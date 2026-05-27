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
import java.util.stream.Collectors;

/**
 * Fires a webhook notification (batched per off-shift person) whenever tickets
 * are reassigned during the shift-handover sweep.
 *
 * Payload sent (ONE call per scheduler cycle — all off-shift people's tickets together):
 * {
 *   "teamName":    "Order Fallout",
 *   "timestamp":   "2026-05-27 14:30",
 *   "ticketCount": 5,
 *   "tickets": [
 *     { "key": "SAC-123", "summary": "...",
 *       "slaRemaining":    "2h 5m remaining",   // "" if SLA not configured / already breached
 *       "currentAssignee": "john.doe@co.com",
 *       "reassignedTo":    "alice@co.com",
 *       "url": "https://lla.atlassian.net/browse/SAC-123" },
 *     ...
 *   ]
 * }
 *
 * In Power Automate use a Compose step to build the Adaptive Card table —
 * columns: Ticket (clickable) | Summary | Time to Resolution | Current Assignee | Reassigned To.
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
    // Called by ShiftAssignService — ONE call per scheduler cycle with ALL
    // reassignments across every off-shift person.
    // tickets = list of { key, summary, currentAssignee, reassignedTo }
    // URL is added here from the Jira base URL.
    // -----------------------------------------------------------------------

    public void fireReassignments(String teamId, String teamName,
                                   List<Map<String, String>> tickets) {
        String webhookUrl = jiraConfigService.getWebhookUrl();
        if (webhookUrl == null || webhookUrl.isBlank()) return;
        if (tickets == null || tickets.isEmpty()) return;

        String jiraBase = jiraConfigService.getUrl(); // e.g. https://lla.atlassian.net

        // Enrich each ticket with its browse URL
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

        try {
            String body = mapper.writeValueAsString(payload);
            sendAsync(webhookUrl, body, teamId, "batch[" + enriched.size() + " tickets]");
        } catch (Exception e) {
            log.error("[{}] Failed to build webhook payload: {}", teamId, e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Test — sends 3 sample tickets; returns HTTP status or -1 on failure
    // -----------------------------------------------------------------------

    public int testWebhook(String url) {
        if (url == null || url.isBlank()) return -1;

        String jiraBase = jiraConfigService.getUrl();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("teamName",    "Order Fallout");
        payload.put("timestamp",   LocalDateTime.now().format(FMT));
        payload.put("ticketCount", 4);
        payload.put("tickets", List.of(
            ticket("SAC-001", "Order activation failure — Prod customer",
                   "1h 45m remaining",  "john.doe@company.com",   "alice@company.com",  jiraBase + "/browse/SAC-001"),
            ticket("SAC-002", "Network provisioning issue — B2B",
                   "⚠ Breached",        "john.doe@company.com",   "alice@company.com",  jiraBase + "/browse/SAC-002"),
            ticket("SAC-003", "SIM swap stuck in queue — urgent",
                   "3h 20m remaining",  "jane.smith@company.com", "bob@company.com",    jiraBase + "/browse/SAC-003"),
            ticket("SAC-004", "B2C order failed — Nokia Escalation",
                   "",                  "jane.smith@company.com", "alice@company.com",  jiraBase + "/browse/SAC-004")
        ));

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
    // Internal
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

                if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                    log.info("[{}] Webhook sent ({}): HTTP {}", teamId, label, resp.statusCode());
                } else {
                    log.warn("[{}] Webhook ({}) returned HTTP {}: {}",
                             teamId, label, resp.statusCode(), resp.body());
                }
            } catch (Exception e) {
                log.warn("[{}] Webhook ({}) failed: {}", teamId, label, e.getMessage());
            }
        });
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
}
