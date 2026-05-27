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
 * Posts a Microsoft Teams Adaptive Card to an incoming webhook URL whenever tickets
 * are reassigned during the shift-handover sweep.
 *
 * ONE call per scheduler cycle — all off-shift people's tickets in one card.
 *
 * Card layout (ColumnSet table — full summary, no truncation):
 * ┌──────────┬──────────────────────────────────────┬────────────────────┬─────────────────────┬─────────────────────┐
 * │ Ticket   │ Summary                              │ Time to Resolution │ Current Assignee    │ Reassigned To       │
 * ├──────────┼──────────────────────────────────────┼────────────────────┼─────────────────────┼─────────────────────┤
 * │ SAC-123  │ Full summary text, wraps freely …    │ 2h 5m remaining    │ john@company.com    │ alice@company.com   │
 * └──────────┴──────────────────────────────────────┴────────────────────┴─────────────────────┴─────────────────────┘
 *
 * In Power Automate the flow is simply:
 *   1. HTTP Trigger  (receives this payload)
 *   2. Post card in a chat or channel
 *        Adaptive Card: @{triggerBody()}
 *
 * No Compose step needed — the full card JSON is sent by the app.
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

        String body = buildCard(teamName != null ? teamName : teamId,
                                LocalDateTime.now().format(FMT),
                                enriched);

        sendAsync(webhookUrl, body, teamId, "batch[" + enriched.size() + " tickets]");
    }

    // -----------------------------------------------------------------------
    // Test
    // -----------------------------------------------------------------------

    public int testWebhook(String url) {
        if (url == null || url.isBlank()) return -1;

        String jiraBase = jiraConfigService.getUrl();
        List<Map<String, String>> samples = List.of(
            ticket("SAC-001", "Order activation failure — Prod customer B2C account stuck in provisioning queue",
                   "1h 45m remaining",  "john.doe@company.com",   "alice@company.com",  jiraBase + "/browse/SAC-001"),
            ticket("SAC-002", "Network provisioning issue — B2B wholesale partner circuit down",
                   "⚠ Breached",        "john.doe@company.com",   "alice@company.com",  jiraBase + "/browse/SAC-002"),
            ticket("SAC-003", "SIM swap stuck in queue — urgent customer complaint raised",
                   "3h 20m remaining",  "jane.smith@company.com", "bob@company.com",    jiraBase + "/browse/SAC-003"),
            ticket("SAC-004", "B2C order failed — Nokia Escalation path assigned needs immediate review",
                   "",                  "jane.smith@company.com", "alice@company.com",  jiraBase + "/browse/SAC-004")
        );

        String body = buildCard("Order Fallout", LocalDateTime.now().format(FMT), samples);

        try {
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
    // Adaptive Card builder — ColumnSet table, full summary, no truncation
    // -----------------------------------------------------------------------

    private String buildCard(String teamName, String timestamp,
                              List<Map<String, String>> tickets) {
        try {
            List<Object> body = new ArrayList<>();

            // ── Title ────────────────────────────────────────────────────
            body.add(map(
                "type",   "TextBlock",
                "text",   "🔄 Shift Handover — " + tickets.size() + " Ticket(s) Reassigned",
                "weight", "Bolder",
                "size",   "Medium",
                "color",  "Accent",
                "wrap",   true
            ));

            // ── Summary facts ─────────────────────────────────────────────
            Map<String, Object> facts = new LinkedHashMap<>();
            facts.put("type",      "FactSet");
            facts.put("separator", true);
            facts.put("facts", List.of(
                fact("Team",  teamName),
                fact("Time",  timestamp)
            ));
            body.add(facts);

            // ── Column header row ─────────────────────────────────────────
            body.add(headerRow());

            // ── One ColumnSet row per ticket ──────────────────────────────
            for (Map<String, String> t : tickets) {
                body.add(dataRow(t));
            }

            // ── Adaptive Card ─────────────────────────────────────────────
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("$schema", "http://adaptivecards.io/schemas/adaptive-card.json");
            card.put("type",    "AdaptiveCard");
            card.put("version", "1.2");
            card.put("body",    body);
            card.put("msteams", Map.of("width", "Full"));

            // ── Teams envelope ────────────────────────────────────────────
            Map<String, Object> attachment = new LinkedHashMap<>();
            attachment.put("contentType", "application/vnd.microsoft.card.adaptive");
            attachment.put("contentUrl",  null);
            attachment.put("content",     card);

            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("type",        "message");
            envelope.put("attachments", List.of(attachment));

            return mapper.writeValueAsString(envelope);
        } catch (Exception e) {
            log.error("Failed to build Adaptive Card: {}", e.getMessage());
            return "{}";
        }
    }

    /** Bold header ColumnSet row. */
    private Map<String, Object> headerRow() {
        Map<String, Object> row = columnSet(List.of(
            col("auto",    boldBlock("Ticket")),
            col("stretch", boldBlock("Summary")),
            col("auto",    boldBlock("Time to Resolution")),
            col("auto",    boldBlock("Current Assignee")),
            col("auto",    boldBlock("Reassigned To"))
        ));
        row.put("separator", true);
        row.put("spacing",   "Medium");
        return row;
    }

    /** Data ColumnSet row for one ticket. */
    private Map<String, Object> dataRow(Map<String, String> t) {
        String key          = t.getOrDefault("key",             "");
        String summary      = t.getOrDefault("summary",         "");
        String sla          = t.getOrDefault("slaRemaining",    "");
        String fromEmail    = t.getOrDefault("currentAssignee", "");
        String toEmail      = t.getOrDefault("reassignedTo",    "");
        String url          = t.getOrDefault("url",             "");

        // Clickable ticket link using Adaptive Card markdown
        String linkText = url.isEmpty() ? key : "[" + key + "](" + url + ")";

        return columnSet(List.of(
            col("auto",    linkBlock(linkText)),             // Ticket ID — clickable
            col("stretch", wrapBlock(summary)),             // Full summary, wraps freely
            col("auto",    slaBlock(sla)),                  // SLA remaining / breached
            col("auto",    subtleBlock(fromEmail)),         // Current assignee
            col("auto",    subtleBlock(toEmail))            // Reassigned to
        ));
    }

    // -----------------------------------------------------------------------
    // Column / cell helpers
    // -----------------------------------------------------------------------

    private static Map<String, Object> columnSet(List<Map<String, Object>> columns) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type",    "ColumnSet");
        m.put("columns", columns);
        return m;
    }

    private static Map<String, Object> col(String width, Map<String, Object> item) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type",  "Column");
        m.put("width", width);
        m.put("items", List.of(item));
        return m;
    }

    /** Bold TextBlock — used for header cells. */
    private static Map<String, Object> boldBlock(String text) {
        return map("type","TextBlock","text",text,"weight","Bolder","wrap",false,"size","Small");
    }

    /** Accent-coloured TextBlock for clickable ticket link (markdown). */
    private static Map<String, Object> linkBlock(String text) {
        return map("type","TextBlock","text",text,"color","Accent","wrap",false,"size","Small");
    }

    /** Full-width wrapping TextBlock — used for summary so nothing is cut. */
    private static Map<String, Object> wrapBlock(String text) {
        return map("type","TextBlock","text", text.isEmpty() ? "—" : text,
                   "wrap", true, "size", "Small");
    }

    /** SLA TextBlock: red/warning colour when breached, default otherwise. */
    private static Map<String, Object> slaBlock(String text) {
        if (text == null || text.isBlank()) {
            return map("type","TextBlock","text","—","isSubtle",true,"wrap",false,"size","Small");
        }
        boolean breached = text.contains("Breached") || text.contains("breached");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type",     "TextBlock");
        m.put("text",     text);
        m.put("color",    breached ? "Attention" : "Good");
        m.put("wrap",     false);
        m.put("size",     "Small");
        return m;
    }

    /** Subtle (grey) TextBlock — used for email addresses. */
    private static Map<String, Object> subtleBlock(String text) {
        return map("type","TextBlock","text", text.isEmpty() ? "—" : text,
                   "isSubtle",true,"wrap",false,"size","Small");
    }

    // -----------------------------------------------------------------------
    // Misc helpers
    // -----------------------------------------------------------------------

    private static Map<String, Object> fact(String title, String value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", title);
        m.put("value", value);
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2)
            m.put(kv[i].toString(), kv[i + 1]);
        return m;
    }

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

    // -----------------------------------------------------------------------
    // Network
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
                    log.info("[{}] Teams card sent ({}): HTTP {}", teamId, label, resp.statusCode());
                else
                    log.warn("[{}] Teams card ({}) HTTP {}: {}", teamId, label, resp.statusCode(), resp.body());
            } catch (Exception e) {
                log.warn("[{}] Teams card ({}) failed: {}", teamId, label, e.getMessage());
            }
        });
    }
}
