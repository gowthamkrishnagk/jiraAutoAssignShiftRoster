package com.jira.autoassign.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.jira.autoassign.client.JiraClient;
import com.jira.autoassign.entity.Team;
import com.jira.autoassign.repository.TeamRepository;
import com.jira.autoassign.service.JiraConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * SLA Tracker endpoint.
 *
 * GET /api/sla?team={id}
 *
 * Returns all assigned tickets for the team grouped by assignee,
 * with the Time-to-Resolution SLA field extracted into a structured status object.
 * The SLA field ID is loaded from DB (configured once via Admin tab).
 */
@RestController
@RequestMapping("/api")
public class SlaController {

    private static final Logger log = LoggerFactory.getLogger(SlaController.class);

    private final JiraClient        jiraClient;
    private final TeamRepository    teamRepository;
    private final JiraConfigService configService;

    public SlaController(JiraClient jiraClient,
                         TeamRepository teamRepository,
                         JiraConfigService configService) {
        this.jiraClient    = jiraClient;
        this.teamRepository = teamRepository;
        this.configService  = configService;
    }

    /**
     * Returns the currently saved SLA field ID.
     * GET /api/sla/config
     */
    @GetMapping("/sla/config")
    public ResponseEntity<?> getSlaConfig() {
        Map<String, Object> resp = new java.util.LinkedHashMap<>();
        resp.put("slaFieldId", configService.getSlaFieldId());
        resp.put("jiraUrl",    configService.getUrl() != null ? configService.getUrl() : "");
        return ResponseEntity.ok(resp);
    }

