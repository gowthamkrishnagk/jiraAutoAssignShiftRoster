package com.jira.autoassign.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.jira.autoassign.config.JiraProperties;
import com.jira.autoassign.service.JiraConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.*;

@Component
public class JiraClient {

    private static final Logger log = LoggerFactory.getLogger(JiraClient.class);

    private final RestTemplate restTemplate;
    private final JiraProperties props;
    private final JiraConfigService configService;

    public JiraClient(RestTemplate restTemplate, JiraProperties props, JiraConfigService configService) {
        this.restTemplate  = restTemplate;
        this.props         = props;
        this.configService = configService;
    }

    // -----------------------------------------------------------------------
    // Auth
    // -----------------------------------------------------------------------

    private HttpHeaders authHeaders() {
        String credentials = configService.getEmail() + ":" + configService.getApiToken();
        String encoded = Base64.getEncoder().encodeToString(credentials.getBytes());
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Basic " + encoded);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Accept", MediaType.APPLICATION_JSON_VALUE);
        return headers;
    }

    private String baseUrl() { return configService.getUrl(); }

    // -----------------------------------------------------------------------
    // User lookup
    // -----------------------------------------------------------------------

    public String getAccountId(String email) {
        String url = UriComponentsBuilder
            .fromHttpUrl(baseUrl() + "/rest/api/3/user/search")
            .queryParam("query", email)
            .toUriString();

        ResponseEntity<JsonNode> response = restTemplate.exchange(
            url, HttpMethod.GET, new HttpEntity<>(authHeaders()), JsonNode.class);

        JsonNode users = response.getBody();
        if (users == null || !users.isArray() || users.isEmpty()) {
            throw new RuntimeException("No Jira user found for email: " + email);
        }
        return users.get(0).get("accountId").asText();
    }

    // -----------------------------------------------------------------------
    // Ticket queries
    // -----------------------------------------------------------------------

    /** Returns unassigned tickets matching the configured JQL. */
    public List<JsonNode> getTickets() {
        String jql = buildUnassignedJql();
        log.debug("Fetching unassigned tickets: {}", jql);
        return searchTickets(jql);
    }

    /** Returns tickets currently assigned to the given accountId, matching base filters. */
    public List<JsonNode> getTicketsAssignedTo(String accountId) {
        String jql = buildAssignedJql(accountId);
        log.debug("Fetching tickets for accountId {}: {}", accountId, jql);
        return searchTickets(jql);
    }

    private List<JsonNode> searchTickets(String jql) {
        URI uri = UriComponentsBuilder
            .fromHttpUrl(baseUrl() + "/rest/api/3/search/jql")
            .queryParam("jql",        "{jql}")
            .queryParam("maxResults", 100)
            .queryParam("fields",     "summary,assignee,status,issuetype,labels")
            .build()
            .expand(jql)
            .encode()
            .toUri();

        ResponseEntity<JsonNode> response = restTemplate.exchange(
            uri, HttpMethod.GET, new HttpEntity<>(authHeaders()), JsonNode.class);

        List<JsonNode> tickets = new ArrayList<>();
        if (response.getBody() != null && response.getBody().has("issues")) {
            response.getBody().get("issues").forEach(tickets::add);
        }
        return tickets;
    }

    private String buildUnassignedJql() {
        if (hasCustomJql()) return props.getCustomJql();

        List<String> conds = new ArrayList<>();
        conds.add("project = " + props.getProjectKey());
        if (props.isOnlyUnassigned()) conds.add("assignee = EMPTY");
        addStatusFilter(conds);
        addTypeFilter(conds);
        addLabelFilter(conds);
        return String.join(" AND ", conds) + " ORDER BY created ASC";
    }

    /**
     * Builds JQL for tickets assigned to a specific accountId.
     * Strips the "Assignee in (EMPTY)" clause from the custom JQL and
     * replaces it with "assignee = <accountId>".
     */
    private String buildAssignedJql(String accountId) {
        String base;
        if (hasCustomJql()) {
            // Strip unassigned filter + ORDER BY, then reattach ORDER BY at end
            base = props.getCustomJql()
                .replaceAll("(?i)\\s+AND\\s+Assignee\\s+in\\s*\\(\\s*EMPTY\\s*\\)", "")
                .replaceAll("(?i)Assignee\\s+in\\s*\\(\\s*EMPTY\\s*\\)\\s+AND\\s+", "")
                .replaceAll("(?i)Assignee\\s+in\\s*\\(\\s*EMPTY\\s*\\)", "")
                .replaceAll("(?i)\\s+ORDER\\s+BY.*$", "");
        } else {
            List<String> conds = new ArrayList<>();
            conds.add("project = " + props.getProjectKey());
            addStatusFilter(conds);
            addTypeFilter(conds);
            addLabelFilter(conds);
            base = String.join(" AND ", conds);
        }
        return base + " AND assignee = \"" + accountId + "\" ORDER BY created ASC";
    }

    private boolean hasCustomJql() {
        return props.getCustomJql() != null && !props.getCustomJql().isBlank();
    }

    private void addStatusFilter(List<String> conds) {
        if (props.getTargetStatuses() != null && !props.getTargetStatuses().isEmpty()) {
            String list = String.join(", ",
                props.getTargetStatuses().stream().map(s -> "\"" + s + "\"").toList());
            conds.add("status in (" + list + ")");
        }
    }

    private void addTypeFilter(List<String> conds) {
        if (props.getTargetIssueTypes() != null && !props.getTargetIssueTypes().isEmpty()) {
            String list = String.join(", ",
                props.getTargetIssueTypes().stream().map(t -> "\"" + t + "\"").toList());
            conds.add("issuetype in (" + list + ")");
        }
    }

    private void addLabelFilter(List<String> conds) {
        if (props.getTargetLabels() != null && !props.getTargetLabels().isEmpty()) {
            String list = String.join(", ",
                props.getTargetLabels().stream().map(l -> "\"" + l + "\"").toList());
            conds.add("labels in (" + list + ")");
        }
    }

    // -----------------------------------------------------------------------
    // Team-scoped ticket queries — caller supplies the JQL directly
    // -----------------------------------------------------------------------

    /** Returns unassigned tickets matching the given JQL (used for per-team assignment). */
    public List<JsonNode> getTicketsByJql(String jql) {
        log.debug("Fetching unassigned tickets by JQL: {}", jql);
        return searchTickets(jql);
    }

    /**
     * Returns tickets assigned to accountId, filtered by the team's base JQL.
     * Strips the "Assignee in (EMPTY)" clause and appends the accountId filter.
     */
    public List<JsonNode> getTicketsAssignedToByJql(String accountId, String baseJql) {
        String base = baseJql
            .replaceAll("(?i)\\s+AND\\s+Assignee\\s+in\\s*\\(\\s*EMPTY\\s*\\)", "")
            .replaceAll("(?i)Assignee\\s+in\\s*\\(\\s*EMPTY\\s*\\)\\s+AND\\s+", "")
            .replaceAll("(?i)Assignee\\s+in\\s*\\(\\s*EMPTY\\s*\\)", "")
            .replaceAll("(?i)\\s+ORDER\\s+BY.*$", "");
        String jql = base + " AND assignee = \"" + accountId + "\" ORDER BY created ASC";
        log.debug("Fetching tickets assigned to {}: {}", accountId, jql);
        return searchTickets(jql);
    }

    /**
     * Returns escalated-unassigned tickets for a team — same as baseJql but
     * swaps "Escalation Path[Dropdown] is EMPTY" → "is not EMPTY".
     */
    public List<JsonNode> getEscalatedUnassignedByJql(String baseJql) {
        String jql = baseJql.replaceAll(
            "(?i)\"Escalation Path\\[Dropdown\\]\"\\s+is\\s+EMPTY",
            "\"Escalation Path[Dropdown]\" is not EMPTY");
        log.debug("Fetching escalated unassigned by JQL: {}", jql);
        return searchTickets(jql);
    }

    /**
     * Returns escalated-unassigned tickets whose escalation path is one of the given values.
     * Used to restore tickets on specific escalation paths to their last historical assignee.
     */
    public List<JsonNode> getEscalatedByPathsAndJql(String baseJql, List<String> escalationPaths) {
        String inList = escalationPaths.stream()
            .map(p -> "\"" + p.replace("\"", "\\\"") + "\"")
            .collect(java.util.stream.Collectors.joining(", "));
        String jql = baseJql.replaceAll(
            "(?i)\"Escalation Path\\[Dropdown\\]\"\\s+is\\s+EMPTY",
            "\"Escalation Path[Dropdown]\" in (" + inList + ")");
        log.debug("Fetching escalated (paths in [{}]) unassigned by JQL: {}", inList, jql);
        return searchTickets(jql);
    }

    /**
     * Returns escalated-unassigned tickets whose escalation path is NOT one of the given values.
     * These tickets are assigned round-robin to the active shift instead of restored.
     */
    public List<JsonNode> getEscalatedNotByPathsAndJql(String baseJql, List<String> escalationPaths) {
        String inList = escalationPaths.stream()
            .map(p -> "\"" + p.replace("\"", "\\\"") + "\"")
            .collect(java.util.stream.Collectors.joining(", "));
        String jql = baseJql.replaceAll(
            "(?i)\"Escalation Path\\[Dropdown\\]\"\\s+is\\s+EMPTY",
            "\"Escalation Path[Dropdown]\" is not EMPTY AND \"Escalation Path[Dropdown]\" not in (" + inList + ")");
        log.debug("Fetching escalated (paths not in [{}]) unassigned by JQL: {}", inList, jql);
        return searchTickets(jql);
    }

    // -----------------------------------------------------------------------

    /** Returns unassigned tickets where Escalation Path is NOT empty (reassign to last owner). */
    public List<JsonNode> getEscalatedUnassignedTickets() {
        String jql = buildEscalatedUnassignedJql();
        log.debug("Fetching escalated unassigned tickets: {}", jql);
        return searchTickets(jql);
    }

    private String buildEscalatedUnassignedJql() {
        if (hasCustomJql()) {
            // Swap "Escalation Path[Dropdown]" is EMPTY → is not EMPTY
            return props.getCustomJql()
                .replaceAll("(?i)\"Escalation Path\\[Dropdown\\]\"\\s+is\\s+EMPTY",
                            "\"Escalation Path[Dropdown]\" is not EMPTY");
        }
        List<String> conds = new ArrayList<>();
        conds.add("project = " + props.getProjectKey());
        conds.add("assignee = EMPTY");
        addStatusFilter(conds);
        addTypeFilter(conds);
        addLabelFilter(conds);
        conds.add("\"Escalation Path[Dropdown]\" is not EMPTY");
        return String.join(" AND ", conds) + " ORDER BY created ASC";
    }

    /**
     * Fetches the changelog for a ticket and returns the accountId of the most
     * recent person it was assigned TO (i.e. last non-null assignee in history).
     * Returns null if no assignee history exists.
     */
    public String getLastAssigneeAccountId(String issueKey) {
        String url = baseUrl() + "/rest/api/3/issue/" + issueKey + "/changelog";
        try {
            ResponseEntity<JsonNode> response = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(authHeaders()), JsonNode.class);

            JsonNode body = response.getBody();
            if (body == null || !body.has("values")) return null;

            List<JsonNode> entries = new ArrayList<>();
            body.get("values").forEach(entries::add);
            Collections.reverse(entries); // most recent first

            for (JsonNode entry : entries) {
                if (!entry.has("items")) continue;
                for (JsonNode item : entry.get("items")) {
                    if ("assignee".equals(item.path("field").asText())) {
                        String to = item.path("to").asText(null);
                        if (to != null && !to.isBlank()) return to;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Could not fetch changelog for {}: {}", issueKey, e.getMessage());
        }
        return null;
    }

    /** Reverse-lookup: accountId → email address. Returns accountId as fallback. */
    public String getUserEmail(String accountId) {
        String url = UriComponentsBuilder
            .fromHttpUrl(baseUrl() + "/rest/api/3/user")
            .queryParam("accountId", accountId)
            .toUriString();
        try {
            ResponseEntity<JsonNode> response = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(authHeaders()), JsonNode.class);
            JsonNode user = response.getBody();
            if (user != null) return user.path("emailAddress").asText(accountId);
        } catch (Exception e) {
            log.warn("Could not fetch email for accountId {}: {}", accountId, e.getMessage());
        }
        return accountId;
    }

    // -----------------------------------------------------------------------
    // Ticket mutations
    // -----------------------------------------------------------------------

    /** Assigns a ticket to the given accountId. Retries on 429 rate limit. */
    public boolean assignTicket(String issueKey, String accountId) {
        String url = baseUrl() + "/rest/api/3/issue/" + issueKey + "/assignee";
        HttpEntity<Map<String, String>> req = new HttpEntity<>(Map.of("accountId", accountId), authHeaders());

        int[] delays = {5000, 15000, 30000};
        for (int attempt = 0; attempt <= delays.length; attempt++) {
            try {
                ResponseEntity<Void> resp = restTemplate.exchange(url, HttpMethod.PUT, req, Void.class);
                return resp.getStatusCode() == HttpStatus.NO_CONTENT;
            } catch (org.springframework.web.client.HttpClientErrorException e) {
                if (e.getStatusCode().value() == 429 && attempt < delays.length) {
                    log.warn("Rate limited. Retrying {}/{} in {}ms...", attempt + 1, delays.length, delays[attempt]);
                    sleep(delays[attempt]);
                } else {
                    log.error("Failed to assign {}: {}", issueKey, e.getMessage());
                    return false;
                }
            }
        }
        return false;
    }

    /** Removes the assignee from a ticket (sets to unassigned). */
    public boolean unassignTicket(String issueKey) {
        String url = baseUrl() + "/rest/api/3/issue/" + issueKey + "/assignee";
        // HashMap allows null values; Map.of() does not
        Map<String, Object> body = new HashMap<>();
        body.put("accountId", null);
        HttpEntity<Map<String, Object>> req = new HttpEntity<>(body, authHeaders());
        try {
            ResponseEntity<Void> resp = restTemplate.exchange(url, HttpMethod.PUT, req, Void.class);
            return resp.getStatusCode() == HttpStatus.NO_CONTENT;
        } catch (Exception e) {
            log.error("Failed to unassign {}: {}", issueKey, e.getMessage());
            return false;
        }
    }

    /**
     * Transitions a ticket to the target status name (e.g. "In Progress").
     * Fetches available transitions and picks the one whose name or destination
     * status name matches. No-op if the transition is not available.
     */
    public void transitionTicket(String issueKey, String targetStatus) {
        String url = baseUrl() + "/rest/api/3/issue/" + issueKey + "/transitions";
        try {
            ResponseEntity<JsonNode> response = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(authHeaders()), JsonNode.class);

            JsonNode body = response.getBody();
            if (body == null || !body.has("transitions")) return;

            String transitionId = null;
            for (JsonNode t : body.get("transitions")) {
                String name   = t.path("name").asText("");
                String toName = t.path("to").path("name").asText("");
                if (targetStatus.equalsIgnoreCase(name) || targetStatus.equalsIgnoreCase(toName)) {
                    transitionId = t.path("id").asText();
                    break;
                }
            }

            if (transitionId == null) {
                log.warn("[{}] No transition to '{}' available", issueKey, targetStatus);
                return;
            }

            Map<String, Object> payload = Map.of("transition", Map.of("id", transitionId));
            restTemplate.exchange(url, HttpMethod.POST,
                new HttpEntity<>(payload, authHeaders()), Void.class);
            log.info("[{}] Transitioned to '{}'", issueKey, targetStatus);

        } catch (Exception e) {
            log.warn("[{}] Could not transition to '{}': {}", issueKey, targetStatus, e.getMessage());
        }
    }

    /** 1-second pause between assignments to avoid Jira rate limits (429). */
    public void pauseBetweenAssignments() {
        sleep(1000);
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
