package com.jira.autoassign.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.jira.autoassign.client.JiraClient;
import com.jira.autoassign.entity.AssignmentLog;
import com.jira.autoassign.entity.ShiftRoster;
import com.jira.autoassign.entity.Team;
import com.jira.autoassign.repository.AssignmentLogRepository;
import com.jira.autoassign.repository.ShiftRosterRepository;
import com.jira.autoassign.repository.TeamRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Core shift-based assignment logic.
 *
 * Every scheduler tick runs all registered teams in isolation:
 *  1. Find who is currently ON shift for that team.
 *  2. Assign only unassigned Jira tickets (from the team's JQL) round-robin.
 *  3. Sweep off-shift people's tickets and reassign to active shift (same team).
 *
 * Each team has its own round-robin index and paused-email set — zero cross-team interference.
 */
@Service
public class ShiftAssignService {

    private static final Logger log = LoggerFactory.getLogger(ShiftAssignService.class);

    /** Returned by tryAssign — holds result and the updated working-list index. */
    private record AssignResult(String email, String accountId, int nextWorkRr) {}

    private final JiraClient jiraClient;
    private final ShiftRosterRepository repository;
    private final AssignmentLogRepository logRepository;
    private final TeamRepository teamRepository;
    private final WebhookService webhookService;

    // Per-team round-robin index (resets on restart — fine)
    private final Map<String, Integer> rrIndexByTeam = new ConcurrentHashMap<>();

    // Per-team paused emails
    private final Map<String, Set<String>> pausedByTeam = new ConcurrentHashMap<>();

    // Per-team discovered-unavailable emails → epoch millis until which to skip them.
    // An account whose Jira assignment fails (deactivated / frozen / locked) still
    // resolves a valid accountId, so without this it keeps occupying a round-robin
    // slot and the next person in sorted order silently absorbs its share. Parking it
    // for a cooldown keeps the rotation — and the index normalization — over only the
    // reachable people, so distribution stays even. Auto-retried once the cooldown lapses.
    private final Map<String, Map<String, Long>> unavailableByTeam = new ConcurrentHashMap<>();
    private static final long UNAVAILABLE_COOLDOWN_MS = 60 * 60 * 1000L; // 1 hour

    // One thread per team, daemon so they don't block JVM shutdown
    private final ExecutorService teamExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        return t;
    });

    public ShiftAssignService(JiraClient jiraClient, ShiftRosterRepository repository,
                              AssignmentLogRepository logRepository, TeamRepository teamRepository,
                              WebhookService webhookService) {
        this.jiraClient     = jiraClient;
        this.repository     = repository;
        this.logRepository  = logRepository;
        this.teamRepository = teamRepository;
        this.webhookService = webhookService;
    }

    // -----------------------------------------------------------------------
    // Called by scheduler every minute — runs all teams independently
    // -----------------------------------------------------------------------

    public void runAllTeams() {
        // Monitor-only teams (e.g. B2B) are never auto-assigned — they're handled
        // separately by B2bNotifyService.
        List<Team> teams = teamRepository.findAll().stream()
            .filter(Team::isAutoAssign)
            .collect(Collectors.toList());
        if (teams.isEmpty()) return;

        log.info("Starting parallel assignment run for {} team(s): {}",
            teams.size(), teams.stream().map(Team::getName).collect(Collectors.joining(", ")));

        List<CompletableFuture<Void>> futures = teams.stream()
            .map(team -> CompletableFuture.runAsync(() -> {
                try {
                    runShiftAssignment(team);
                } catch (Exception e) {
                    log.error("[{}] Assignment run failed: {}", team.getName(), e.getMessage(), e);
                }
            }, teamExecutor))
            .collect(Collectors.toList());

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                             .get(10, TimeUnit.MINUTES); // safety timeout
            log.info("All teams finished assignment run.");
        } catch (TimeoutException e) {
            log.error("Assignment run timed out after 10 minutes — some teams may not have completed.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Assignment run interrupted.");
        } catch (ExecutionException e) {
            log.error("Assignment run execution error: {}", e.getCause().getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Single-team assignment run
    // -----------------------------------------------------------------------

    public void runShiftAssignment(Team team) {
        String teamName = team.getName();
        String teamId   = team.getId();
        log.info("=== [{}] Shift Assignment Run{} ===", teamName, team.isDryRun() ? " [DRY-RUN]" : "");

        LocalDate today     = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);
        LocalTime now       = LocalTime.now();

        clearPausesForEndedShifts(teamId);
        Set<String> paused = pausedByTeam.getOrDefault(teamId, Collections.emptySet());

        // Emails currently parked because their Jira assignment failed (frozen/locked).
        // Cooldown-expired entries are pruned so they get retried this run.
        long nowMs = System.currentTimeMillis();
        Map<String, Long> unavailable = unavailableByTeam.getOrDefault(teamId, Collections.emptyMap());
        unavailable.values().removeIf(until -> until <= nowMs);
        Set<String> skip = unavailable.keySet();

        // --- Step 1: who is on shift right now for this team? ---
        List<ShiftRoster> activeShifts = repository.findActiveShifts(teamId, today, yesterday, now);
        List<String> activeEmails = activeShifts.stream()
            .map(ShiftRoster::getEmail).distinct()
            .filter(e -> !paused.contains(e))
            .filter(e -> !skip.contains(e.toLowerCase().trim()))
            .collect(Collectors.toList());

        if (!paused.isEmpty()) {
            log.info("[{}] Paused (skipped) assignees: {}", teamName, paused);
        }
        if (!skip.isEmpty()) {
            log.info("[{}] Temporarily unavailable (assignment failed, in cooldown): {}", teamName, skip);
        }
        log.info("[{}] Active shift assignees: {}", teamName,
            activeEmails.isEmpty() ? "NONE" : activeEmails);

        if (activeEmails.isEmpty()) {
            log.info("[{}] No active shift right now — skipping assignment.", teamName);
            return;
        }

        // Owner email (lower) → cover substitute email. For "covered" rows the owner's
        // Jira is locked, so tickets are assigned to the cover instead — but the owner
        // stays the displayed/logged assignee and each ticket is commented in their name.
        Map<String, String> coverByOwner = new HashMap<>();
        for (ShiftRoster s : activeShifts) {
            if (s.getCoverEmail() != null && !s.getCoverEmail().isBlank()) {
                coverByOwner.put(s.getEmail().toLowerCase().trim(), s.getCoverEmail().trim());
            }
        }

        // Resolve emails → accountIds. For covered owners we resolve the COVER's account
        // (who actually receives the ticket) while keeping the owner email for display/log.
        List<String> accountIds     = new ArrayList<>();
        List<String> resolvedEmails = new ArrayList<>();   // owner emails — display/log
        List<String> resolvedCovers = new ArrayList<>();   // cover email, or null
        for (String email : activeEmails) {
            String cover       = coverByOwner.get(email.toLowerCase().trim());
            String assignEmail = cover != null ? cover : email;
            try {
                accountIds.add(jiraClient.getAccountId(assignEmail));
                resolvedEmails.add(email);
                resolvedCovers.add(cover);
            } catch (Exception e) {
                log.warn("[{}] Could not resolve Jira account for {}{}: {}", teamName, assignEmail,
                    cover != null ? " (cover for " + email + ")" : "", e.getMessage());
            }
        }

        if (accountIds.isEmpty()) {
            log.warn("[{}] Could not resolve any active shift accounts — skipping.", teamName);
            return;
        }

        int rrIndex = rrIndexByTeam.getOrDefault(teamId, 0);

        // Mutable working lists for this run.
        // Any account that fails a Jira assignment is removed on the spot so
        // remaining tickets go only to reachable accounts — perfect equal spread.
        // The full list is rebuilt from accountIds at the start of every run,
        // so a previously locked account is retried fresh next scheduler tick.
        // Sort both lists in tandem by email so the rr index maps to the same person
        // across runs, regardless of the order the DB returns active shifts.
        List<String[]> pairs = new ArrayList<>();
        for (int i = 0; i < resolvedEmails.size(); i++) {
            // [owner email, accountId to assign, cover email or null]
            pairs.add(new String[]{ resolvedEmails.get(i), accountIds.get(i), resolvedCovers.get(i) });
        }
        pairs.sort(Comparator.comparing((String[] p) -> p[0]));
        List<String> workEmails = pairs.stream().map(p -> p[0]).collect(Collectors.toList());
        List<String> workIds    = pairs.stream().map(p -> p[1]).collect(Collectors.toList());
        List<String> workCovers = pairs.stream().map(p -> p[2]).collect(Collectors.toList()); // may hold null
        int workRr = rrIndex;

        // --- Step 2: assign unassigned tickets (team JQL) ---
        List<JsonNode> unassigned = jiraClient.getTicketsByJql(team.getJql()).stream()
            .filter(t -> t.get("fields").get("assignee").isNull())
            .collect(Collectors.toList());

        log.info("[{}] Unassigned tickets to assign: {}", teamName, unassigned.size());

        int assigned = 0, failed = 0;
        for (JsonNode ticket : unassigned) {
            String issueKey = ticket.get("key").asText();
            String summary  = ticket.get("fields").path("summary").asText("");

            if (team.isDryRun()) {
                if (!workIds.isEmpty()) {
                    log.info("[{}][DRY-RUN] Would assign [{}] -> {}",
                        teamName, issueKey, workEmails.get(workRr % workIds.size()));
                    assigned++;
                    workRr++;
                }
            } else {
                AssignResult res = tryAssignRoundRobin(teamId, teamName, issueKey, workIds, workEmails, workCovers, workRr);
                if (res != null) {
                    assigned++;
                    workRr = res.nextWorkRr();
                    logRepository.save(AssignmentLog.ofAssign(teamId, issueKey, summary, res.email()));
                    String status = ticket.get("fields").path("status").path("name").asText("");
                    if ("Waiting for support".equalsIgnoreCase(status)) {
                        jiraClient.transitionTicket(issueKey, "In Progress");
                    }
                } else {
                    failed++;
                    workRr++;
                }
                jiraClient.pauseBetweenAssignments();
            }
        }
        log.info("[{}] Done — assigned: {}, failed: {}", teamName, assigned, failed);

        // --- Step 2b: escalated + unassigned ---
        // ITOPS L2 Salesforce / Salesforce L3 B2B / Salesforce L3 B2C → restore to last historical assignee
        // Any other escalation path → assign round-robin to active shift

        final List<String> restorePaths = List.of(
            "ITOPS L2 Salesforce",
            "Salesforce L3 B2B",
            "Salesforce L3 B2C"
        );

        // 2b-i: restore paths → restore to last assignee
        List<JsonNode> restoreEscalated = jiraClient.getEscalatedByPathsAndJql(team.getJql(), restorePaths).stream()
            .filter(t -> t.get("fields").get("assignee").isNull())
            .collect(Collectors.toList());

        log.info("[{}] Escalated (restore paths) to restore: {}", teamName, restoreEscalated.size());

        for (JsonNode ticket : restoreEscalated) {
            String issueKey  = ticket.get("key").asText();
            String summary   = ticket.get("fields").path("summary").asText("");
            String lastAccId = jiraClient.getLastAssigneeAccountId(issueKey);

            if (lastAccId == null) {
                log.info("[{}] [{}] No assignee history — skipping", teamName, issueKey);
                continue;
            }
            String lastEmail = jiraClient.getUserEmail(lastAccId);

            if (team.isDryRun()) {
                log.info("[{}][DRY-RUN] Would restore escalated [{}] -> {}", teamName, issueKey, lastEmail);
            } else {
                log.info("[{}] Restore escalated [{}] -> {}", teamName, issueKey, lastEmail);
                if (jiraClient.assignTicket(issueKey, lastAccId)) {
                    logRepository.save(AssignmentLog.ofAssign(teamId, issueKey, summary, lastEmail));
                }
            }
            jiraClient.pauseBetweenAssignments();
        }

        // 2b-ii: other escalation paths → round-robin to active shift
        List<JsonNode> otherEscalated = jiraClient.getEscalatedNotByPathsAndJql(team.getJql(), restorePaths).stream()
            .filter(t -> t.get("fields").get("assignee").isNull())
            .collect(Collectors.toList());

        log.info("[{}] Other-path escalated to assign round-robin: {}", teamName, otherEscalated.size());

        for (JsonNode ticket : otherEscalated) {
            String issueKey = ticket.get("key").asText();
            String summary  = ticket.get("fields").path("summary").asText("");

            if (team.isDryRun()) {
                if (!workIds.isEmpty()) {
                    log.info("[{}][DRY-RUN] Would assign other-escalated [{}] -> {}",
                        teamName, issueKey, workEmails.get(workRr % workIds.size()));
                    workRr++;
                }
            } else {
                AssignResult res = tryAssignRoundRobin(teamId, teamName, issueKey, workIds, workEmails, workCovers, workRr);
                if (res != null) {
                    workRr = res.nextWorkRr();
                    logRepository.save(AssignmentLog.ofAssign(teamId, issueKey, summary, res.email()));
                } else {
                    workRr++;
                }
                jiraClient.pauseBetweenAssignments();
            }
        }

        // --- Step 3: reassign off-shift people's tickets to active shift ---
        // Paused people are excluded: they are still on shift, just temporarily paused —
        // don't reassign their tickets while they are paused.
        //
        // Active swap/cover targets are also excluded: a swap deliberately routes the
        // rostered owner's tickets to a substitute, so for the duration of that shift the
        // substitute is effectively on duty today. Treating them as "off-shift" here would
        // let the sweep claw those tickets straight back — undoing the swap within a tick.
        // (The rostered owner remains the displayed/credited assignee; only the receiving
        // email changes.)
        Set<String> activeCoverTargets = coverByOwner.values().stream()
            .map(c -> c.toLowerCase().trim())
            .collect(Collectors.toSet());

        List<String> allRosterEmails = repository.findAllEmailsInRange(teamId, today, yesterday);
        List<String> offShiftEmails  = allRosterEmails.stream()
            .filter(e -> !activeEmails.contains(e))
            .filter(e -> !paused.contains(e))
            .filter(e -> !activeCoverTargets.contains(e.toLowerCase().trim()))
            .collect(Collectors.toList());

        log.info("[{}] Off-shift sweep — people to check: {}",
            teamName, offShiftEmails.isEmpty() ? "NONE" : offShiftEmails);

        // Collect ALL reassignments across every off-shift person → one batched webhook
        List<Map<String, String>> allReassigned = new ArrayList<>();

        for (String offEmail : offShiftEmails) {
            try {
                String offAccountId = jiraClient.getAccountId(offEmail);
                // Fetch with SLA field so we can include remaining time in the notification
                List<JsonNode> theirTickets = jiraClient.getTicketsAssignedToByJqlWithSla(offAccountId, team.getJql());

                if (theirTickets.isEmpty()) continue;
                log.info("[{}] {} has {} ticket(s) to reassign", teamName, offEmail, theirTickets.size());

                for (JsonNode ticket : theirTickets) {
                    String issueKey = ticket.get("key").asText();
                    String summary  = ticket.get("fields").path("summary").asText("");

                    if (team.isDryRun()) {
                        if (!workIds.isEmpty()) {
                            String newEmail = workEmails.get(workRr % workIds.size());
                            log.info("[{}][DRY-RUN] Would reassign [{}] {} -> {}", teamName, issueKey, offEmail, newEmail);
                            workRr++;
                        }
                    } else {
                        AssignResult res = tryAssignRoundRobin(teamId, teamName, issueKey, workIds, workEmails, workCovers, workRr);
                        if (res != null) {
                            workRr = res.nextWorkRr();
                            logRepository.save(AssignmentLog.ofAssign(teamId, issueKey, summary, res.email()));
                            String slaRemaining = jiraClient.extractSlaRemaining(ticket);
                            Map<String, String> t = new LinkedHashMap<>();
                            t.put("key",             issueKey);
                            t.put("summary",         summary);
                            t.put("slaRemaining",    slaRemaining);
                            t.put("currentAssignee", offEmail);
                            t.put("reassignedTo",    res.email());
                            allReassigned.add(t);
                        } else {
                            workRr++;
                        }
                        jiraClient.pauseBetweenAssignments();
                    }
                }
            } catch (Exception e) {
                log.warn("[{}] Could not sweep off-shift {}: {}", teamName, offEmail, e.getMessage());
            }
        }

        // Fire one single webhook with the full picture across all off-shift people
        if (!allReassigned.isEmpty()) {
            webhookService.fireReassignments(teamId, teamName, allReassigned);
        }

        // Normalize so the stored index stays in [0, teamSize) across team size changes.
        int normalizedRr = resolvedEmails.isEmpty() ? 0 : workRr % resolvedEmails.size();
        rrIndexByTeam.put(teamId, normalizedRr);
    }

    // -----------------------------------------------------------------------
    // Round-robin assignment with dynamic working list
    // -----------------------------------------------------------------------

    /**
     * Assigns issueKey using a simple round-robin on the provided working lists.
     *
     * If the chosen account fails (locked / unavailable in Jira) it is removed
     * from workIds/workEmails immediately, so:
     *   • the same ticket retries with the very next account in line
     *   • all subsequent tickets in this run skip the locked account entirely
     *   • the round-robin cycles only over reachable accounts → perfect equal spread
     *
     * The working lists are rebuilt from the full active roster at the start of
     * every scheduler run, so a previously locked account is retried fresh next tick.
     *
     * Returns AssignResult on success, or null if every account in workIds failed.
     */
    private AssignResult tryAssignRoundRobin(String teamId, String teamName, String issueKey,
                                              List<String> workIds, List<String> workEmails,
                                              List<String> workCovers, int workRr) {
        while (!workIds.isEmpty()) {
            int    pos   = workRr % workIds.size();
            String accId = workIds.get(pos);
            String email = workEmails.get(pos);
            String cover = workCovers.get(pos);   // null unless this slot is under cover
            log.info("[{}] [{}] -> {}{}", teamName, issueKey, email,
                     cover != null ? " (assigned to cover " + cover + ")" : "");

            if (jiraClient.assignTicket(issueKey, accId)) {
                // Under cover: credit the real shift owner by name on the ticket itself.
                if (cover != null) {
                    jiraClient.postComment(issueKey, coverComment(email));
                }
                return new AssignResult(email, accId, workRr + 1);
            }

            // Account unreachable — remove so it doesn't waste future slots this run,
            // and park it in cooldown so subsequent runs skip it entirely (keeps the
            // rotation even). Auto-retried once UNAVAILABLE_COOLDOWN_MS lapses.
            log.warn("[{}] [{}] {} unavailable — removed from rotation, parked for {} min",
                     teamName, issueKey, email, UNAVAILABLE_COOLDOWN_MS / 60000);
            markUnavailable(teamId, email);
            workIds.remove(pos);
            workEmails.remove(pos);
            workCovers.remove(pos);
            // Do NOT advance workRr: the same position now holds the next account
        }

        log.error("[{}] [{}] All active accounts unavailable — ticket left unassigned", teamName, issueKey);
        return null;
    }

    // -----------------------------------------------------------------------
    // Pause / resume — per team
    // -----------------------------------------------------------------------

    /** Parks an email whose Jira assignment failed so it's skipped until the cooldown lapses. */
    private void markUnavailable(String teamId, String email) {
        unavailableByTeam.computeIfAbsent(teamId, k -> new ConcurrentHashMap<>())
            .put(email.toLowerCase().trim(), System.currentTimeMillis() + UNAVAILABLE_COOLDOWN_MS);
    }

    public void pauseEmail(String teamId, String email) {
        pausedByTeam.computeIfAbsent(teamId, k -> ConcurrentHashMap.newKeySet())
                    .add(email.toLowerCase().trim());
        log.info("[{}] Paused from assignments: {}", teamId, email);
    }

    public void resumeEmail(String teamId, String email) {
        Set<String> set = pausedByTeam.get(teamId);
        if (set != null) set.remove(email.toLowerCase().trim());
        log.info("[{}] Resumed for assignments: {}", teamId, email);
    }

    public Set<String> getPausedEmails(String teamId) {
        clearPausesForEndedShifts(teamId);
        return Collections.unmodifiableSet(
            pausedByTeam.getOrDefault(teamId, Collections.emptySet()));
    }

    /**
     * A break is scoped to the shift it was taken in: once the person's shift
     * ends, their pause is dropped automatically — even if they never hit
     * Resume — so they start their next shift available. Overnight shifts
     * (e.g. 15:30 → 00:30) stay paused until the end crosses midnight, because
     * findActiveShifts still matches yesterday's row until then.
     */
    private void clearPausesForEndedShifts(String teamId) {
        Set<String> paused = pausedByTeam.get(teamId);
        if (paused == null || paused.isEmpty()) return;

        LocalDate today = LocalDate.now();
        Set<String> onShiftNow = repository
            .findActiveShifts(teamId, today, today.minusDays(1), LocalTime.now())
            .stream()
            .map(s -> s.getEmail().toLowerCase().trim())
            .collect(Collectors.toSet());

        paused.removeIf(email -> {
            boolean shiftOver = !onShiftNow.contains(email);
            if (shiftOver) {
                log.info("[{}] Shift ended — break auto-cleared for {}", teamId, email);
            }
            return shiftOver;
        });
    }

    /**
     * Current round-robin pointer for a team — the position (into the email-sorted
     * active roster) that the next auto-assigned ticket will go to. Persists between
     * scheduler runs, normalized to [0, activeCount). Read-only accessor for the UI.
     */
    public int getRrIndex(String teamId) {
        return rrIndexByTeam.getOrDefault(teamId, 0);
    }

    // -----------------------------------------------------------------------
    // Queries used by REST endpoints
    // -----------------------------------------------------------------------

    public List<ShiftRoster> getActiveShifts(String teamId) {
        LocalDate today = LocalDate.now();
        return repository.findActiveShifts(teamId, today, today.minusDays(1), LocalTime.now());
    }

    public List<ShiftRoster> getCurrentMonthSchedule(String teamId) {
        LocalDate now = LocalDate.now();
        return repository.findByShiftDateBetween(
            teamId,
            now.withDayOfMonth(1),
            now.withDayOfMonth(now.lengthOfMonth())
        );
    }

    /**
     * All shifts visible in "Today's Shift" panel — only rows whose shiftDate = today.
     * Yesterday's overnight rows are intentionally excluded; the panel shows today's schedule only.
     */
    public List<ShiftRoster> getTodayShifts(String teamId) {
        return repository.findByTeamIdAndDate(teamId, LocalDate.now());
    }

    public List<ShiftRoster> getShiftsForDate(String teamId, LocalDate date) {
        return repository.findByTeamIdAndDate(teamId, date);
    }

    /**
     * Returns upcoming shift boundaries (end + start) across ALL teams for today,
     * sorted by how soon they occur. Used by the webhook modal to show
     * "next reassignment" — i.e. when the next webhook will actually fire.
     *
     * Shift-end   → off-shift sweep reassigns tickets (primary webhook trigger).
     * Shift-start → unassigned tickets are picked up by the new assignee.
     */
    public List<Map<String, Object>> getUpcomingHandovers() {
        LocalDate today     = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);
        LocalDate tomorrow  = today.plusDays(1);
        LocalTime now       = LocalTime.now();
        DateTimeFormatter timeFmt = DateTimeFormatter.ofPattern("HH:mm");

        // Seconds from now until midnight — used for overnight shift end calculations
        long secsToMidnight = 86400L - now.toSecondOfDay();

        // Build teamId → display-name map (falls back to raw teamId string if not found)
        Map<String, String> teamNames = teamRepository.findAll().stream()
            .filter(t -> t.getId() != null)
            .collect(Collectors.toMap(
                Team::getId,
                t -> t.getName() != null && !t.getName().isBlank() ? t.getName() : t.getId(),
                (a, b) -> a
            ));

        List<Map<String, Object>> result = new ArrayList<>();

        // ── Yesterday's overnight shifts whose end is still in the future today ─
        // e.g. shift stored on 2026-05-26 with start=22:00, end=06:00
        // → at 01:00 today their end (06:00) is still upcoming
        for (ShiftRoster s : repository.findAllForDate(yesterday)) {
            boolean overnight = s.getShiftStart().isAfter(s.getShiftEnd());
            if (overnight && s.getShiftEnd().isAfter(now)) {
                long secs = ChronoUnit.SECONDS.between(now, s.getShiftEnd());
                addHandover(result, s.getTeamId(),
                    teamNames.getOrDefault(s.getTeamId(), s.getTeamId()),
                    s.getShiftEnd().format(timeFmt), secs, s.getEmail(), "shift_end");
            }
        }

        // ── Today's shift boundaries ──────────────────────────────────────────
        for (ShiftRoster s : repository.findAllForDate(today)) {
            String teamName = teamNames.getOrDefault(s.getTeamId(), s.getTeamId());
            boolean overnight = s.getShiftStart().isAfter(s.getShiftEnd());

            if (!overnight) {
                // Normal shift: start and end are both within today
                if (s.getShiftEnd().isAfter(now)) {
                    long secs = ChronoUnit.SECONDS.between(now, s.getShiftEnd());
                    addHandover(result, s.getTeamId(), teamName,
                        s.getShiftEnd().format(timeFmt), secs, s.getEmail(), "shift_end");
                }
                if (s.getShiftStart().isAfter(now)) {
                    long secs = ChronoUnit.SECONDS.between(now, s.getShiftStart());
                    addHandover(result, s.getTeamId(), teamName,
                        s.getShiftStart().format(timeFmt), secs, s.getEmail(), "shift_start");
                }
            } else {
                // Overnight shift (e.g. 14:30 → 02:30): end crosses midnight into tomorrow.
                // secsEnd is always positive — it's the distance to tomorrow's end time.
                long secsEnd = secsToMidnight + s.getShiftEnd().toSecondOfDay();
                addHandover(result, s.getTeamId(), teamName,
                    "tomorrow " + s.getShiftEnd().format(timeFmt), secsEnd, s.getEmail(), "shift_end");
                // Start is today — only show if still upcoming
                if (s.getShiftStart().isAfter(now)) {
                    long secsStart = ChronoUnit.SECONDS.between(now, s.getShiftStart());
                    addHandover(result, s.getTeamId(), teamName,
                        s.getShiftStart().format(timeFmt), secsStart, s.getEmail(), "shift_start");
                }
            }
        }

        // ── If nothing remains today/tonight, look at tomorrow ────────────────
        if (result.isEmpty()) {
            for (ShiftRoster s : repository.findAllForDate(tomorrow)) {
                String teamName = teamNames.getOrDefault(s.getTeamId(), s.getTeamId());
                long secsEnd   = secsToMidnight + s.getShiftEnd().toSecondOfDay();
                long secsStart = secsToMidnight + s.getShiftStart().toSecondOfDay();
                addHandover(result, s.getTeamId(), teamName,
                    "tomorrow " + s.getShiftEnd().format(timeFmt),   secsEnd,   s.getEmail(), "shift_end");
                addHandover(result, s.getTeamId(), teamName,
                    "tomorrow " + s.getShiftStart().format(timeFmt), secsStart, s.getEmail(), "shift_start");
            }
        }

        result.sort(Comparator.comparingLong(m -> (long) m.get("secondsUntil")));
        return result;
    }

    private static void addHandover(List<Map<String, Object>> out,
                                     String teamId, String teamName,
                                     String atDisplay, long secsUntil,
                                     String email, String type) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("teamId",       teamId);
        m.put("teamName",     teamName);
        m.put("at",           atDisplay);
        m.put("secondsUntil", secsUntil);
        m.put("minutesUntil", secsUntil / 60);
        m.put("email",        email);
        m.put("type",         type);   // "shift_end" or "shift_start"
        out.add(m);
    }

    /**
     * Permanently removes a single ShiftRoster row (used by "Remove from today's shift").
     * Also clears the pause state for that email so they don't linger in the paused set.
     */
    public void removeShiftRow(Long id, String teamId) {
        ShiftRoster row = repository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Shift row not found: " + id));
        if (!teamId.equals(row.getTeamId()))
            throw new IllegalArgumentException("Row does not belong to team: " + teamId);
        repository.deleteById(id);
        resumeEmail(teamId, row.getEmail());   // clear any lingering break state
        log.info("[{}] Removed from today's shift: {} (row {})", teamId, row.getEmail(), id);
    }

    /**
     * Adds a single ShiftRoster row for TODAY (used by "Add to today's shift").
     * Triggers an assignment run so a person added during an active shift
     * immediately picks up tickets.
     */
    public Map<String, Object> addShiftRow(String teamId, String email, LocalTime start, LocalTime end) {
        if (email == null || email.isBlank())
            throw new IllegalArgumentException("Email is required");
        if (!isValidEmail(email))
            throw new IllegalArgumentException("'" + email.trim() + "' is not a valid email address");
        if (start == null || end == null)
            throw new IllegalArgumentException("Shift start and end are required");
        teamRepository.findById(teamId)
            .orElseThrow(() -> new IllegalArgumentException("Unknown team: " + teamId));

        String cleanEmail = email.trim();
        LocalDate today   = LocalDate.now();

        // Reject an exact duplicate — same person, same shift window, already on today.
        boolean duplicate = repository.findByTeamIdAndDate(teamId, today).stream().anyMatch(r ->
            r.getEmail() != null
            && r.getEmail().trim().equalsIgnoreCase(cleanEmail)
            && r.getShiftStart().equals(start)
            && r.getShiftEnd().equals(end));
        if (duplicate)
            throw new IllegalArgumentException(
                cleanEmail + " is already on today's shift for " + start + "–" + end);

        ShiftRoster row = new ShiftRoster();
        row.setTeamId(teamId);
        row.setEmail(cleanEmail);
        row.setShiftDate(today);
        row.setShiftStart(start);
        row.setShiftEnd(end);
        row.setCreatedAt(LocalDateTime.now());
        repository.save(row);

        // Clear any stale break state so the new addition starts available.
        resumeEmail(teamId, cleanEmail);

        log.info("[{}] Added to today's shift: {} ({}–{}) (row {})",
            teamId, cleanEmail, start, end, row.getId());

        triggerAssignmentRun(teamId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("added", true);
        result.put("shift", shiftRowMap(row));
        return result;
    }

    /**
     * Puts a shift row "under cover" (or clears it). The owner stays the displayed and
     * logged assignee, but while a cover is set tickets are assigned to coverEmail in Jira
     * and each ticket is commented as worked by the owner. Pass a blank coverEmail to clear.
     */
    public Map<String, Object> setCover(Long id, String coverEmail, String teamId) {
        ShiftRoster row = repository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Shift row not found: " + id));
        if (!teamId.equals(row.getTeamId()))
            throw new IllegalArgumentException("Shift does not belong to team: " + teamId);

        String clean = coverEmail == null ? "" : coverEmail.trim();
        if (clean.isBlank()) {
            row.setCoverEmail(null);
            repository.save(row);
            log.info("[{}] Cover cleared on row {} ({})", teamId, id, row.getEmail());
        } else {
            if (!isValidEmail(clean))
                throw new IllegalArgumentException("'" + clean + "' is not a valid email address");
            if (clean.equalsIgnoreCase(row.getEmail().trim()))
                throw new IllegalArgumentException("Cover cannot be the same person as the shift owner");
            row.setCoverEmail(clean);
            repository.save(row);
            log.info("[{}] Cover set on row {}: {} → tickets assigned to {}", teamId, id, row.getEmail(), clean);
        }

        triggerAssignmentRun(teamId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("covered",   row.getCoverEmail() != null);
        result.put("coverName", row.getCoverEmail() != null ? displayNameFromEmail(row.getCoverEmail()) : null);
        result.put("shift",     shiftRowMap(row));
        return result;
    }

    /** Comment posted on tickets routed through a cover — credits the real shift owner by name. */
    private static String coverComment(String ownerEmail) {
        return "This ticket is worked by " + displayNameFromEmail(ownerEmail)
             + ", the actual shift assignee.";
    }

    // A pragmatic email check: exactly one @, non-empty local and domain parts,
    // a dot in the domain, and no whitespace. Rejects a display name accidentally
    // typed into the email field (e.g. "Jecintha A"), which would otherwise become
    // a phantom rotation slot that can never receive a Jira ticket.
    private static final Pattern EMAIL_RE = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    /** True when the string is a syntactically valid email address. */
    public static boolean isValidEmail(String email) {
        return email != null && EMAIL_RE.matcher(email.trim()).matches();
    }

    /** Derives a human display name from an email's local part, e.g. gowtham.krishna@x → "Gowtham Krishna". */
    public static String displayNameFromEmail(String email) {
        if (email == null || email.isBlank()) return "";
        String local = email.trim();
        int at = local.indexOf('@');
        if (at > 0) local = local.substring(0, at);
        StringBuilder sb = new StringBuilder();
        for (String part : local.split("[._+\\-]+")) {
            if (part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) sb.append(part.substring(1).toLowerCase());
        }
        return sb.length() == 0 ? email.trim() : sb.toString();
    }

    // -----------------------------------------------------------------------
    // Shift swap
    // -----------------------------------------------------------------------

    /**
     * Swaps the email (assignee) between two ShiftRoster rows.
     * Both rows must belong to the given team.
     *
     * @throws IllegalArgumentException if either row is not found or doesn't belong to the team
     */
    public Map<String, Object> swapShifts(Long idA, Long idB, String teamId) {
        ShiftRoster a = repository.findById(idA)
            .orElseThrow(() -> new IllegalArgumentException("Shift row not found: " + idA));
        ShiftRoster b = repository.findById(idB)
            .orElseThrow(() -> new IllegalArgumentException("Shift row not found: " + idB));

        if (!teamId.equals(a.getTeamId()) || !teamId.equals(b.getTeamId()))
            throw new IllegalArgumentException("Both shifts must belong to team: " + teamId);

        if (idA.equals(idB))
            throw new IllegalArgumentException("Cannot swap a shift with itself");

        String emailA = a.getEmail();
        a.setEmail(b.getEmail());
        b.setEmail(emailA);
        repository.save(a);
        repository.save(b);

        log.info("[{}] Shift swap: row {} ({}) ↔ row {} ({})",
            teamId, idA, b.getEmail(), idB, a.getEmail());

        triggerAssignmentRun(teamId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("swapped", true);
        result.put("shiftA", shiftRowMap(a));
        result.put("shiftB", shiftRowMap(b));
        return result;
    }

    public Map<String, Object> replaceShiftEmail(Long id, String newEmail, String teamId) {
        ShiftRoster row = repository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Shift row not found: " + id));

        if (!teamId.equals(row.getTeamId()))
            throw new IllegalArgumentException("Shift does not belong to team: " + teamId);

        if (newEmail.equals(row.getEmail()))
            throw new IllegalArgumentException("New email is the same as the current one");
        if (!isValidEmail(newEmail))
            throw new IllegalArgumentException("'" + newEmail + "' is not a valid email address");

        // Don't let a rename collapse two rows into a duplicate of another same-window entry.
        boolean duplicate = repository.findByTeamIdAndDate(teamId, row.getShiftDate()).stream().anyMatch(r ->
            !r.getId().equals(row.getId())
            && r.getEmail() != null
            && r.getEmail().trim().equalsIgnoreCase(newEmail.trim())
            && r.getShiftStart().equals(row.getShiftStart())
            && r.getShiftEnd().equals(row.getShiftEnd()));
        if (duplicate)
            throw new IllegalArgumentException(
                newEmail.trim() + " already has this shift (" + row.getShiftStart() + "–" + row.getShiftEnd() + ")");

        String oldEmail = row.getEmail();
        row.setEmail(newEmail);
        repository.save(row);

        log.info("[{}] Shift email replaced: row {} {} → {}", teamId, id, oldEmail, newEmail);

        triggerAssignmentRun(teamId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("replaced", true);
        result.put("shift", shiftRowMap(row));
        return result;
    }

    public Map<String, Object> swapAssignee(String fromEmail, String toEmail, String teamId) {
        if (fromEmail.equals(toEmail))
            throw new IllegalArgumentException("fromEmail and toEmail are the same");
        if (!isValidEmail(toEmail))
            throw new IllegalArgumentException("'" + toEmail + "' is not a valid email address");

        Team team = teamRepository.findById(teamId)
            .orElseThrow(() -> new IllegalArgumentException("Team not found: " + teamId));

        String fromAccountId = jiraClient.getAccountId(fromEmail);
        String toAccountId   = jiraClient.getAccountId(toEmail);

        List<JsonNode> tickets = jiraClient.getTicketsAssignedToByJql(fromAccountId, team.getJql());
        log.info("[{}] Swap assignee {} → {}: {} ticket(s)", teamId, fromEmail, toEmail, tickets.size());

        int reassigned = 0;
        for (JsonNode ticket : tickets) {
            String issueKey = ticket.get("key").asText();
            String summary  = ticket.get("fields").path("summary").asText("");
            if (jiraClient.assignTicket(issueKey, toAccountId)) {
                logRepository.save(AssignmentLog.ofAssign(teamId, issueKey, summary, toEmail));
                reassigned++;
            }
            jiraClient.pauseBetweenAssignments();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("swapped", true);
        result.put("from", fromEmail);
        result.put("to", toEmail);
        result.put("reassigned", reassigned);
        return result;
    }

    private void triggerAssignmentRun(String teamId) {
        teamRepository.findById(teamId).ifPresent(team ->
            CompletableFuture.runAsync(() -> {
                try {
                    runShiftAssignment(team);
                } catch (Exception e) {
                    log.error("[{}] Assignment run after roster change failed: {}", teamId, e.getMessage(), e);
                }
            }, teamExecutor)
        );
    }

    private Map<String, Object> shiftRowMap(ShiftRoster s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",    s.getId());
        m.put("email", s.getEmail());
        m.put("date",  s.getShiftDate().toString());
        m.put("start", s.getShiftStart().toString());
        m.put("end",   s.getShiftEnd().toString());
        return m;
    }

    public void purgeOldData() {
        LocalDateTime logCutoff    = LocalDateTime.now().minusDays(7);
        LocalDate     rosterCutoff = LocalDate.now().minusDays(7);

        long logsDeleted   = logRepository.deleteByAssignedAtBefore(logCutoff);
        long rosterDeleted = repository.deleteByShiftDateBefore(rosterCutoff);

        log.info("Purged data older than 7 days — activity log: {} rows, shift roster: {} rows.",
                 logsDeleted, rosterDeleted);
    }
}
