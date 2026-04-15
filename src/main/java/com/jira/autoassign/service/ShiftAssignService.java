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

    private final JiraClient jiraClient;
    private final ShiftRosterRepository repository;
    private final AssignmentLogRepository logRepository;
    private final TeamRepository teamRepository;

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
                              AssignmentLogRepository logRepository, TeamRepository teamRepository) {
        this.jiraClient     = jiraClient;
        this.repository     = repository;
        this.logRepository  = logRepository;
        this.teamRepository = teamRepository;
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

        // --- Step 2: assign unassigned tickets (team JQL) ---
        List<JsonNode> unassigned = jiraClient.getTicketsByJql(team.getJql()).stream()
            .filter(t -> t.get("fields").get("assignee").isNull())
            .collect(Collectors.toList());

        log.info("[{}] Unassigned tickets to assign: {}", teamName, unassigned.size());

        int assigned = 0, failed = 0;
        for (JsonNode ticket : unassigned) {
            String issueKey  = ticket.get("key").asText();
            String summary   = ticket.get("fields").path("summary").asText("");
            int    idx       = rrIndex % accountIds.size();
            String accountId = accountIds.get(idx);
            String email     = resolvedEmails.get(idx);

            if (team.isDryRun()) {
                log.info("[{}][DRY-RUN] Would assign [{}] -> {}", teamName, issueKey, email);
                assigned++;
            } else {
                log.info("[{}] [{}] -> {}", teamName, issueKey, email);
                if (jiraClient.assignTicket(issueKey, accountId)) {
                    assigned++;
                    logRepository.save(AssignmentLog.ofAssign(teamId, issueKey, summary, email));
                    // Auto-transition "Waiting for support" → "In Progress"
                    String status = ticket.get("fields").path("status").path("name").asText("");
                    if ("Waiting for support".equalsIgnoreCase(status)) {
                        jiraClient.transitionTicket(issueKey, "In Progress");
                    }
                } else {
                    failed++;
                }
            }
            rrIndex++;
            jiraClient.pauseBetweenAssignments();
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
            String issueKey  = ticket.get("key").asText();
            String summary   = ticket.get("fields").path("summary").asText("");
            int    idx       = rrIndex % accountIds.size();
            String accountId = accountIds.get(idx);
            String email     = resolvedEmails.get(idx);

            if (team.isDryRun()) {
                log.info("[{}][DRY-RUN] Would assign other-escalated [{}] -> {}", teamName, issueKey, email);
            } else {
                log.info("[{}] Assign other-escalated [{}] -> {}", teamName, issueKey, email);
                if (jiraClient.assignTicket(issueKey, accountId)) {
                    logRepository.save(AssignmentLog.ofAssign(teamId, issueKey, summary, email));
                }
            }
            rrIndex++;
            jiraClient.pauseBetweenAssignments();
        }

        // --- Step 3: reassign off-shift people's tickets to active shift ---
        List<String> allRosterEmails = repository.findAllEmailsInRange(teamId, today, yesterday);
        List<String> offShiftEmails  = allRosterEmails.stream()
            .filter(e -> !activeEmails.contains(e))
            .collect(Collectors.toList());

        log.info("[{}] Off-shift sweep — people to check: {}",
            teamName, offShiftEmails.isEmpty() ? "NONE" : offShiftEmails);

        for (String offEmail : offShiftEmails) {
            try {
                String offAccountId = jiraClient.getAccountId(offEmail);
                List<JsonNode> theirTickets = jiraClient.getTicketsAssignedToByJql(offAccountId, team.getJql());

                if (theirTickets.isEmpty()) continue;
                log.info("[{}] {} has {} ticket(s) to reassign", teamName, offEmail, theirTickets.size());

                for (JsonNode ticket : theirTickets) {
                    String issueKey = ticket.get("key").asText();
                    String summary  = ticket.get("fields").path("summary").asText("");
                    int    idx      = rrIndex % accountIds.size();
                    String newAccId = accountIds.get(idx);
                    String newEmail = resolvedEmails.get(idx);

                    if (team.isDryRun()) {
                        log.info("[{}][DRY-RUN] Would reassign [{}] {} -> {}", teamName, issueKey, offEmail, newEmail);
                    } else {
                        log.info("[{}] Reassign [{}] {} -> {}", teamName, issueKey, offEmail, newEmail);
                        if (jiraClient.assignTicket(issueKey, newAccId)) {
                            logRepository.save(AssignmentLog.ofAssign(teamId, issueKey, summary, newEmail));
                        }
                    }
                    rrIndex++;
                    jiraClient.pauseBetweenAssignments();
                }
            } catch (Exception e) {
                log.warn("[{}] Could not sweep off-shift {}: {}", teamName, offEmail, e.getMessage());
            }
        }

        rrIndexByTeam.put(teamId, rrIndex);
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

    public void purgeOldData() {
        LocalDateTime logCutoff    = LocalDateTime.now().minusDays(7);
        LocalDate     rosterCutoff = LocalDate.now().minusDays(7);

        long logsDeleted   = logRepository.deleteByAssignedAtBefore(logCutoff);
        long rosterDeleted = repository.deleteByShiftDateBefore(rosterCutoff);

        log.info("Purged data older than 7 days — activity log: {} rows, shift roster: {} rows.",
                 logsDeleted, rosterDeleted);
    }
}
