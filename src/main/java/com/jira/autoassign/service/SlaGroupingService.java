package com.jira.autoassign.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.jira.autoassign.client.JiraClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns raw breached-ticket JSON from Jira into per-assignee groups.
 *
 * Extracted from SlaController so the SLA tab, the Excel report and the daily
 * breach-reason report email all attribute breaches the same way — there is only
 * one place that decides who a breach belongs to.
 *
 * Grouping key: the person who held the ticket at breach time (open tickets, via
 * changelog lookup) or the current assignee (resolved tickets — too many to walk
 * history for). Tickets with nobody attributable land under "Unassigned".
 */
@Service
public class SlaGroupingService {

    private final JiraClient jiraClient;

    public SlaGroupingService(JiraClient jiraClient) {
        this.jiraClient = jiraClient;
    }

    /**
     * @param tickets        breached tickets straight from a {@code cf[X] = breached()} search
     * @param fieldId        SLA custom field id (e.g. customfield_10020)
     * @param sevKey         severity field key, or null when not discoverable
     * @param doAttribution  true → resolve the owner at breach time from the changelog
     * @return one entry per assignee: { email, name, tickets: [ { key, summary, status,
     *         severity, sla, reassignedTo? } ] }
     */
    public List<Map<String, Object>> groupByBreachOwner(
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
                boolean hasCycles = !slaField.path("completedCycles").isMissingNode()
                                    && slaField.path("completedCycles").size() > 0;
                slaInfo.put("status", hasCycles ? "completed_breached" : "breached");
            }
            slaInfo.put("available", true);

            String severity = (sevKey != null)
                ? extractSeverity(ticket.path("fields").path(sevKey)) : "";
            String issueKey = ticket.path("key").asText();

            long breachEpoch = slaInfo.containsKey("breachEpoch")
                               ? ((Number) slaInfo.get("breachEpoch")).longValue() : 0L;

            String groupAccId   = currentAccId;
            String groupEmail   = currentEmail;
            String groupName    = currentName;
            String reassignedTo = null;

            if (doAttribution && breachEpoch > 0) {
                // Uses the inline changelog (expand=changelog) + cache; no per-ticket
                // call unless the inline history is missing/truncated.
                String ownerAtBreach = jiraClient.resolveBreachOwner(ticket, breachEpoch);
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
    public Map<String, Object> extractSla(JsonNode slaNode) {
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
            info.put("breachTime",      last.path("breachTime").path("friendly").asText(""));
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
    public String extractSeverity(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return "";
        if (node.isTextual()) return node.asText();
        if (node.has("name"))  return node.path("name").asText("");
        if (node.has("value")) return node.path("value").asText("");
        return "";
    }
}
