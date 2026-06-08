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
    private static final DateTimeFormatter FMT             = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final int               TICKETS_PER_BATCH = 10;   // ~25 KB per card, well under 28 KB limit
    private static final int               BATCH_DELAY_MS    = 2_000; // 2 s gap between batches (Teams rate limit)

    private final JiraConfigService jiraConfigService;
    private final ObjectMapper      mapper = new ObjectMapper();

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    public WebhookService(JiraConfigService jiraConfigService) {
        this.jiraConfigService = jiraConfigService;
    }

    // -----------------------------------------------------------------------
    // Called by ShiftAssignService — ONE call per scheduler cycle.
    // Splits into batches of TICKETS_PER_BATCH so each card stays well
    // under the 28 KB Teams Adaptive Card limit.
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

        // Split into fixed-size batches
        List<List<Map<String, String>>> batches = partition(enriched, TICKETS_PER_BATCH);
        int totalBatches = batches.size();

        log.info("[{}] Sending {} ticket(s) across {} webhook batch(es)", teamId, enriched.size(), totalBatches);

        for (int b = 0; b < totalBatches; b++) {
            final int batchNum   = b + 1;
            final int delayMs    = b * BATCH_DELAY_MS;           // stagger: 0s, 2s, 4s …
            final List<Map<String, String>> batch = batches.get(b);

            try {
                String cardJson = mapper.writeValueAsString(
                    buildAdaptiveCard(teamId, teamName, batch, batchNum, totalBatches));

                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("teamName",     teamName != null ? teamName : teamId);
                payload.put("timestamp",    LocalDateTime.now().format(FMT));
                payload.put("ticketCount",  batch.size());
                payload.put("batchNumber",  batchNum);
                payload.put("totalBatches", totalBatches);
                payload.put("tickets",      batch);
                payload.put("adaptiveCard", cardJson);

                String body  = mapper.writeValueAsString(payload);
                String label = totalBatches == 1
                    ? "batch[" + batch.size() + " tickets]"
                    : "batch[" + batchNum + "/" + totalBatches + ", " + batch.size() + " tickets]";

                sendAsyncDelayed(webhookUrl, body, teamId, label, delayMs);

            } catch (Exception e) {
                log.error("[{}] Failed to build payload for batch {}/{}: {}",
                    teamId, batchNum, totalBatches, e.getMessage());
            }
        }
    }

    /** Partition a list into sublists of at most {@code size} elements. */
    private static <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size)
            result.add(list.subList(i, Math.min(i + size, list.size())));
        return result;
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

        try {
            String cardJson = mapper.writeValueAsString(buildAdaptiveCard("test", "Order Fallout", testTickets));

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("teamName",    "Order Fallout");
            payload.put("timestamp",   LocalDateTime.now().format(FMT));
            payload.put("ticketCount", testTickets.size());
            payload.put("tickets",     testTickets);
            payload.put("adaptiveCard", cardJson);   // ← plain JSON string, not object

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
    // B2B single-ticket @mention card → separate B2B webhook URL
    // -----------------------------------------------------------------------

    /**
     * Builds a single-ticket Adaptive Card with an optional Teams @mention and POSTs
     * it to the configured B2B webhook URL. Used for all three B2B notifications
     * (reassignment, Matrixx/Aria support needed, SLA breach warning).
     *
     * @param title       card banner title
     * @param mentionText body line, may contain "&lt;at&gt;Name&lt;/at&gt;" if a mapped member exists
     * @param teamsEmail  Teams id/UPN for the @mention; blank/null → no mention entity
     * @param teamsName   Teams display name for the @mention
     * @param ticket      map with key, url, summary, context, sla
     */
    public void fireB2bCard(String title, String mentionText,
                            String teamsEmail, String teamsName,
                            Map<String, String> ticket) {
        String webhookUrl = jiraConfigService.getB2bWebhookUrl();
        if (webhookUrl == null || webhookUrl.isBlank()) {
            log.warn("[B2B] No B2B webhook URL configured — skipping notification for {}",
                ticket.get("key"));
            return;
        }
        try {
            String cardJson = mapper.writeValueAsString(
                buildB2bCard(title, mentionText, teamsEmail, teamsName, ticket));

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("timestamp",         LocalDateTime.now().format(FMT));
            payload.put("ticketKey",         ticket.getOrDefault("key", ""));
            payload.put("mentionEmail",      teamsEmail != null ? teamsEmail : "");
            payload.put("mentionName",       teamsName  != null ? teamsName  : "");
            // hasMapping=true → an explicit B2B mapping exists; the flow should mention
            // mentionEmail directly and SKIP the group auto-match. false → auto-match by
            // assigneeNameKey against the Teams group members.
            payload.put("hasMapping",        teamsEmail != null && !teamsEmail.isBlank());
            // Trimmed Jira identity for flow-side matching against Teams group members.
            payload.put("assigneeJiraEmail", ticket.getOrDefault("assigneeJiraEmail", ""));
            payload.put("assigneeNameKey",   ticket.getOrDefault("assigneeNameKey", ""));
            payload.put("ticket",            ticket);
            payload.put("adaptiveCard",      cardJson);

            String body = mapper.writeValueAsString(payload);
            sendAsyncDelayed(webhookUrl, body, "b2b", ticket.getOrDefault("key", "") + " — " + title, 0);
        } catch (Exception e) {
            log.error("[B2B] Failed to build/send card for {}: {}", ticket.get("key"), e.getMessage());
        }
    }

    /** Test the B2B webhook with a single sample @mention card. */
    public int testB2bWebhook(String url) {
        if (url == null || url.isBlank()) return -1;
        String jiraBase = jiraConfigService.getUrl();
        Map<String, String> sample = new LinkedHashMap<>();
        sample.put("key",     "SAC-9999");
        sample.put("url",     jiraBase + "/browse/SAC-9999");
        sample.put("summary", "B2B test ticket — verifying the B2B webhook and @mention rendering");
        sample.put("context", "Assignee: Test User");
        sample.put("sla",     "1h 30m remaining");
        sample.put("assigneeJiraEmail", "testUser@libertypr.com");
        sample.put("assigneeNameKey",   "testuser");
        try {
            String cardJson = mapper.writeValueAsString(buildB2bCard(
                "🔔 B2B Webhook Test",
                "<at>Test User</at> — this is a B2B test message.",
                "test.user@example.com", "Test User", sample));

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("timestamp",         LocalDateTime.now().format(FMT));
            payload.put("ticketKey",         sample.get("key"));
            payload.put("mentionEmail",      "test.user@example.com");
            payload.put("mentionName",       "Test User");
            payload.put("hasMapping",        true);
            payload.put("assigneeJiraEmail", sample.get("assigneeJiraEmail"));
            payload.put("assigneeNameKey",   sample.get("assigneeNameKey"));
            payload.put("ticket",            sample);
            payload.put("adaptiveCard",      cardJson);

            String reqBody = mapper.writeValueAsString(payload);
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(reqBody))
                .timeout(Duration.ofSeconds(15))
                .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            log.info("[b2b-webhook-test] POST {} → HTTP {}", url, resp.statusCode());
            return resp.statusCode();
        } catch (Exception e) {
            log.warn("[b2b-webhook-test] Failed: {}", e.getMessage());
            return -1;
        }
    }

    private Map<String, Object> buildB2bCard(String title, String mentionText,
                                             String teamsEmail, String teamsName,
                                             Map<String, String> ticket) {
        String key     = ticket.getOrDefault("key",     "");
        String url     = ticket.getOrDefault("url",     "");
        String summary = ticket.getOrDefault("summary", "");
        String context = ticket.getOrDefault("context", "");
        String sla     = ticket.getOrDefault("sla",     "");
        boolean mention = teamsEmail != null && !teamsEmail.isBlank();

        List<Map<String, Object>> body = new ArrayList<>();

        // Banner
        Map<String, Object> banner = new LinkedHashMap<>();
        banner.put("type",  "Container");
        banner.put("style", "emphasis");
        banner.put("bleed", true);
        banner.put("items", List.of(
            tb(title, "Bolder", "Accent", "Large", true),
            tb("🕐 " + LocalDateTime.now().format(FMT), null, "Accent", "Small", false)
        ));
        body.add(banner);

        // Ticket detail
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(tb("[" + key + "](" + url + ")  ↗", "Bolder", "Accent", "Medium", false));
        if (!summary.isBlank()) {
            Map<String, Object> summaryText = tb(summary, null, null, "Small", true);
            summaryText.put("isSubtle", true);
            items.add(summaryText);
        }
        if (!context.isBlank()) items.add(tb(context, "Bolder", null, "Small", true));
        if (!sla.isBlank()) {
            String slaColor = sla.contains("Breach") ? "Attention" : "Good";
            String slaIcon  = sla.contains("Breach") ? "🔴" : "🟢";
            items.add(tb("SLA:  " + slaIcon + "  " + sla, "Bolder", slaColor, "Small", false));
        }
        Map<String, Object> mentionBlock = tb(mentionText, "Bolder", null, "Default", true);
        mentionBlock.put("spacing", "Medium");
        items.add(mentionBlock);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("type",      "Container");
        detail.put("separator", true);
        detail.put("spacing",   "Medium");
        detail.put("items",     items);
        body.add(detail);

        // Root
        Map<String, Object> adaptiveCard = new LinkedHashMap<>();
        adaptiveCard.put("type",    "AdaptiveCard");
        adaptiveCard.put("$schema", "http://adaptivecards.io/schemas/adaptive-card.json");
        adaptiveCard.put("version", "1.5");

        Map<String, Object> msteams = new LinkedHashMap<>();
        msteams.put("width", "Full");
        if (mention) {
            Map<String, Object> mentioned = new LinkedHashMap<>();
            mentioned.put("id",   teamsEmail);
            mentioned.put("name", teamsName);
            Map<String, Object> entity = new LinkedHashMap<>();
            entity.put("type",      "mention");
            entity.put("text",      "<at>" + teamsName + "</at>");
            entity.put("mentioned", mentioned);
            msteams.put("entities", List.of(entity));
        }
        adaptiveCard.put("msteams", msteams);
        adaptiveCard.put("body",    body);
        return adaptiveCard;
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

    /** Convenience overload — single batch (test calls). */
    private Map<String, Object> buildAdaptiveCard(String teamId, String teamName,
                                                   List<Map<String, String>> tickets) {
        return buildAdaptiveCard(teamId, teamName, tickets, 1, 1);
    }

    private Map<String, Object> buildAdaptiveCard(String teamId, String teamName,
                                                   List<Map<String, String>> tickets,
                                                   int batchNum, int totalBatches) {
        String displayName = (teamName != null && !teamName.isBlank()) ? teamName : teamId;
        String timestamp   = LocalDateTime.now().format(FMT);

        // Title line — show batch info only when there are multiple batches
        String titleText = totalBatches == 1
            ? "🔄  Shift Handover — " + tickets.size() + " Ticket(s) Reassigned"
            : "🔄  Shift Handover — Part " + batchNum + " of " + totalBatches
              + "  (" + tickets.size() + " tickets)";

        List<Map<String, Object>> body = new ArrayList<>();

        // ── Banner ────────────────────────────────────────────────────────────
        Map<String, Object> banner = new LinkedHashMap<>();
        banner.put("type",  "Container");
        banner.put("style", "emphasis");
        banner.put("bleed", true);
        banner.put("items", List.of(
            tb(titleText, "Bolder", "Accent", "Large", true),
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

    /**
     * Fire-and-forget HTTP POST on a virtual thread.
     * delayMs > 0 staggers batches so Teams doesn't rate-limit back-to-back cards.
     */
    private void sendAsyncDelayed(String url, String body, String teamId, String label, int delayMs) {
        Thread.ofVirtual().start(() -> {
            try {
                if (delayMs > 0) {
                    log.info("[{}] Webhook ({}) — waiting {}ms before send", teamId, label, delayMs);
                    Thread.sleep(delayMs);
                }
                HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(15))
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
