package com.jira.autoassign.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jira.autoassign.client.JiraClient;
import com.jira.autoassign.config.JiraProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Service
public class AssignService {

    private static final Logger log = LoggerFactory.getLogger(AssignService.class);
    private static final String STATE_FILE = "round_robin_state.json";

    private final JiraClient jiraClient;
    private final JiraProperties props;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AssignService(JiraClient jiraClient, JiraProperties props) {
        this.jiraClient = jiraClient;
        this.props = props;
    }

    // ---------- State persistence ----------

    private int loadIndex() {
        File file = new File(STATE_FILE);
        if (!file.exists()) return 0;
        try {
            return objectMapper.readTree(file).get("index").asInt();
        } catch (IOException e) {
            log.warn("Could not read state file, starting from index 0");
            return 0;
        }
    }

    private void saveIndex(int index) {
        try {
            objectMapper.writeValue(new File(STATE_FILE), new IndexState(index));
        } catch (IOException e) {
            log.error("Failed to save state file: {}", e.getMessage());
        }
    }

    record IndexState(int index) {}

    // ---------- Main logic ----------

    public void runAssignment() {
        boolean dryRun = props.isDryRun();
        List<String> assignees = props.getAssignees();

        log.info("=== Jira Auto-Assign Started {} ===", dryRun ? "[DRY RUN]" : "");

        // Resolve each assignee email to a Jira accountId
        List<String> accountIds = new ArrayList<>();
        for (String email : assignees) {
            String accountId = jiraClient.getAccountId(email);
            log.info("Resolved {} -> {}", email, accountId);
            accountIds.add(accountId);
        }

        // Fetch tickets matching the configured filters
        List<JsonNode> tickets = jiraClient.getTickets();
        if (tickets.isEmpty()) {
            log.info("No tickets found matching the criteria.");
            return;
        }

        log.info("Found {} ticket(s) to assign.", tickets.size());

        int currentIndex = loadIndex();
        int assigned = 0;
        int failed = 0;

        for (JsonNode ticket : tickets) {
            String issueKey = ticket.get("key").asText();
            String summary = ticket.get("fields").get("summary").asText();
            String issueType = ticket.get("fields").get("issuetype").get("name").asText();

            // Pick the next assignee in round-robin order
            String assigneeEmail = assignees.get(currentIndex % assignees.size());
            String assigneeId = accountIds.get(currentIndex % accountIds.size());

            log.info("[{}] ({}) {} -> {}", issueKey, issueType, summary, assigneeEmail);

            if (dryRun) {
                log.info("  [DRY RUN] Skipping actual assignment.");
                assigned++;
            } else {
                boolean success = jiraClient.assignTicket(issueKey, assigneeId);
                if (success) {
                    log.info("  [ASSIGNED]");
                    assigned++;
                } else {
                    log.error("  [FAILED] Could not assign {}", issueKey);
                    failed++;
                }
            }

            currentIndex++;
        }

        // Persist updated index only if this was a real run
        if (!dryRun) {
            saveIndex(currentIndex);
        }

        log.info("=== Done: {} assigned, {} failed. Next assignee: {} ===",
                assigned, failed, assignees.get(currentIndex % assignees.size()));
    }
}
