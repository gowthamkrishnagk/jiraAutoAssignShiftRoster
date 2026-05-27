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
import java.util.*;
import java.util.concurrent.*;
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
        List<Team> teams = teamRepository.findAll();
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

        Set<String> paused = pausedByTeam.getOrDefault(teamId, Collections.emptySet());

        // --- Step 1: who is on shift right now for this team? ---
        List<ShiftRoster> activeShifts = repository.findActiveShifts(teamId, today, yesterday, now);
        List<String> activeEmails = activeShifts.stream()
            .map(ShiftRoster::getEmail).distinct()
            .filter(e -> !paused.contains(e))
            .collect(Collectors.toList());

        if (!paused.isEmpty()) {
            log.info("[{}] Paused (skipped) assignees: {}", teamName, paused);
        }
        log.info("[{}] Active shift assignees: {}", teamName,
            activeEmails.isEmpty() ? "NONE" : activeEmails);

        if (activeEmails.isEmpty()) {
            log.info("[{}] No active shift right now — skipping assignment.", teamName);
            return;
        }

        // Resolve emails → accountIds
        List<String> accountIds     = new ArrayList<>();
        List<String> resolvedEmails = new ArrayList<>();
        for (String email : activeEmails) {
            try {
                accountIds.add(jiraClient.getAccountId(email));
                resolvedEmails.add(email);
            } catch (Exception e) {
                log.warn("[{}] Could not resolve Jira account for {}: {}", teamName, email, e.getMessage());
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
        List<String> workIds    = new ArrayList<>(accountIds);
        List<String> workEmails = new ArrayList<>(resolvedEmails);
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
                AssignResult res = tryAssignRoundRobin(teamId, teamName, issueKey, workIds, workEmails, workRr);
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
                AssignResult res = tryAssignRoundRobin(teamId, teamName, issueKey, workIds, workEmails, workRr);
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
        List<String> allRosterEmails = repository.findAllEmailsInRange(teamId, today, yesterday);
        List<String> offShiftEmails  = allRosterEmails.stream()
            .filter(e -> !activeEmails.contains(e))
            .filter(e -> !paused.contains(e))
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
                        AssignResult res = tryAssignRoundRobin(teamId, teamName, issueKey, workIds, workEmails, workRr);
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

        rrIndexByTeam.put(teamId, workRr);
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
                                              int workRr) {
        while (!workIds.isEmpty()) {
            int    pos   = workRr % workIds.size();
            String accId = workIds.get(pos);
            String email = workEmails.get(pos);
            log.info("[{}] [{}] -> {}", teamName, issueKey, email);

            if (jiraClient.assignTicket(issueKey, accId)) {
                return new AssignResult(email, accId, workRr + 1);
            }

            // Account unreachable — remove so it doesn't waste future slots this run
            log.warn("[{}] [{}] {} unavailable — removed from this run's rotation (will retry next run)",
                     teamName, issueKey, email);
            workIds.remove(pos);
            workEmails.remove(pos);
            // Do NOT advance workRr: the same position now holds the next account
        }

        log.error("[{}] [{}] All active accounts unavailable — ticket left unassigned", teamName, issueKey);
        return null;
    }

    // -----------------------------------------------------------------------
    // Pause / resume — per team
    // -----------------------------------------------------------------------

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
        return Collections.unmodifiableSet(
            pausedByTeam.getOrDefault(teamId, Collections.emptySet()));
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

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("swapped", true);
        result.put("shiftA", shiftRowMap(a));
        result.put("shiftB", shiftRowMap(b));
        return result;
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
