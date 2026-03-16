package com.jira.autoassign.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.jira.autoassign.client.JiraClient;
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
 *  1. Find who is currently ON shift → assign new unassigned Jira tickets to them (round-robin).
 *  2. Find who is in today's roster but OFF shift → unassign any tickets they hold
 *     so the next active person can pick them up.
 */
@Service
public class ShiftAssignService {

    private static final Logger log = LoggerFactory.getLogger(ShiftAssignService.class);

    private final JiraClient jiraClient;
    private final ShiftRosterRepository repository;
    private final AssignmentLogRepository logRepository;

    // In-memory round-robin index — resets on restart, which is fine.
    private int rrIndex = 0;

    public ShiftAssignService(JiraClient jiraClient, ShiftRosterRepository repository,
                              AssignmentLogRepository logRepository) {
        this.jiraClient = jiraClient;
        this.repository = repository;
        this.logRepository = logRepository;
    }

    // -----------------------------------------------------------------------
    // Called by scheduler every minute
    // -----------------------------------------------------------------------

    public void runShiftAssignment() {
        log.info("=== Shift Assignment Run ===");

        LocalDate today     = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);
        LocalTime now       = LocalTime.now();

        // --- Step 1: who is on shift right now? ---
        List<ShiftRoster> activeShifts = repository.findActiveShifts(today, yesterday, now);
        List<String> activeEmails = activeShifts.stream()
            .map(ShiftRoster::getEmail).distinct().collect(Collectors.toList());

        log.info("Active shift assignees: {}", activeEmails.isEmpty() ? "NONE" : activeEmails);

        // --- Step 2: unassign tickets from off-shift people ---
        List<String> rosterEmails = repository.findAllEmailsInRange(today, yesterday);
        List<String> offShift = rosterEmails.stream()
            .filter(e -> !activeEmails.contains(e))
            .collect(Collectors.toList());

        if (!offShift.isEmpty()) {
            log.info("Off-shift roster members (checking for tickets to unassign): {}", offShift);
            for (String email : offShift) {
                try {
                    String accountId = jiraClient.getAccountId(email);
                    List<JsonNode> held = jiraClient.getTicketsAssignedTo(accountId);
                    if (!held.isEmpty()) {
                        log.info("  Unassigning {} ticket(s) from {}", held.size(), email);
                        for (JsonNode ticket : held) {
                            String key     = ticket.get("key").asText();
                            String summary = ticket.get("fields").path("summary").asText("");
                            if (jiraClient.unassignTicket(key)) {
                                log.info("    Unassigned: {}", key);
                                logRepository.save(AssignmentLog.ofUnassign(key, summary, email));
                            }
                            jiraClient.pauseBetweenAssignments();
                        }
                    }
                } catch (Exception e) {
                    log.warn("  Could not process off-shift user {}: {}", email, e.getMessage());
                }
            }
        }

        // --- Step 3: assign unassigned tickets to active shift people ---
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

            log.info("  [{}] -> {}", issueKey, email);
            if (jiraClient.assignTicket(issueKey, accountId)) {
                assigned++;
                logRepository.save(AssignmentLog.ofAssign(issueKey, summary, email));
            } else {
                failed++;
            }
            rrIndex++;
            jiraClient.pauseBetweenAssignments();
        }

        log.info("Done — assigned: {}, failed: {}", assigned, failed);
    }

    // -----------------------------------------------------------------------
    // Used by REST endpoints
    // -----------------------------------------------------------------------

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
