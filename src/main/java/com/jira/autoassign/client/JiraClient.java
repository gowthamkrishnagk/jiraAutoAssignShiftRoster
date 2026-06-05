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
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
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

    /** Cached accountId of the API-token owner — i.e. this app's own Jira identity. */
    private volatile String myAccountId = null;

    /**
     * Breach-owner attribution cache, keyed by "issueKey@breachEpochMs".
     * A ticket's changelog up to a *past* breach instant never changes, so this is
     * safe to cache for the app lifetime. Value is the accountId, or "" meaning
     * "no assignment history at/before the breach" (so it isn't re-queried).
     */
    private final java.util.concurrent.ConcurrentHashMap<String, String> breachOwnerCache
        = new java.util.concurrent.ConcurrentHashMap<>();

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

    /**
     * Returns the accountId of the API-token owner — this app's own Jira identity.
     * Lets callers tell the app's own assignments apart from a human's manual
     * reassignment. Cached for the lifetime of the app; null if it can't be resolved.
     */
    public String getMyAccountId() {
        if (myAccountId != null) return myAccountId;
        try {
            ResponseEntity<JsonNode> resp = restTemplate.exchange(
                baseUrl() + "/rest/api/3/myself",
                HttpMethod.GET, new HttpEntity<>(authHeaders()), JsonNode.class);
            JsonNode body = resp.getBody();
            if (body != null) {
                String id = body.path("accountId").asText(null);
                if (id != null && !id.isBlank()) {
                    myAccountId = id;
                    log.info("[Jira] App identity accountId: {}", id);
                    return myAccountId;
                }
            }
        } catch (Exception e) {
            log.warn("[Jira] Could not resolve app's own accountId via /myself: {}", e.getMessage());
        }
        return null;
    }

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

    /**
     * Fetches ALL matching tickets using Jira pagination.
     * Jira Cloud caps each page at 100; this loops until every page is fetched.
     */
    private List<JsonNode> searchTicketsWithFields(String jql, String fields) {
        return searchTicketsWithFields(jql, fields, null);
    }

    /**
     * Same as the two-arg version, but optionally asks Jira to expand extra data
     * inline (e.g. "changelog") so callers avoid a follow-up per-issue request.
     */
    private List<JsonNode> searchTicketsWithFields(String jql, String fields, String expand) {
        List<JsonNode> all = new ArrayList<>();
        final int PAGE_SIZE = 100;
        int startAt = 0;

        while (true) {
            UriComponentsBuilder builder = UriComponentsBuilder
                .fromHttpUrl(baseUrl() + "/rest/api/3/search/jql")
                .queryParam("jql",        "{jql}")
                .queryParam("maxResults", PAGE_SIZE)
                .queryParam("startAt",    startAt)
                .queryParam("fields",     fields);
            if (expand != null && !expand.isBlank()) builder.queryParam("expand", expand);
            URI uri = builder.build().expand(jql).encode().toUri();

            ResponseEntity<JsonNode> response = restTemplate.exchange(
                uri, HttpMethod.GET, new HttpEntity<>(authHeaders()), JsonNode.class);

            JsonNode body = response.getBody();
            if (body == null || !body.has("issues")) break;

            int fetched = 0;
            for (JsonNode issue : body.get("issues")) {
                all.add(issue);
                fetched++;
            }

            int total = body.path("total").asInt(0);
            startAt += fetched;
            log.debug("[JQL] Fetched {}/{} tickets (startAt={})", startAt, total, startAt - fetched);

            // Stop if we've fetched everything or got an empty page
            if (fetched == 0 || startAt >= total) break;
        }

        return all;
    }

    /**
     * Returns all tickets with an assignee that match the team's base JQL,
     * fetching the given SLA field alongside the standard fields.
     * Used by the SLA Tracker — strips "Assignee in (EMPTY)" and flips to
     * "assignee is not EMPTY" so we see every person's current load.
     */
    /**
     * Fetches OPEN breached tickets — uses the team's original status filter
     * (In Progress, Waiting for support, Escalated, …) with NO date limit.
     * An open ticket breached 60 days ago is still breached today.
     */
    public List<JsonNode> getOpenSlaTickets(String baseJql, String slaFieldId) {
        String base = baseJql
            // Remove assignee-empty filter — we want ASSIGNED tickets
            .replaceAll("(?i)\\s+AND\\s+Assignee\\s+in\\s*\\(\\s*EMPTY\\s*\\)", "")
            .replaceAll("(?i)Assignee\\s+in\\s*\\(\\s*EMPTY\\s*\\)\\s+AND\\s+", "")
            .replaceAll("(?i)Assignee\\s+in\\s*\\(\\s*EMPTY\\s*\\)", "")
            // Remove escalation-path filter — we want ALL tickets regardless of escalation
            .replaceAll("(?i)\\s+AND\\s+\"Escalation Path\\[Dropdown\\]\"\\s+is\\s+EMPTY", "")
            .replaceAll("(?i)\"Escalation Path\\[Dropdown\\]\"\\s+is\\s+EMPTY\\s+AND\\s+", "")
            .replaceAll("(?i)\"Escalation Path\\[Dropdown\\]\"\\s+is\\s+EMPTY", "")
            .replaceAll("(?i)\\s+ORDER\\s+BY.*$", "");

        // Let Jira detect the breach — cf[X] = breached() is far more accurate than
        // parsing completedCycles.breached in Java (Jira sometimes sets that flag wrong).
        String cfNum    = (slaFieldId != null) ? slaFieldId.replace("customfield_", "") : "";
        String slaBreachFilter = cfNum.isEmpty() ? "" : " AND cf[" + cfNum + "] = breached()";

        String jql = base + slaBreachFilter + " ORDER BY created DESC";
        log.info("[SLA] Open tickets query: {}", jql);

        String sevKey = discoverSeverityFieldKey();
        String fields = "summary,assignee,status," + slaFieldId
                        + (sevKey != null ? "," + sevKey : "");
        // expand=changelog returns each issue's history inline, so breach
        // attribution (resolveBreachOwner) can skip the per-ticket changelog call.
        return searchTicketsWithFields(jql, fields, "changelog");
    }

    /**
     * Fetches RESOLVED/CLOSED/CANCELLED tickets that breached SLA on a specific date.
     *
     * @param date  "YYYY-MM-DD" for a specific day, or null/"today" for today.
     *              Uses Jira's startOfDay()/endOfDay() for today so the project timezone
     *              is respected. For other dates, uses an inclusive day range filter.
     */
    public List<JsonNode> getResolvedSlaTickets(String baseJql, String slaFieldId, String date) {
        String base = stripForResolved(baseJql);

        // Build the date filter — scope to one calendar day only.
        // Use `updated` so CLOSED tickets without a resolutiondate are included.
        String dateFilter;
        boolean isToday = (date == null || date.isBlank() || date.equalsIgnoreCase("today"));
        if (isToday) {
            // Jira built-in functions respect the project/user timezone
            dateFilter = " AND updated >= startOfDay() AND updated <= endOfDay()";
        } else {
            try {
                LocalDate d    = LocalDate.parse(date);          // expects "YYYY-MM-DD"
                LocalDate next = d.plusDays(1);
                dateFilter = " AND updated >= \"" + d + "\" AND updated < \"" + next + "\"";
            } catch (DateTimeParseException e) {
                log.warn("[SLA] Invalid date '{}', falling back to today", date);
                dateFilter = " AND updated >= startOfDay() AND updated <= endOfDay()";
            }
        }

        // cf[X] = breached() — Jira is the source of truth for breach detection.
        String cfNum           = (slaFieldId != null) ? slaFieldId.replace("customfield_", "") : "";
        String slaBreachFilter = cfNum.isEmpty() ? "" : " AND cf[" + cfNum + "] = breached()";

        String jql = base
            + " AND status in (\"Resolved\",\"Closed\",\"Cancelled\")"
            + slaBreachFilter
            + dateFilter
            + " ORDER BY updated DESC";
        log.info("[SLA] Resolved tickets query (date={}): {}", isToday ? "today" : date, jql);

        String sevKey = discoverSeverityFieldKey();
        String fields = "summary,assignee,status," + slaFieldId
                        + (sevKey != null ? "," + sevKey : "");
        return searchTicketsWithFields(jql, fields);
    }

    /**
     * Fetches ALL resolved/closed/cancelled SLA breaches whose `updated` falls in
     * the inclusive day range [from, to] — in ONE search instead of one query per
     * day. Includes the `updated` field so the caller can bucket each ticket to a
     * day. Used by the date-range report download.
     */
    public List<JsonNode> getResolvedSlaTicketsInRange(String baseJql, String slaFieldId,
                                                       LocalDate from, LocalDate to) {
        String base = stripForResolved(baseJql);

        LocalDate next = to.plusDays(1);  // exclusive upper bound
        String dateFilter = " AND updated >= \"" + from + "\" AND updated < \"" + next + "\"";

        String cfNum           = (slaFieldId != null) ? slaFieldId.replace("customfield_", "") : "";
        String slaBreachFilter = cfNum.isEmpty() ? "" : " AND cf[" + cfNum + "] = breached()";

        String jql = base
            + " AND status in (\"Resolved\",\"Closed\",\"Cancelled\")"
            + slaBreachFilter
            + dateFilter
            + " ORDER BY updated DESC";
        log.info("[SLA] Resolved range query {}..{}: {}", from, to, jql);

        String sevKey = discoverSeverityFieldKey();
        String fields = "summary,assignee,status,updated," + slaFieldId
                        + (sevKey != null ? "," + sevKey : "");
        return searchTicketsWithFields(jql, fields);
    }

    /** Strips the unassigned/status/escalation/order-by clauses from a team's base JQL
     *  so resolved-breach queries can re-scope to their own status + date filters. */
    private static String stripForResolved(String baseJql) {
        return baseJql
            .replaceAll("(?i)\\s+AND\\s+Assignee\\s+in\\s*\\(\\s*EMPTY\\s*\\)", "")
            .replaceAll("(?i)Assignee\\s+in\\s*\\(\\s*EMPTY\\s*\\)\\s+AND\\s+", "")
            .replaceAll("(?i)Assignee\\s+in\\s*\\(\\s*EMPTY\\s*\\)", "")
            .replaceAll("(?i)\\s+AND\\s+status\\s+in\\s*\\([^)]+\\)", "")
            .replaceAll("(?i)status\\s+in\\s*\\([^)]+\\)\\s+AND\\s+", "")
            .replaceAll("(?i)\\s+AND\\s+\"Escalation Path\\[Dropdown\\]\"\\s+is\\s+EMPTY", "")
            .replaceAll("(?i)\"Escalation Path\\[Dropdown\\]\"\\s+is\\s+EMPTY\\s+AND\\s+", "")
            .replaceAll("(?i)\"Escalation Path\\[Dropdown\\]\"\\s+is\\s+EMPTY", "")
            .replaceAll("(?i)\\s+ORDER\\s+BY.*$", "");
    }

    /** @deprecated Use getOpenSlaTickets + getResolvedSlaTickets instead */
    public List<JsonNode> getSlaTickets(String baseJql, String slaFieldId, String period) {
        return getOpenSlaTickets(baseJql, slaFieldId);
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
            base = baseJqlStripped(props.getCustomJql());
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
        String jql = baseJqlStripped(baseJql) + " AND assignee = \"" + accountId + "\" ORDER BY created ASC";
        log.debug("Fetching tickets assigned to {}: {}", accountId, jql);
        return searchTickets(jql);
    }

    /**
     * Same as {@link #getTicketsAssignedToByJql} but also fetches the SLA custom field
     * so callers can extract remaining time / breach status per ticket.
     * Falls back to the standard field list if no SLA field is configured.
     */
    public List<JsonNode> getTicketsAssignedToByJqlWithSla(String accountId, String baseJql) {
        String slaFieldId = configService.getSlaFieldId();
        String base = baseJqlStripped(baseJql);
        String jql  = base + " AND assignee = \"" + accountId + "\" ORDER BY created ASC";

        String fields = "summary,assignee,status";
        if (slaFieldId != null && !slaFieldId.isBlank()) fields += "," + slaFieldId;

        log.debug("Fetching tickets (with SLA) assigned to {}: {}", accountId, jql);
        return searchTicketsWithFields(jql, fields);
    }

    /**
     * Extracts the human-readable SLA remaining time from a ticket node.
     * Returns "⚠ Breached" if already breached, empty string if SLA not configured
     * or the field is missing.
     *
     * Reads from: fields.{slaFieldId}.ongoingCycle.remainingTime.friendly
     */
    public String extractSlaRemaining(JsonNode ticket) {
        String slaFieldId = configService.getSlaFieldId();
        if (slaFieldId == null || slaFieldId.isBlank()) return "";

        JsonNode slaNode = ticket.path("fields").path(slaFieldId);
        if (slaNode.isMissingNode() || slaNode.isNull()) return "";

        JsonNode ongoing = slaNode.path("ongoingCycle");
        if (!ongoing.isMissingNode() && !ongoing.isNull()) {
            boolean breached = ongoing.path("breached").asBoolean(false);
            long    millis   = ongoing.path("remainingTime").path("millis").asLong(0);
            if (millis < 0 || breached) return "⚠ Breached";
            String friendly = ongoing.path("remainingTime").path("friendly").asText("").trim();
            return friendly.isEmpty() ? "" : friendly + " remaining";
        }

        // Completed cycle — SLA already closed
        JsonNode completed = slaNode.path("completedCycles");
        if (completed.isArray() && completed.size() > 0) {
            JsonNode last    = completed.get(completed.size() - 1);
            boolean  breached = last.path("breached").asBoolean(false);
            return breached ? "⚠ Breached" : "✓ Met";
        }
        return "";
    }

    /** Shared JQL stripping used by assignee-based queries. */
    private static String baseJqlStripped(String baseJql) {
        return baseJql
            .replaceAll("(?i)\\s+AND\\s+Assignee\\s+in\\s*\\(\\s*EMPTY\\s*\\)", "")
            .replaceAll("(?i)Assignee\\s+in\\s*\\(\\s*EMPTY\\s*\\)\\s+AND\\s+", "")
            .replaceAll("(?i)Assignee\\s+in\\s*\\(\\s*EMPTY\\s*\\)", "")
            .replaceAll("(?i)\\s+ORDER\\s+BY.*$", "");
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
     * Returns the accountId of whoever made the most recent assignee change on the ticket
     * (the changelog author), or null if there is no assignee history or it can't be read.
     * Lets the sweep tell the app's own assignments apart from a human's manual reassignment.
     */
    public String getLastAssigneeChangeAuthor(String issueKey) {
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
                        String author = entry.path("author").path("accountId").asText(null);
                        return (author != null && !author.isBlank()) ? author : null;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Could not fetch changelog author for {}: {}", issueKey, e.getMessage());
        }
        return null;
    }

    /**
     * Resolves the breach owner for one ticket — the accountId assigned at the breach
     * instant. Uses, in order: the attribution cache → the inline changelog from the
     * search response (expand=changelog, only when complete/untruncated) → a per-ticket
     * changelog fetch. The result is cached by issueKey+epoch (immutable history).
     * Pass a ticket node from getOpenSlaTickets so the inline changelog is available.
     */
    public String resolveBreachOwner(JsonNode ticket, long epochMs) {
        String issueKey = ticket.path("key").asText("");
        if (issueKey.isEmpty() || epochMs <= 0) return null;

        String cacheKey = issueKey + "@" + epochMs;
        String cached = breachOwnerCache.get(cacheKey);
        if (cached != null) return cached.isEmpty() ? null : cached;

        String owner;
        JsonNode changelog = ticket.path("changelog");
        JsonNode histories = changelog.path("histories");
        int total = changelog.path("total").asInt(-1);
        // Trust the inline changelog only if it's the complete history (not truncated).
        if (histories.isArray() && total >= 0 && total <= histories.size()) {
            owner = assigneeAtEpochFromHistories(histories, epochMs);
        } else {
            owner = getAssigneeAtEpoch(issueKey, epochMs);  // fetch full changelog
        }

        breachOwnerCache.put(cacheKey, owner == null ? "" : owner);
        return owner;
    }

    /**
     * Returns the accountId of whoever was assigned to the ticket AT the given epoch time.
     * Fetches the per-ticket changelog (with 429 backoff) and returns the last assignment
     * at or before epochMs. Returns null if changelog cannot be fetched or none exists.
     */
    public String getAssigneeAtEpoch(String issueKey, long epochMs) {
        String url = baseUrl() + "/rest/api/3/issue/" + issueKey + "/changelog";
        try {
            ResponseEntity<JsonNode> response = getJsonWithRetry(url);
            JsonNode body = response.getBody();
            if (body == null || !body.has("values")) return null;
            return assigneeAtEpochFromHistories(body.get("values"), epochMs);
        } catch (Exception e) {
            log.warn("[{}] Could not fetch changelog for breach attribution: {}", issueKey, e.getMessage());
            return null;
        }
    }

    /**
     * Reads a changelog history array (each entry: { created, items:[{field,to}] }) and
     * returns the accountId of the last non-null 'assignee' change at/before epochMs.
     * Order-independent: picks the assignee change with the greatest created &lt;= epoch.
     * Unassignment events (to == null) are ignored, matching prior behavior.
     */
    private String assigneeAtEpochFromHistories(JsonNode histories, long epochMs) {
        String result = null;
        long   bestMs = Long.MIN_VALUE;
        for (JsonNode entry : histories) {
            String createdStr = entry.path("created").asText("");
            if (createdStr.isEmpty()) continue;
            long entryMs;
            try {
                entryMs = java.time.OffsetDateTime.parse(createdStr).toInstant().toEpochMilli();
            } catch (Exception ex) { continue; }
            if (entryMs > epochMs) continue;  // change happened after the breach

            for (JsonNode item : entry.path("items")) {
                if ("assignee".equals(item.path("field").asText())) {
                    String toId = item.path("to").asText(null);
                    if (toId != null && !toId.isBlank() && entryMs >= bestMs) {
                        bestMs = entryMs;
                        result = toId;
                    }
                }
            }
        }
        return result;
    }

    /** GET returning JSON with Retry-After-aware backoff on HTTP 429 (rate limit). */
    private ResponseEntity<JsonNode> getJsonWithRetry(String url) {
        final int maxRetries = 3;
        for (int attempt = 0; ; attempt++) {
            try {
                return restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(authHeaders()), JsonNode.class);
            } catch (org.springframework.web.client.HttpClientErrorException e) {
                if (e.getStatusCode().value() == 429 && attempt < maxRetries) {
                    long waitMs = retryAfterMs(e);
                    log.warn("[Jira] 429 rate limited on GET — waiting {}ms (retry {}/{})",
                        waitMs, attempt + 1, maxRetries);
                    sleep(waitMs);
                } else {
                    throw e;
                }
            }
        }
    }

    /** Reads the Retry-After header (seconds) from a 429 response; defaults to 5s. */
    private long retryAfterMs(org.springframework.web.client.HttpClientErrorException e) {
        try {
            String ra = (e.getResponseHeaders() != null)
                ? e.getResponseHeaders().getFirst("Retry-After") : null;
            if (ra != null && ra.matches("\\d+")) return Long.parseLong(ra) * 1000L;
        } catch (Exception ignored) {}
        return 5000L;
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
