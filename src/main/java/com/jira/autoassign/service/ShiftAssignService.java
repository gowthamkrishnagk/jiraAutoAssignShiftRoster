package com.jira.autoassign.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.jira.autoassign.client.JiraClient;
import com.jira.autoassign.config.JiraProperties;
import com.jira.autoassign.entity.AssignmentLog;
import com.jira.autoassign.entity.ShiftRoster;
import com.jira.autoassign.repository.AssignmentLogRepository;
import com.jira.autoassign.repository.ShiftRosterRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Core shift-based assignment logic.
 *
 * Every scheduler tick:
 *  1. Find who is currently ON shift.
 *  2. Assign only currently-unassigned Jira tickets to them (round-robin).
 *     Already-assigned tickets are never touched.
 */
@Service
public class ShiftAssignService {

    private static final Logger log = LoggerFactory.getLogger(ShiftAssignService.class);

    private final JiraClient jiraClient;
    private final ShiftRosterRepository repository;
    private final AssignmentLogRepository logRepository;
    private final JiraProperties props;

    // In-memory round-robin index — resets on restart, which is fine.
    private int rrIndex = 0;

    public ShiftAssignService(JiraClient jiraClient, ShiftRosterRepository repository,
                              AssignmentLogRepository logRepository, JiraProperties props) {
        this.jiraClient    = jiraClient;
        this.repository    = repository;
        this.logRepository = logRepository;
        this.props         = props;
    }

    // -----------------------------------------------------------------------
    // Called by scheduler every minute
    // -----------------------------------------------------------------------

    public void runShiftAssignment() {
        log.info("=== Shift Assignment Run{} ===", props.isDryRun() ? " [DRY-RUN]" : "");

        LocalDate today     = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);
        LocalTime now       = LocalTime.now();

        // --- Step 1: who is on shift right now? ---
        List<ShiftRoster> activeShifts = repository.findActiveShifts(today, yesterday, now);
        List<String> activeEmails = activeShifts.stream()
            .map(ShiftRoster::getEmail).distinct().collect(Collectors.toList());

        log.info("Active shift assignees: {}", activeEmails.isEmpty() ? "NONE" : activeEmails);

        // --- Step 2: assign unassigned tickets to active shift people ---
        if (activeEmails.isEmpty()) {
            log.info("No active shift right now — skipping assignment.");
            return;
        }

        // Resolve emails → accountIds (skip failures gracefully)
        List<String> accountIds   = new ArrayList<>();
        List<String> resolvedEmails = new ArrayList<>();
        for (String email : activeEmails) {
            try {
                accountIds.add(jiraClient.getAccountId(email));
                resolvedEmails.add(email);
            } catch (Exception e) {
                log.warn("  Could not resolve Jira account for {}: {}", email, e.getMessage());
            }
        }

        if (accountIds.isEmpty()) {
            log.warn("Could not resolve any active shift accounts — skipping assignment.");
            return;
        }

        List<JsonNode> unassigned = jiraClient.getTickets().stream()
            .filter(t -> t.get("fields").get("assignee").isNull())
            .collect(Collectors.toList());

        log.info("Unassigned tickets to assign: {}", unassigned.size());

        int assigned = 0, failed = 0;
        for (JsonNode ticket : unassigned) {
            String issueKey  = ticket.get("key").asText();
            String summary   = ticket.get("fields").path("summary").asText("");
            int idx          = rrIndex % accountIds.size();
            String accountId = accountIds.get(idx);
            String email     = resolvedEmails.get(idx);

            if (props.isDryRun()) {
                log.info("  [DRY-RUN] Would assign [{}] -> {}", issueKey, email);
                assigned++;
            } else {
                log.info("  [{}] -> {}", issueKey, email);
                if (jiraClient.assignTicket(issueKey, accountId)) {
                    assigned++;
                    logRepository.save(AssignmentLog.ofAssign(issueKey, summary, email));
                } else {
                    failed++;
                }
            }
            rrIndex++;
            jiraClient.pauseBetweenAssignments();
        }