    /**
     * Saves the SLA field ID (one-time Admin setup).
     * POST /api/sla/config
     * Body: { "slaFieldId": "customfield_XXXXX" }
     */
    @PostMapping("/sla/config")
    public ResponseEntity<?> saveSlaConfig(@RequestBody Map<String, String> body) {
        String fieldId = body.getOrDefault("slaFieldId", "").trim();
        if (fieldId.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "slaFieldId is required"));
        configService.saveSlaFieldId(fieldId);
        return ResponseEntity.ok(Map.of("message", "SLA field ID saved", "slaFieldId", fieldId));
    }

    /**
     * Posts a breach-reason comment on a Jira ticket.
     *
     * POST /api/sla/comment
     * Body: { "issueKey": "SAC-1234", "reason": "Aria Escalation" }
     *
     * Comment added to the ticket:
     *   "SLA Breached. Reason: Aria Escalation"
     */
    @PostMapping("/sla/comment")
    public ResponseEntity<?> postBreachComment(@RequestBody Map<String, String> body) {
        String issueKey = body.getOrDefault("issueKey", "").trim();
        String reason   = body.getOrDefault("reason",   "").trim();

        if (issueKey.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "issueKey is required"));
        if (reason.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "reason is required"));

        String comment = "SLA Breached. Reason: " + reason;
        boolean ok = jiraClient.postComment(issueKey, comment);

        if (ok) return ResponseEntity.ok(Map.of("message", "Comment added to " + issueKey));
        return ResponseEntity.internalServerError()
                             .body(Map.of("error", "Failed to post comment on " + issueKey));
    }

    /**
     * Returns the allowed severity option values in the order Jira defines them.
     * The frontend uses this order to assign colours — first = most severe (red),
     * last = least severe (green). No values are hardcoded here.
     *
     * GET /api/sla/severity-options
     */
    @GetMapping("/sla/severity-options")
    public ResponseEntity<List<String>> getSeverityOptions() {
        return ResponseEntity.ok(jiraClient.getSeverityOptions());
    }

    @GetMapping("/sla")
    public ResponseEntity<?> getSla(@RequestParam String team,
                                    @RequestParam(defaultValue = "all") String period) {

        Team t = teamRepository.findById(team).orElse(null);
        if (t == null) return ResponseEntity.notFound().build();

        String fieldId = configService.getSlaFieldId();
        if (fieldId == null || fieldId.isBlank())
            return ResponseEntity.ok(Map.of("error", "sla_not_configured"));

        String sevKey = jiraClient.discoverSeverityFieldKey();

        // Open tickets — use original team JQL status filter, no date limit
        // (a ticket breached 60 days ago that is still open must still show)
        List<JsonNode> openAll = jiraClient.getOpenSlaTickets(t.getJql(), fieldId);
        List<JsonNode> openBreached = openAll.stream()
            .filter(tk -> Boolean.TRUE.equals(extractSla(tk.path("fields").path(fieldId)).get("breached")))
            .collect(java.util.stream.Collectors.toList());

        // Resolved/Closed tickets — filtered by resolved date window (period)
        List<JsonNode> resolvedAll = jiraClient.getResolvedSlaTickets(t.getJql(), fieldId, period);
        List<JsonNode> resolvedBreached = resolvedAll.stream()
            .filter(tk -> Boolean.TRUE.equals(extractSla(tk.path("fields").path(fieldId)).get("breached")))
            .collect(java.util.stream.Collectors.toList());

        log.info("[SLA] period={} openTotal={} openBreached={} resolvedTotal={} resolvedBreached={}",
            period, openAll.size(), openBreached.size(), resolvedAll.size(), resolvedBreached.size());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("open",     groupByBreachOwner(openBreached,     fieldId, sevKey));
        result.put("resolved", groupByBreachOwner(resolvedBreached, fieldId, sevKey));
        return ResponseEntity.ok(result);
    }

    // -----------------------------------------------------------------------
    // Grouping helper — shared by open and resolved sections
    // -----------------------------------------------------------------------

    private List<Map<String, Object>> groupByBreachOwner(
            List<JsonNode> tickets, String fieldId, String sevKey) {

        Map<String, Map<String, Object>> byAssignee = new LinkedHashMap<>();

        for (JsonNode ticket : tickets) {
            JsonNode assigneeNode = ticket.path("fields").path("assignee");
            if (assigneeNode.isNull() || assigneeNode.isMissingNode()) continue;

            String currentAccId = assigneeNode.path("accountId").asText("");
            String currentEmail = assigneeNode.path("emailAddress").asText("");
            String currentName  = assigneeNode.path("displayName").asText(currentEmail);

            JsonNode slaField        = ticket.path("fields").path(fieldId);
            Map<String, Object> slaInfo = extractSla(slaField);

            String severity = (sevKey != null)
                ? extractSeverity(ticket.path("fields").path(sevKey)) : "";
            String issueKey = ticket.path("key").asText();

            boolean breached    = Boolean.TRUE.equals(slaInfo.get("breached"));
            long    breachEpoch = slaInfo.containsKey("breachEpoch")
                                  ? ((Number) slaInfo.get("breachEpoch")).longValue() : 0L;

            String groupAccId   = currentAccId;
            String groupEmail   = currentEmail;
            String groupName    = currentName;
            String reassignedTo = null;

            if (breached && breachEpoch > 0) {
                String ownerAtBreach = jiraClient.getAssigneeAtEpoch(issueKey, breachEpoch);
                if (ownerAtBreach != null && !ownerAtBreach.equals(currentAccId)) {
                    Map<String, String> ownerInfo = jiraClient.getUserInfo(ownerAtBreach);
                    groupAccId   = ownerAtBreach;
                    groupEmail   = ownerInfo.getOrDefault("email", "");
                    groupName    = ownerInfo.getOrDefault("displayName",
                                       groupEmail.isEmpty() ? ownerAtBreach : groupEmail);
                    reassignedTo = currentName.isEmpty() ? currentEmail : currentName;
                }
            }

            String mapKey = groupEmail.isEmpty() ? groupAccId : groupEmail;
            if (mapKey.isEmpty()) mapKey = "unknown";

            final String finalEmail = groupEmail;
            final String finalName  = groupName;
            Map<String, Object> entry = byAssignee.computeIfAbsent(mapKey, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("email",   finalEmail);
                m.put("name",    finalName);
                m.put("tickets", new ArrayList<>());
                return m;
            });

            Map<String, Object> ticketData = new LinkedHashMap<>();
            ticketData.put("key",      issueKey);
            ticketData.put("summary",  ticket.path("fields").path("summary").asText(""));
            ticketData.put("status",   ticket.path("fields").path("status").path("name").asText(""));
            ticketData.put("severity", severity);
            ticketData.put("sla",      slaInfo);
            if (reassignedTo != null) ticketData.put("reassignedTo", reassignedTo);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> list = (List<Map<String, Object>>) entry.get("tickets");
            list.add(ticketData);
        }

        return new ArrayList<>(byAssignee.values());
    }

    // -----------------------------------------------------------------------
    // SLA field → structured status
    // -----------------------------------------------------------------------

    /**
     * Parses a Jira SLA custom field node into a flat status map.
     *
     * Jira SLA field shape:
     * {
     *   "ongoingCycle": {
     *     "breachTime":    { "epochMillis": ..., "friendly": "Today 12:00 PM" },
     *     "remainingTime": { "millis": 7500000,  "friendly": "2h 5m" },
     *     "breached": false,
     *     "paused":   false,
     *     "goalDuration": { "friendly": "4h" }
     *   },
     *   "completedCycles": [ ... ]
     * }
     */
    private Map<String, Object> extractSla(JsonNode slaNode) {
        Map<String, Object> info = new LinkedHashMap<>();

        if (slaNode == null || slaNode.isNull() || slaNode.isMissingNode()) {
            info.put("available", false);
            info.put("status",    "unavailable");
            return info;
        }
        info.put("available", true);

        // --- ongoing cycle (SLA is still running) ---
        JsonNode ongoing = slaNode.path("ongoingCycle");
        if (!ongoing.isMissingNode() && !ongoing.isNull()) {
            boolean breached = ongoing.path("breached").asBoolean(false);
            boolean paused   = ongoing.path("paused").asBoolean(false);
            long    millis   = ongoing.path("remainingTime").path("millis").asLong(0);
            long    goalMs   = ongoing.path("goalDuration").path("millis").asLong(0);

            // Jira sometimes returns millis=0 with breached=true — treat negative or zero+breached as breached
            if (millis < 0) breached = true;

            String slaStatus = breached ? "breached" : (paused ? "paused" : "ongoing");

            info.put("status",          slaStatus);
            info.put("breached",        breached);
            info.put("paused",          paused);
            info.put("remaining",       ongoing.path("remainingTime").path("friendly").asText(""));
            info.put("remainingMillis", millis);
            info.put("goalMillis",      goalMs);
            info.put("goal",            ongoing.path("goalDuration").path("friendly").asText(""));
            info.put("breachTime",      ongoing.path("breachTime").path("friendly").asText(""));
            info.put("breachEpoch",     ongoing.path("breachTime").path("epochMillis").asLong(0));
            return info;
        }

        // --- completed cycles (SLA already finished) ---
        JsonNode completed = slaNode.path("completedCycles");
        if (completed.isArray() && completed.size() > 0) {
            JsonNode last     = completed.get(completed.size() - 1);
            boolean  breached = last.path("breached").asBoolean(false);

            info.put("status",          breached ? "completed_breached" : "completed");
            info.put("breached",        breached);
            info.put("paused",          false);
            info.put("remaining",       "");
            info.put("remainingMillis", 0L);
            info.put("completedAt",     last.path("stopTime").path("friendly").asText(""));
            info.put("goal",            last.path("goalDuration").path("friendly").asText(""));
            // breachTime is available on completed cycles too — used for breach attribution
            info.put("breachEpoch",     last.path("breachTime").path("epochMillis").asLong(0));
            return info;
        }

        // --- SLA not yet started ---
        info.put("status",          "not_started");
        info.put("breached",        false);
        info.put("paused",          false);
        info.put("remaining",       "");
        info.put("remainingMillis", 0L);
        return info;
    }

    /**
     * Extracts the severity label from the Jira "severity" field node.
     * The field can be:
     *   - a plain string: "High"
     *   - an object with "name": { "name": "High", "id": "2" }
     *   - an object with "value": { "value": "High" }   (some custom select fields)
     *   - null / missing
     */
    private String extractSeverity(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return "";
        if (node.isTextual()) return node.asText();
        if (node.has("name"))  return node.path("name").asText("");
        if (node.has("value")) return node.path("value").asText("");
        return "";
    }
}
