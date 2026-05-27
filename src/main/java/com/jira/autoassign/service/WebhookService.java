package com.jira.autoassign.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Builds a fully responsive Adaptive Card and POSTs it to a Power Automate
 * HTTP-trigger webhook URL.
 *
 * Card layout (Container per ticket — works on mobile / tablet / desktop):
 * ┌──────────────────────────────────────────────────────────┐
 * │ 🔄 Shift Handover — 4 Ticket(s) Reassigned              │
 * │ Team: Order Fallout                       14:30          │
 * ├──────────────────────────────────────────────────────────┤
 * │ [SAC-001]                          1h 45m remaining ✅   │
 * │ Full ticket summary text that wraps on narrow screens    │
 * │ From: john@company.com   →   To: alice@company.com       │
 * ├──────────────────────────────────────────────────────────┤
 * │ [SAC-002]                          ⚠ Breached ❌         │
 * │ ...                                                      │
 * └──────────────────────────────────────────────────────────┘
 *
 * PA Compose:  @{triggerBody()?['adaptiveCard']}
 */
@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final JiraConfigService jiraConfigService;
    private final ObjectMapper      mapper = new ObjectMapper();

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    public WebhookService(JiraConfigService jiraConfigService) {
        this.jiraConfigService = jiraConfigService;
    }

    // -----------------------------------------------------------------------
    // Called by ShiftAssignService — ONE call per scheduler cycle
    // -----------------------------------------------------------------------

    public void fireReassignments(String teamId, String teamName,
                                   List<Map<String, String>> tickets) {
        String webhookUrl = jiraConfigService.getWebhookUrl();
        if (webhookUrl == null || webhookUrl.isBlank()) return;
        if (tickets == null || tickets.isEmpty()) return;

        String jiraBase = jiraConfigService.getUrl();

        List<Map<String, String>> enriched = tickets.stream().map(t -> {
            Map<String, String> m = new LinkedHashMap<>(t);
            m.put("url", jiraBase + "/browse/" + t.get("key"));
            return m;
        }).collect(Collectors.toList());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("teamName",    teamName != null ? teamName : teamId);
        payload.put("timestamp",   LocalDateTime.now().format(FMT));
        payload.put("ticketCount", enriched.size());
        payload.put("tickets",     enriched);
        payload.put("adaptiveCard", buildAdaptiveCard(teamId, teamName, enriched));

        try {
            String body = mapper.writeValueAsString(payload);
            sendAsync(webhookUrl, body, teamId, "batch[" + enriched.size() + " tickets]");
        } catch (Exception e) {
            log.error("[{}] Failed to build webhook payload: {}", teamId, e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Test — 4 sample tickets with realistic data
    // -----------------------------------------------------------------------

    public int testWebhook(String url) {
        if (url == null || url.isBlank()) return -1;

        String jiraBase = jiraConfigService.getUrl();

        List<Map<String, String>> testTickets = List.of(
            ticket("SAC-001",
                   "Order activation failure — Prod customer B2C account stuck in provisioning queue since yesterday",
                   "1h 45m remaining",
                   "john.doe@company.com", "alice@company.com",
                   jiraBase + "/browse/SAC-001"),
            ticket("SAC-002",
                   "Network provisioning issue — B2B wholesale partner circuit down affecting 3 customers",
                   "⚠ Breached",
                   "john.doe@company.com", "alice@company.com",
                   jiraBase + "/browse/SAC-002"),
            ticket("SAC-003",
                   "SIM swap stuck in queue — urgent customer complaint raised, needs immediate attention",
                   "3h 20m remaining",
                   "jane.smith@company.com", "bob@company.com",
                   jiraBase + "/browse/SAC-003"),
            ticket("SAC-004",
                   "B2C order failed — Nokia Escalation path assigned, waiting on L2 support response",
                   "",
                   "jane.smith@company.com", "alice@company.com",
                   jiraBase + "/browse/SAC-004")
        );

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("teamName",    "Order Fallout");
        payload.put("timestamp",   LocalDateTime.now().format(FMT));
        payload.put("ticketCount", testTickets.size());
        payload.put("tickets",     testTickets);
        payload.put("adaptiveCard", buildAdaptiveCard("test", "Order Fallout", testTickets));

        try {
            String body = mapper.writeValueAsString(payload);
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(15))
                .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            log.info("[webhook-test] POST {} → HTTP {}", url, resp.statusCode());
            return resp.statusCode();
        } catch (Exception e) {
            log.warn("[webhook-test] Failed: {}", e.getMessage());
            return -1;
        }
    }

    // -----------------------------------------------------------------------
    // Build fully responsive Adaptive Card — Container per ticket
    //
    // Adapts to any screen width (mobile / tablet / desktop):
    //
    //  [SAC-001]                          1h 45m remaining ✓
    //  Full ticket summary wraps naturally on narrow screens
    //  From: john.doe@co…        →        To: alice@co…
    //  ─────────────────────────────────────────────────────
    //  [SAC-002]                              ⚠ Breached
    //  Network provisioning issue — B2B wholesale partner…
    //  From: john.doe@co…        →        To: alice@co…
    // -----------------------------------------------------------------------

    private Map<String, Object> buildAdaptiveCard(String teamId, String teamName,
                                                   List<Map<String, String>> tickets) {
        String displayName = (teamName != null && !teamName.isBlank()) ? teamName : teamId;
        String timestamp   = LocalDateTime.now().format(FMT);

        List<Map<String, Object>> body = new ArrayList<>();

        // ── Banner ────────────────────────────────────────────────────────────
        Map<String, Object> banner = new LinkedHashMap<>();
        banner.put("type",  "Container");
        banner.put("style", "emphasis");
        banner.put("bleed", true);
        banner.put("items", List.of(
            tb("🔄  Shift Handover — " + tickets.size() + " Ticket(s) Reassigned",
               "Bolder", "Accent", "Large", true),
            colSet(
                col("stretch", List.of(tb("**Team:** " + displayName, null, null, "Small", false))),
                col("auto",    List.of(tb("🕐 " + timestamp, null, "Accent", "Small", false)))
            )
        ));
        body.add(banner);

        // ── One Container per ticket ──────────────────────────────────────────
        int idx = 0;
        for (Map<String, String> t : tickets) {
            idx++;
            String key     = t.getOrDefault("key",             "");
            String url     = t.getOrDefault("url",             "");
            String summary = t.getOrDefault("summary",         "");
            String sla     = t.getOrDefault("slaRemaining",    "");
            String from    = t.getOrDefault("currentAssignee", "");
            String to      = t.getOrDefault("reassignedTo",    "");

            String slaText  = sla.isBlank() ? "Not Set" : sla;
            String slaColor = sla.isBlank()            ? "Default"
                            : sla.contains("Breached") ? "Attention"
                            : "Good";
            String slaIcon  = sla.isBlank()            ? "⏱"
                            : sla.contains("Breached") ? "🔴"
                            : "🟢";

            // ── Line 1: #N  [SAC-001] ↗  (full width, no crowding) ───────
            Map<String, Object> line1 = colSet(
                col("auto",    List.of(tb("#" + idx, "Bolder", "Default", "Small", false))),
                col("stretch", List.of(tb("[" + key + "](" + url + ")  ↗", "Bolder", "Accent", "Medium", false)))
            );

            // ── Line 2: SLA: 🟢 1h 45m remaining  (own line, colored) ───
            Map<String, Object> slaLine = tb("SLA:  " + slaIcon + "  " + slaText, "Bolder", slaColor, "Small", false);
            slaLine.put("spacing", "Small");

            // ── Line 3: 📋 Summary label ──────────────────────────────────
            Map<String, Object> summaryLabel = tb("📋  Summary", "Bolder", null, "Small", false);
            summaryLabel.put("spacing", "Small");

            // ── Line 4: summary text (wraps freely) ───────────────────────
            Map<String, Object> summaryText = tb(summary, null, null, "Small", true);
            summaryText.put("spacing", "None");
            summaryText.put("isSubtle", true);

            // ── Lines 5-6: FactSet — From / To ───────────────────────────
            Map<String, Object> factSet = new LinkedHashMap<>();
            factSet.put("type",    "FactSet");
            factSet.put("spacing", "Small");
            factSet.put("facts", List.of(
                Map.of("title", "🔁  From", "value", from),
                Map.of("title", "✅  To",   "value", to)
            ));

            Map<String, Object> ticketContainer = new LinkedHashMap<>();
            ticketContainer.put("type",      "Container");
            ticketContainer.put("separator", true);
            ticketContainer.put("spacing",   "Medium");
            ticketContainer.put("items",     List.of(line1, slaLine, summaryLabel, summaryText, factSet));
            body.add(ticketContainer);
        }

        // ── Adaptive Card root ───────────────────────────────────────────────
        Map<String, Object> adaptiveCard = new LinkedHashMap<>();
        adaptiveCard.put("type",    "AdaptiveCard");
        adaptiveCard.put("$schema", "http://adaptivecards.io/schemas/adaptive-card.json");
        adaptiveCard.put("version", "1.5");
        adaptiveCard.put("msteams", Map.of("width", "Full"));
        adaptiveCard.put("body",    body);

        return adaptiveCard;
    }

    // -----------------------------------------------------------------------
    // Card builder helpers
    // -----------------------------------------------------------------------

    /** TextBlock */
    private static Map<String, Object> tb(String text, String weight,
                                           String color, String size, boolean wrap) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "TextBlock");
        m.put("text", text);
        m.put("wrap", wrap);
        m.put("size", size != null ? size : "Small");
        if (weight != null) m.put("weight", weight);
        if (color  != null) m.put("color",  color);
        return m;
    }

    /** Column */
    private static Map<String, Object> col(String width, List<Map<String, Object>> items) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type",  "Column");
        m.put("width", width);
        m.put("items", items);
        return m;
    }

    /** ColumnSet — varargs for 2 or 3 columns */
    @SafeVarargs
    private static Map<String, Object> colSet(Map<String, Object>... cols) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type",    "ColumnSet");
        m.put("columns", List.of(cols));
        return m;
    }

    // -----------------------------------------------------------------------
    // Async HTTP sender
    // -----------------------------------------------------------------------

    private void sendAsync(String url, String body, String teamId, String label) {
        Thread.ofVirtual().start(() -> {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(10))
                    .build();
                HttpResponse<String> resp =
                    httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() >= 200 && resp.statusCode() < 300)
                    log.info("[{}] Webhook sent ({}): HTTP {}", teamId, label, resp.statusCode());
                else
                    log.warn("[{}] Webhook ({}) HTTP {}: {}", teamId, label, resp.statusCode(), resp.body());
            } catch (Exception e) {
                log.warn("[{}] Webhook ({}) failed: {}", teamId, label, e.getMessage());
            }
        });
    }

    // -----------------------------------------------------------------------
    // Test-ticket factory
    // -----------------------------------------------------------------------

    private static Map<String, String> ticket(String key, String summary,
                                               String slaRemaining,
                                               String currentAssignee,
                                               String reassignedTo, String url) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("key",             key);
        m.put("summary",         summary);
        m.put("slaRemaining",    slaRemaining);
        m.put("currentAssignee", currentAssignee);
        m.put("reassignedTo",    reassignedTo);
        m.put("url",             url);
        return m;
    }
}
