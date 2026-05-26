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
import java.util.Collections;

@Component
public class JiraClient {

    private static final Logger log = LoggerFactory.getLogger(JiraClient.class);

    private final RestTemplate restTemplate;
    private final JiraProperties props;
    private final JiraConfigService configService;

    /** Cached after first successful discovery — avoids repeated /rest/api/3/field calls. */
    private volatile String severityFieldKey = null;

    public JiraClient(RestTemplate restTemplate, JiraProperties props, JiraConfigService configService) {
        this.restTemplate  = restTemplate;
        this.props         = props;
        this.configService = configService;
    }

    /**
     * Returns the Jira field key for the Severity dropdown (e.g. "customfield_10051").
     * Discovers it once via /rest/api/3/field and caches for the lifetime of the app.
     */
    public String discoverSeverityFieldKey() {
        if (severityFieldKey != null) return severityFieldKey;
        try {
            ResponseEntity<JsonNode> resp = restTemplate.exchange(
                baseUrl() + "/rest/api/3/field",
                HttpMethod.GET, new HttpEntity<>(authHeaders()), JsonNode.class);
            if (resp.getBody() != null) {
                for (JsonNode f : resp.getBody()) {
                    String name = f.path("name").asText("").toLowerCase();
                    String id   = f.path("id").asText("");
                    if (name.equals("severity") || name.equals("severity[dropdown]")
                            || id.equalsIgnoreCase("severity")) {
                        severityFieldKey = id;
                        log.info("[SLA] Discovered severity field key: {}", id);
                        return severityFieldKey;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[SLA] Could not discover severity field key: {}", e.getMessage());
        }
        return null;
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
        return searchTicketsWithFields(jql, "summary,assignee,status,issuetype,labels");
    }

    private List<JsonNode> searchTicketsWithFields(String jql, String fields) {
        URI uri = UriComponentsBuilder
            .fromHttpUrl(baseUrl() + "/rest/api/3/search/jql")
            .queryParam("jql",        "{jql}")
            .queryParam("maxResults", 500)
            .queryParam("fields",     fields)
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

    /**
     * Returns all tickets with an assignee that match the team's base JQL,
     * fetching the given SLA field alongside the standard fields.
     * Used by the SLA Tracker — strips "Assignee in (EMPTY)" and flips to
     * "assignee is not EMPTY" so we see every person's current load.
     */
    /**
     * Single unified SLA query — fetches ALL statuses (open, in-progress, waiting,
     * escalated, resolved, closed) filtered only by created date window.
     * Status filter is stripped from the base JQL so no tickets are missed.
     * Returns up to 500 tickets; backend filters to breached-only.
     */
    public List<JsonNode> getSlaTickets(String baseJql, String slaFieldId, String period) {
        String base = baseJql
            // remove assignee empty filter
            .replaceAll("(?i)\\s+AND\\s+Assignee\\s+in\\s*\\(\\s*EMPTY\\s*\\)", "")
            .replaceAll("(?i)Assignee\\s+in\\s*\\(\\s*EMPTY\\s*\\)\\s+AND\\s+", "")
            .replaceAll("(?i)Assignee\\s+in\\s*\\(\\s*EMPTY\\s*\\)", "")
            // remove status filter — we want ALL statuses (open, waiting, resolved, closed…)
            .replaceAll("(?i)\\s+AND\\s+status\\s+in\\s*\\([^)]+\\)", "")
            .replaceAll("(?i)status\\s+in\\s*\\([^)]+\\)\\s+AND\\s+", "")
            .replaceAll("(?i)\\s+ORDER\\s+BY.*$", "");

        // Date filter by created date
        String dateFilter = switch (period == null ? "all" : period) {
            case "weekly"  -> " AND created >= -7d";
            case "monthly" -> " AND created >= -30d";
            default        -> " AND created >= -90d";  // "all" = last 90 days
        };

        String jql = base + " AND assignee is not EMPTY" + dateFilter + " ORDER BY created DESC";
        log.info("[SLA] Unified query (period={}): {}", period, jql);

        String sevKey = discoverSeverityFieldKey();
        String fields = "summary,assignee,status," + slaFieldId
                        + (sevKey != null ? "," + sevKey : "");
        return searchTicketsWithFields(jql, fields);
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

    /**
     * Returns the accountId of whoever was assigned to the ticket AT the given epoch time.
     * Walks the changelog (chronological order) and returns the last assignment
     * that occurred at or before epochMs.
     * Returns null if changelog cannot be fetched or no assignment history exists.
     */
    public String getAssigneeAtEpoch(String issueKey, long epochMs) {
        String url = baseUrl() + "/rest/api/3/issue/" + issueKey + "/changelog";
        try {
            ResponseEntity<JsonNode> response = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(authHeaders()), JsonNode.class);
            JsonNode body = response.getBody();
            if (body == null || !body.has("values")) return null;

            String result = null;
            for (JsonNode entry : body.get("values")) {
                String createdStr = entry.path("created").asText("");
                if (createdStr.isEmpty()) continue;
                long entryMs;
                try {
                    entryMs = java.time.OffsetDateTime.parse(createdStr)
                                  .toInstant().toEpochMilli();
                } catch (Exception ex) { continue; }

                if (entryMs > epochMs) break; // past breach time — stop

                for (JsonNode item : entry.path("items")) {
                    if ("assignee".equals(item.path("field").asText())) {
                        String toId = item.path("to").asText(null);
                        if (toId != null && !toId.isBlank()) result = toId;
                    }
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("[{}] Could not fetch changelog for breach attribution: {}", issueKey, e.getMessage());
            return null;
        }
    }

    /** In-memory cache: accountId → { email, displayName }. Avoids repeated /user calls. */
    private final java.util.concurrent.ConcurrentHashMap<String, Map<String,String>> userInfoCache
        = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Returns { "email": "...", "displayName": "..." } for the given accountId.
     * Result is cached for the lifetime of the app.
     */
    public Map<String, String> getUserInfo(String accountId) {
        return userInfoCache.computeIfAbsent(accountId, id -> {
            String url = UriComponentsBuilder
                .fromHttpUrl(baseUrl() + "/rest/api/3/user")
                .queryParam("accountId", id)
                .toUriString();
            try {
                ResponseEntity<JsonNode> resp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(authHeaders()), JsonNode.class);
                JsonNode user = resp.getBody();
                if (user != null) {
                    Map<String, String> info = new LinkedHashMap<>();
                    info.put("email",       user.path("emailAddress").asText(""));
                    info.put("displayName", user.path("displayName").asText(""));
                    return info;
                }
            } catch (Exception e) {
                log.warn("Could not fetch user info for {}: {}", id, e.getMessage());
            }
            return Map.of("email", "", "displayName", accountId);
        });
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

    // -----------------------------------------------------------------------
    // Severity field options — fetched live from Jira, no hardcoding
    // -----------------------------------------------------------------------

    /**
     * Looks up the "severity" field in Jira and returns its allowed option values
     * in the order Jira defines them (typically most severe first).
     *
     * Steps:
     *  1. GET /rest/api/3/field           — find the field whose name == "Severity"
     *                                        or id == "severity"
     *  2. GET /rest/api/3/field/{id}/context          — get its first context
     *  3. GET /rest/api/3/field/{id}/context/{cid}/option — get the ordered options
     *
     * Returns an empty list if the field cannot be found or options cannot be fetched.
     */
    public List<String> getSeverityOptions() {
        // Step 1 — reuse the cached severity field key
        String fieldId = discoverSeverityFieldKey();

        if (fieldId == null) {
            log.warn("[SLA] Severity field not found in Jira field list");
            return Collections.emptyList();
        }

        // Step 2 — get contexts for the field
        String contextId = null;
        try {
            String ctxUrl = baseUrl() + "/rest/api/3/field/" + fieldId + "/context";
            ResponseEntity<JsonNode> ctxResp = restTemplate.exchange(
                ctxUrl, HttpMethod.GET, new HttpEntity<>(authHeaders()), JsonNode.class);

            JsonNode ctxBody = ctxResp.getBody();
            if (ctxBody != null && ctxBody.has("values") && ctxBody.get("values").size() > 0) {
                contextId = ctxBody.get("values").get(0).path("id").asText();
            }
        } catch (Exception e) {
            log.warn("[SLA] Could not fetch severity field contexts: {}", e.getMessage());
            return Collections.emptyList();
        }

        if (contextId == null) {
            log.warn("[SLA] No context found for severity field '{}'", fieldId);
            return Collections.emptyList();
        }

        // Step 3 — get options for the context
        try {
            String optUrl = baseUrl() + "/rest/api/3/field/" + fieldId
                          + "/context/" + contextId + "/option";
            ResponseEntity<JsonNode> optResp = restTemplate.exchange(
                optUrl, HttpMethod.GET, new HttpEntity<>(authHeaders()), JsonNode.class);

            List<String> values = new ArrayList<>();
            JsonNode optBody = optResp.getBody();
            if (optBody != null && optBody.has("values")) {
                for (JsonNode opt : optBody.get("values")) {
                    if (!opt.path("disabled").asBoolean(false)) {
                        values.add(opt.path("value").asText());
                    }
                }
            }
            log.info("[SLA] Severity options fetched: {}", values);
            return values;

        } catch (Exception e) {
            log.warn("[SLA] Could not fetch severity options: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // -----------------------------------------------------------------------
    // Comment
    // -----------------------------------------------------------------------

    /**
     * Posts a plain-text comment on a Jira ticket.
     * Jira Cloud v3 requires Atlassian Document Format (ADF) — plain text
     * is wrapped in a minimal doc → paragraph → text node.
     */
    public boolean postComment(String issueKey, String commentText) {
        String url = baseUrl() + "/rest/api/3/issue/" + issueKey + "/comment";

        // Build ADF body
        Map<String, Object> textNode = new LinkedHashMap<>();
        textNode.put("type", "text");
        textNode.put("text", commentText);

        Map<String, Object> paragraph = new LinkedHashMap<>();
        paragraph.put("type", "paragraph");
        paragraph.put("content", List.of(textNode));

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("type",    "doc");
        doc.put("version", 1);
        doc.put("content", List.of(paragraph));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("body", doc);

        try {
            ResponseEntity<Void> resp = restTemplate.exchange(
                url, HttpMethod.POST,
                new HttpEntity<>(payload, authHeaders()),
                Void.class);
            return resp.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            log.error("[{}] Failed to post comment: {}", issueKey, e.getMessage());
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
