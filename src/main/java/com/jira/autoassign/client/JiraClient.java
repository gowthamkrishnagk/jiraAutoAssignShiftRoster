package com.jira.autoassign.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.jira.autoassign.config.JiraProperties;
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

    public JiraClient(RestTemplate restTemplate, JiraProperties props) {
        this.restTemplate = restTemplate;
        this.props = props;
    }

    // -----------------------------------------------------------------------
    // Auth
    // -----------------------------------------------------------------------

    private HttpHeaders authHeaders() {
        String credentials = props.getEmail() + ":" + props.getApiToken();
        String encoded = Base64.getEncoder().encodeToString(credentials.getBytes());
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Basic " + encoded);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Accept", MediaType.APPLICATION_JSON_VALUE);
        return headers;
    }

    // -----------------------------------------------------------------------
    // User lookup
    // -----------------------------------------------------------------------

    public String getAccountId(String email) {
        String url = UriComponentsBuilder
            .fromHttpUrl(props.getUrl() + "/rest/api/3/user/search")
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
            .fromHttpUrl(props.getUrl() + "/rest/api/3/search/jql")
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
    // Ticket mutations
    // -----------------------------------------------------------------------

    /** Assigns a ticket to the given accountId. Retries on 429 rate limit. */
    public boolean assignTicket(String issueKey, String accountId) {
        String url = props.getUrl() + "/rest/api/3/issue/" + issueKey + "/assignee";
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
        String url = props.getUrl() + "/rest/api/3/issue/" + issueKey + "/assignee";
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

    /** 1-second pause between assignments to avoid Jira rate limits (429). */
    public void pauseBetweenAssignments() {
        sleep(1000);
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
