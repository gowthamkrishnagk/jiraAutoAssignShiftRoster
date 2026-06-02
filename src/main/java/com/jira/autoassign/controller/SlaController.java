package com.jira.autoassign.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.jira.autoassign.client.JiraClient;
import com.jira.autoassign.entity.BreachComment;
import com.jira.autoassign.entity.Team;
import com.jira.autoassign.repository.BreachCommentRepository;
import com.jira.autoassign.repository.TeamRepository;
import com.jira.autoassign.service.JiraConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
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

    private final JiraClient              jiraClient;
    private final TeamRepository          teamRepository;
    private final JiraConfigService       configService;
    private final BreachCommentRepository commentRepo;

    public SlaController(JiraClient jiraClient,
                         TeamRepository teamRepository,
                         JiraConfigService configService,
                         BreachCommentRepository commentRepo) {
        this.jiraClient    = jiraClient;
        this.teamRepository = teamRepository;
        this.configService  = configService;
        this.commentRepo    = commentRepo;
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

        if (ok) {
            // Persist to DB so the UI shows "already commented" after refresh.
            // If the same ticket is re-commented, update the reason.
            BreachComment bc = commentRepo.findByIssueKey(issueKey)
                                          .orElse(new BreachComment(issueKey, reason));
            bc.setReason(reason);
            bc.setCommentedAt(java.time.LocalDateTime.now());
            commentRepo.save(bc);
            return ResponseEntity.ok(Map.of("message", "Comment added to " + issueKey));
        }
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
                                    @RequestParam(required = false) String date) {

        Team t = teamRepository.findById(team).orElse(null);
        if (t == null) return ResponseEntity.notFound().build();

        String fieldId = configService.getSlaFieldId();
        if (fieldId == null || fieldId.isBlank())
            return ResponseEntity.ok(Map.of("error", "sla_not_configured"));

        String sevKey = jiraClient.discoverSeverityFieldKey();

        // Both queries use cf[X]=breached() — Jira is the source of truth for breach detection.
        // No Java-side re-filtering: every ticket returned is already confirmed breached by Jira.
        // Resolved query is scoped to the selected calendar date (default = today).
        List<JsonNode> openBreached     = jiraClient.getOpenSlaTickets(t.getJql(), fieldId);
        List<JsonNode> resolvedBreached = jiraClient.getResolvedSlaTickets(t.getJql(), fieldId, date);

        log.info("[SLA] date={} openBreached={} resolvedBreached={}",
            date == null ? "today" : date, openBreached.size(), resolvedBreached.size());

        // Build commentedTickets map from DB — { "SAC-123": "Aria Escalation", ... }
        // Frontend uses this to show already-commented state after refresh.
        Map<String, String> commentedTickets = new LinkedHashMap<>();
        commentRepo.findAll().forEach(bc -> commentedTickets.put(bc.getIssueKey(), bc.getReason()));

        Map<String, Object> result = new LinkedHashMap<>();
        // Open: attribute breaches to who had ticket at breach time (changelog lookup)
        // Resolved: skip attribution — too many tickets; use current assignee
        result.put("open",             groupByBreachOwner(openBreached,     fieldId, sevKey, true));
        result.put("resolved",         groupByBreachOwner(resolvedBreached, fieldId, sevKey, false));
        result.put("commentedTickets", commentedTickets);
        return ResponseEntity.ok(result);
    }

    /**
     * Date-range breach report — backs the "Download SLA Report" Excel export.
     *
     * GET /api/sla/report?team={id}&from=YYYY-MM-DD&to=YYYY-MM-DD
     *
     * Returns:
     *   {
     *     "from": "...", "to": "...",
     *     "open":  [ ...grouped open breached snapshot... ],   // point-in-time, not per-day
     *     "days":  [ { "date": "YYYY-MM-DD", "resolved": [ ...grouped... ] }, ... ],
     *     "commentedTickets": { "SAC-123": "Aria Escalation", ... }
     *   }
     *
     * One Jira search per day in the range (resolved/closed breaches scoped to that
     * calendar day by `updated` date), plus one snapshot query for open breaches.
     * The frontend turns this into one sheet per day + an Open Breached sheet +
     * an Overall sheet for the whole range.
     */
    @GetMapping("/sla/report")
    public ResponseEntity<?> getSlaReport(@RequestParam String team,
                                          @RequestParam String from,
                                          @RequestParam String to) {

        Team t = teamRepository.findById(team).orElse(null);
        if (t == null) return ResponseEntity.notFound().build();

        String fieldId = configService.getSlaFieldId();
        if (fieldId == null || fieldId.isBlank())
            return ResponseEntity.ok(Map.of("error", "sla_not_configured"));

        LocalDate fromDate, toDate;
        try {
            fromDate = LocalDate.parse(from);   // expects YYYY-MM-DD
            toDate   = LocalDate.parse(to);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid date — use YYYY-MM-DD."));
        }
        if (toDate.isBefore(fromDate))
            return ResponseEntity.badRequest().body(Map.of("error", "'To' date is before 'From' date."));

        // Breach reasons drive the report window. Reasons are kept for a rolling
        // 30 days, so the DB only holds recent ones. We clamp the range to the FIRST
        // and LAST logged reason date (by commentedAt). Every day in between is still
        // emitted — including gap days where nobody logged a reason (those just show
        // breached tickets with blank reason cells), so no dates are skipped.
        List<BreachComment> comments = commentRepo.findAll();
        if (comments.isEmpty())
            return ResponseEntity.badRequest()
                .body(Map.of("error", "No breach reason data in the database yet."));

        Map<String, String> commentedTickets = new LinkedHashMap<>();
        LocalDate minReason = null, maxReason = null;
        for (BreachComment bc : comments) {
            commentedTickets.put(bc.getIssueKey(), bc.getReason());
            LocalDate d = bc.getCommentedAt().toLocalDate();
            if (minReason == null || d.isBefore(minReason)) minReason = d;
            if (maxReason == null || d.isAfter(maxReason))  maxReason = d;
        }

        // Clamp only the two ends to the first/last logged reason — the span in
        // between (gap days included) is reported in full.
        LocalDate effFrom = fromDate.isBefore(minReason) ? minReason : fromDate;
        LocalDate effTo   = toDate.isAfter(maxReason)    ? maxReason : toDate;
        if (effTo.isBefore(effFrom))
            return ResponseEntity.badRequest().body(Map.of(
                "error", "No breach reason data in the selected range. "
                       + "Reasons are available " + minReason + " to " + maxReason + "."));

        long span = effTo.toEpochDay() - effFrom.toEpochDay() + 1;
        if (span > 92)
            return ResponseEntity.badRequest()
                .body(Map.of("error", "Range too large (" + span + " days). Maximum is 92 days."));

        String sevKey = jiraClient.discoverSeverityFieldKey();

        // Open breaches — point-in-time snapshot, attributed to who held the ticket at breach time
        List<JsonNode> openBreached = jiraClient.getOpenSlaTickets(t.getJql(), fieldId);

        // One block per calendar day in the clamped range: resolved/closed breaches updated that day
        List<Map<String, Object>> dayBlocks = new ArrayList<>();
        for (LocalDate d = effFrom; !d.isAfter(effTo); d = d.plusDays(1)) {
            List<JsonNode> resolved = jiraClient.getResolvedSlaTickets(t.getJql(), fieldId, d.toString());
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("date",     d.toString());
            block.put("resolved", groupByBreachOwner(resolved, fieldId, sevKey, false));
            dayBlocks.add(block);
        }

        boolean clamped = !effFrom.equals(fromDate) || !effTo.equals(toDate);
        log.info("[SLA] Report team={} requested {}..{} effective {}..{} ({} days) open={}",
            team, fromDate, toDate, effFrom, effTo, span, openBreached.size());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("from",             effFrom.toString());
        result.put("to",               effTo.toString());
        result.put("reasonDataFrom",   minReason.toString());
        result.put("reasonDataTo",     maxReason.toString());
        if (clamped)
            result.put("note", "Range adjusted to the logged breach-reason window — first reason "
                             + minReason + " to last reason " + maxReason
                             + ". All days in between are included.");
        result.put("open",             groupByBreachOwner(openBreached, fieldId, sevKey, true));
        result.put("days",             dayBlocks);
        result.put("commentedTickets", commentedTickets);
        return ResponseEntity.ok(result);
    }

    /**
     * Distinct dates that have a stored breach reason (by commentedAt).
     * Drives the From/To dropdowns in the report modal so users can only pick
     * dates that actually exist in the DB. Sorted ascending (YYYY-MM-DD).
     *
     * GET /api/sla/report/dates
     */
    @GetMapping("/sla/report/dates")
    public ResponseEntity<?> getReportDates() {
        TreeSet<String> dates = new TreeSet<>();
        commentRepo.findAll().forEach(bc ->
            dates.add(bc.getCommentedAt().toLocalDate().toString()));
        return ResponseEntity.ok(Map.of("dates", new ArrayList<>(dates)));
    }

    // -----------------------------------------------------------------------
    // Grouping helper — shared by open and resolved sections
    // -----------------------------------------------------------------------

    private List<Map<String, Object>> groupByBreachOwner(
            List<JsonNode> tickets, String fieldId, String sevKey, boolean doAttribution) {

        Map<String, Map<String, Object>> byAssignee = new LinkedHashMap<>();

        for (JsonNode ticket : tickets) {
            JsonNode assigneeNode = ticket.path("fields").path("assignee");
            boolean unassigned = assigneeNode.isNull() || assigneeNode.isMissingNode();

            String currentAccId = unassigned ? "" : assigneeNode.path("accountId").asText("");
            String currentEmail = unassigned ? "" : assigneeNode.path("emailAddress").asText("");
            String currentName  = unassigned ? "" : assigneeNode.path("displayName").asText(currentEmail);

            JsonNode slaField        = ticket.path("fields").path(fieldId);
            Map<String, Object> slaInfo = new LinkedHashMap<>(extractSla(slaField));

            // Every ticket here came from cf[X]=breached() — Jira confirmed it.
            // Force breached=true so the frontend isBreached() check never drops it,
            // even when extractSla() can't parse the SLA field structure perfectly.
            slaInfo.put("breached", true);
            if (!"completed_breached".equals(slaInfo.get("status"))
                    && !"breached".equals(slaInfo.get("status"))) {
                // Preserve readable status: completed cycle → completed_breached, else breached
                boolean hasCycles = !ticket.path("fields").path(fieldId)
                                           .path("completedCycles").isMissingNode()
                                    && ticket.path("fields").path(fieldId)
                                             .path("completedCycles").size() > 0;
                slaInfo.put("status", hasCycles ? "completed_breached" : "breached");
            }
            slaInfo.put("available", true);

            String severity = (sevKey != null)
                ? extractSeverity(ticket.path("fields").path(sevKey)) : "";
            String issueKey = ticket.path("key").asText();

            boolean breached    = true; // always true — ticket came from breached() JQL
            long    breachEpoch = slaInfo.containsKey("breachEpoch")
                                  ? ((Number) slaInfo.get("breachEpoch")).longValue() : 0L;

            String groupAccId   = currentAccId;
            String groupEmail   = currentEmail;
            String groupName    = currentName;
            String reassignedTo = null;

            if (doAttribution && breached && breachEpoch > 0) {
                String ownerAtBreach = jiraClient.getAssigneeAtEpoch(issueKey, breachEpoch);
                if (ownerAtBreach != null && !ownerAtBreach.equals(currentAccId)) {
                    Map<String, String> ownerInfo = jiraClient.getUserInfo(ownerAtBreach);
                    groupAccId   = ownerAtBreach;
                    groupEmail   = ownerInfo.getOrDefault("email", "");
                    groupName    = ownerInfo.getOrDefault("displayName",
                                       groupEmail.isEmpty() ? ownerAtBreach : groupEmail);
                    // For unassigned tickets, show "Now: Unassigned" badge
                    reassignedTo = unassigned ? "Unassigned"
                                 : (currentName.isEmpty() ? currentEmail : currentName);
                }
            }

            // If still no group info (unassigned and no breach-owner found), group under "Unassigned"
            if (groupAccId.isEmpty() && groupEmail.isEmpty()) {
                groupName  = "Unassigned";
                groupEmail = "__unassigned__";
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
            // Fallback: Jira occasionally sets breached=false even when elapsed > goal.
            // cf[X]=breached() in the JQL already confirmed the ticket is breached —
            // sync our flag with the elapsed-vs-goal check as a safety net.
            if (!breached) {
                long elapsed = last.path("elapsedTime").path("millis").asLong(0);
                long goal    = last.path("goalDuration").path("millis").asLong(0);
                if (goal > 0 && elapsed >= goal) breached = true;
            }

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
