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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Posts a Microsoft Teams Adaptive Card to an incoming webhook URL whenever a
 * ticket is reassigned during the off-shift sweep.
 *
 * Teams incoming webhook payload (Adaptive Card):
 * POST <webhook-url>
 * Content-Type: application/json
 * {
 *   "type": "message",
 *   "attachments": [{
 *     "contentType": "application/vnd.microsoft.card.adaptive",
 *     "content": { ...adaptive card... }
 *   }]
 * }
 *
 * Card fields: Ticket · Summary · From (leaving) · To (taking over) · Team · Time
 *
 * The webhook URL is stored in DB (single-row jira_config table) via JiraConfigService
 * and can be changed at runtime — no restart required.
 *
 * All network calls are fire-and-forget on a virtual thread so the assignment
 * loop is never blocked by a slow or unreachable Teams endpoint.
 */
@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final JiraConfigService jiraConfigService;
    private final ObjectMapper      mapper = new ObjectMapper();

    // Single shared client — thread-safe, keeps connection pool alive
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    public WebhookService(JiraConfigService jiraConfigService) {
        this.jiraConfigService = jiraConfigService;
    }

    // -----------------------------------------------------------------------
    // Called by ShiftAssignService after a successful off-shift reassignment
    // -----------------------------------------------------------------------

    /**
     * Fire-and-forget: posts an Adaptive Card to the configured Teams webhook.
     * No-op if no URL is saved.
     *
     * @param teamId    team identifier (e.g. "orderfallout")
     * @param teamName  human-readable team name for the card
     * @param issueKey  Jira key  e.g. "SAC-123"
     * @param summary   ticket title
     * @param fromEmail person who was on shift (now leaving)
     * @param toEmail   person now taking over
     */
    public void fireReassignment(String teamId, String teamName, String issueKey,
                                  String summary, String fromEmail, String toEmail) {
        String url = jiraConfigService.getWebhookUrl();
        if (url == null || url.isBlank()) return;

        String body = buildCard(issueKey, summary, fromEmail, toEmail,
                                teamName != null ? teamName : teamId,
                                LocalDateTime.now().format(FMT));

        sendAsync(url, body, teamId, issueKey);
    }

    // -----------------------------------------------------------------------
    // Test — called from /api/webhook-settings/test endpoint
    // -----------------------------------------------------------------------

    /**
     * Sends a test Adaptive Card to {@code url} (synchronous, used by UI "Test" button).
     * @return HTTP response status code, or -1 on connection failure / timeout
     */
    public int testWebhook(String url) {
        if (url == null || url.isBlank()) return -1;

        String body = buildCard(
            "TEST-001",
            "Webhook test from Shift Roster Manager",
            "prev.person@company.com",
            "next.person@company.com",
            "Test Team",
            LocalDateTime.now().format(FMT)
        );

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
    // Adaptive Card builder
    // -----------------------------------------------------------------------

    /**
     * Builds the Teams incoming-webhook JSON envelope that wraps an Adaptive Card.
     *
     * Schema reference:
     *   https://adaptivecards.io/schemas/adaptive-card.json  (version 1.4)
     *   Teams envelope: { "type": "message", "attachments": [ { "contentType": "application/vnd.microsoft.card.adaptive", ... } ] }
     */
    private String buildCard(String issueKey, String summary,
                              String fromEmail, String toEmail,
                              String teamName, String timestamp) {
        try {
            // ── Adaptive Card body ────────────────────────────────────────
            Map<String, Object> heading = map(
                "type",    "TextBlock",
                "text",    "🔄 Ticket Reassigned — Shift Handover",
                "weight",  "Bolder",
                "size",    "Medium",
                "color",   "Accent",
                "wrap",    true
            );

            Map<String, Object> subtitle = map(
                "type",     "TextBlock",
                "text",     "A ticket was moved to the active shift assignee.",
                "isSubtle", true,
                "size",     "Small",
                "spacing",  "None",
                "wrap",     true
            );

            // Divider
            Map<String, Object> divider = map("type", "Separator");

            // Fact set
            Map<String, Object> facts = new LinkedHashMap<>();
            facts.put("type", "FactSet");
            facts.put("spacing", "Medium");
            facts.put("facts", List.of(
                fact("🎫 Ticket",   issueKey  != null ? issueKey  : "—"),
                fact("📝 Summary",  summary   != null ? summary   : "—"),
                fact("👤 From",     fromEmail != null ? fromEmail : "—"),
                fact("➡️ To",      toEmail   != null ? toEmail   : "—"),
                fact("🏷️ Team",    teamName  != null ? teamName  : "—"),
                fact("🕐 Time",     timestamp != null ? timestamp : "—")
            ));

            // Adaptive Card content
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("$schema", "http://adaptivecards.io/schemas/adaptive-card.json");
            card.put("type",    "AdaptiveCard");
            card.put("version", "1.4");
            card.put("body",    List.of(heading, subtitle, divider, facts));
            card.put("msteams", Map.of("width", "Full"));

            // Teams envelope
            Map<String, Object> attachment = new LinkedHashMap<>();
            attachment.put("contentType", "application/vnd.microsoft.card.adaptive");
            attachment.put("contentUrl",  null);
            attachment.put("content",     card);

            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("type",        "message");
            envelope.put("attachments", List.of(attachment));

            return mapper.writeValueAsString(envelope);
        } catch (Exception e) {
            log.error("Failed to build Adaptive Card JSON: {}", e.getMessage());
            return "{}";
        }
    }

    // -----------------------------------------------------------------------
    // Internal — fire-and-forget on a virtual thread
    // -----------------------------------------------------------------------

    private void sendAsync(String url, String body, String teamId, String issueKey) {
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

                if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                    log.info("[{}] Teams card sent for [{}]: HTTP {}",
                             teamId, issueKey, resp.statusCode());
                } else {
                    log.warn("[{}] Teams card for [{}] returned HTTP {}: {}",
                             teamId, issueKey, resp.statusCode(), resp.body());
                }
            } catch (Exception e) {
                log.warn("[{}] Teams card failed for [{}]: {}",
                         teamId, issueKey, e.getMessage());
            }
        });
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static Map<String, Object> fact(String title, String value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", title);
        m.put("value", value);
        return m;
    }

    /** Varargs helper: map("k1","v1","k2","v2",...) */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length - 1; i += 2)
            m.put(kv[i].toString(), kv[i + 1]);
        return m;
    }
}