        log.info("Done — assigned: {}, failed: {}", assigned, failed);

        // --- Step 2b: escalated + unassigned → reassign to last historical assignee ---
        List<JsonNode> escalatedUnassigned = jiraClient.getEscalatedUnassignedTickets().stream()
            .filter(t -> t.get("fields").get("assignee").isNull())
            .collect(Collectors.toList());

        log.info("Escalated unassigned tickets to restore to last owner: {}", escalatedUnassigned.size());

        for (JsonNode ticket : escalatedUnassigned) {
            String issueKey     = ticket.get("key").asText();
            String summary      = ticket.get("fields").path("summary").asText("");
            String lastAccId    = jiraClient.getLastAssigneeAccountId(issueKey);

            if (lastAccId == null) {
                log.info("  [{}] No assignee history found — skipping", issueKey);
                continue;
            }

            String lastEmail = jiraClient.getUserEmail(lastAccId);

            if (props.isDryRun()) {
                log.info("  [DRY-RUN] Would restore escalated [{}] -> {}", issueKey, lastEmail);
            } else {
                log.info("  Restore escalated [{}] -> {}", issueKey, lastEmail);
                if (jiraClient.assignTicket(issueKey, lastAccId)) {
                    logRepository.save(AssignmentLog.ofAssign(issueKey, summary, lastEmail));
                }
            }
            jiraClient.pauseBetweenAssignments();
        }

        // --- Step 3: reassign tickets from off-shift people equally to active shift people ---
        List<String> allRosterEmails = repository.findAllEmailsInRange(today, yesterday);
        List<String> offShiftEmails  = allRosterEmails.stream()
            .filter(e -> !activeEmails.contains(e))
            .collect(Collectors.toList());

        log.info("Off-shift sweep — people to check: {}", offShiftEmails.isEmpty() ? "NONE" : offShiftEmails);

        for (String offEmail : offShiftEmails) {
            try {
                String offAccountId = jiraClient.getAccountId(offEmail);
                List<JsonNode> theirTickets = jiraClient.getTicketsAssignedTo(offAccountId);

                if (theirTickets.isEmpty()) continue;
                log.info("  {} has {} ticket(s) to reassign", offEmail, theirTickets.size());

                for (JsonNode ticket : theirTickets) {
                    String issueKey = ticket.get("key").asText();
                    String summary  = ticket.get("fields").path("summary").asText("");
                    int    idx      = rrIndex % accountIds.size();
                    String newAccId = accountIds.get(idx);
                    String newEmail = resolvedEmails.get(idx);

                    if (props.isDryRun()) {
                        log.info("  [DRY-RUN] Would reassign [{}] {} -> {}", issueKey, offEmail, newEmail);
                    } else {
                        log.info("  Reassign [{}] {} -> {}", issueKey, offEmail, newEmail);
                        if (jiraClient.assignTicket(issueKey, newAccId)) {
                            logRepository.save(AssignmentLog.ofAssign(issueKey, summary, newEmail));
                        }
                    }
                    rrIndex++;
                    jiraClient.pauseBetweenAssignments();
                }
            } catch (Exception e) {
                log.warn("  Could not sweep off-shift {}: {}", offEmail, e.getMessage());
            }
        }
    }

    // -----------------------------------------------------------------------
    // Used by REST endpoints
    // -----------------------------------------------------------------------

    public void clearActivityLog() {
        long count = logRepository.count();
        logRepository.deleteAll();
        log.info("Activity log cleared — {} entries removed.", count);
    }

    public List<ShiftRoster> getActiveShifts() {
        LocalDate today = LocalDate.now();
        return repository.findActiveShifts(today, today.minusDays(1), LocalTime.now());
    }

    public List<ShiftRoster> getCurrentMonthSchedule() {
        LocalDate now = LocalDate.now();
        return repository.findByShiftDateBetweenOrderByShiftDateAscShiftStartAsc(
            now.withDayOfMonth(1),
            now.withDayOfMonth(now.lengthOfMonth())
        );
    }
}
