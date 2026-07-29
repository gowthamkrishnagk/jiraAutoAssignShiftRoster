package com.jira.autoassign.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.jira.autoassign.client.JiraClient;
import com.jira.autoassign.entity.BreachComment;
import com.jira.autoassign.entity.Team;
import com.jira.autoassign.repository.BreachCommentRepository;
import com.jira.autoassign.repository.TeamRepository;
import com.jira.autoassign.service.JiraConfigService;
import com.jira.autoassign.service.SlaDailyReportService;
import com.jira.autoassign.service.SlaGroupingService;
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
    private final SlaGroupingService      grouping;
    private final SlaDailyReportService   dailyReportService;

    public SlaController(JiraClient jiraClient,
                         TeamRepository teamRepository,
                         JiraConfigService configService,
                         BreachCommentRepository commentRepo,
                         SlaGroupingService grouping,
                         SlaDailyReportService dailyReportService) {
        this.jiraClient         = jiraClient;
        this.teamRepository     = teamRepository;
        this.configService      = configService;
        this.commentRepo        = commentRepo;
        this.grouping           = grouping;
        this.dailyReportService = dailyReportService;
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
        // 45 days, so the DB only holds recent ones. We clamp the range to the FIRST
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

        // Single ranged query, then bucket each ticket to a day by its `updated` date
        // (one Jira search for the whole range instead of one search per day).
        List<JsonNode> rangeResolved =
            jiraClient.getResolvedSlaTicketsInRange(t.getJql(), fieldId, effFrom, effTo);

        // Pre-seed every day in the range so empty days still get a (header-only) sheet.
        Map<String, List<JsonNode>> byDay = new LinkedHashMap<>();
        for (LocalDate d = effFrom; !d.isAfter(effTo); d = d.plusDays(1))
            byDay.put(d.toString(), new ArrayList<>());

        for (JsonNode tk : rangeResolved) {
            String dayKey = updatedDateKey(tk.path("fields").path("updated").asText(""));
            if (dayKey != null && byDay.containsKey(dayKey)) byDay.get(dayKey).add(tk);
        }

        List<Map<String, Object>> dayBlocks = new ArrayList<>();
        for (Map.Entry<String, List<JsonNode>> e : byDay.entrySet()) {
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("date",     e.getKey());
            block.put("resolved", groupByBreachOwner(e.getValue(), fieldId, sevKey, false));
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
    // Daily breach-reason report email
    // -----------------------------------------------------------------------

    /**
     * Current settings + SMTP status for the Admin card.
     * GET /api/sla/daily-report/settings
     */
    @GetMapping("/sla/daily-report/settings")
    public ResponseEntity<?> getDailyReportSettings() {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("recipients",     configService.getSlaReportRecipients());
        resp.put("recipientList",  configService.getSlaReportRecipientList());
        resp.put("enabled",        configService.isSlaReportEnabled());
        resp.put("smtpConfigured", dailyReportService.isMailConfigured());
        resp.put("smtpHost",       dailyReportService.smtpHost());
        resp.put("smtpSource",     dailyReportService.smtpSource());
        resp.put("fromAddress",    dailyReportService.effectiveFrom());
        resp.put("reportDate",     dailyReportService.defaultResolvedDate().toString());
        return ResponseEntity.ok(resp);
    }

    /**
     * Mail-server settings for the Admin form. The password is never returned —
     * only whether one is stored.
     *
     * GET /api/sla/daily-report/smtp
     */
    @GetMapping("/sla/daily-report/smtp")
    public ResponseEntity<?> getSmtpSettings() {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("host",        configService.getSmtpHost());
        resp.put("port",        configService.getSmtpPort() == null ? 587 : configService.getSmtpPort());
        resp.put("username",    configService.getSmtpUsername());
        resp.put("passwordSet", !configService.getSmtpPassword().isBlank());
        resp.put("auth",        configService.isSmtpAuth());
        resp.put("startTls",    configService.isSmtpStartTls());
        resp.put("from",        configService.getSlaReportFrom());
        resp.put("fromName",    configService.getSlaReportFromName());
        resp.put("configured",  dailyReportService.isMailConfigured());
        resp.put("source",      dailyReportService.smtpSource());
        resp.put("activeHost",  dailyReportService.smtpHost());
        resp.put("activeFrom",  dailyReportService.effectiveFrom());
        return ResponseEntity.ok(resp);
    }

    /**
     * Saves the mail server. Takes effect on the next send — no restart needed.
     * An omitted/blank password keeps the stored one.
     *
     * POST /api/sla/daily-report/smtp
     * Body: { host, port, username, password, auth, startTls, from, fromName }
     */
    @PostMapping("/sla/daily-report/smtp")
    public ResponseEntity<?> saveSmtpSettings(@RequestBody Map<String, Object> body) {
        String host = str(body.get("host"));
        if (!host.isBlank() && str(body.get("from")).isBlank() && str(body.get("username")).isBlank())
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Set a username or a From address — the mail needs a sender."));

        Integer port = null;
        Object rawPort = body.get("port");
        if (rawPort != null && !rawPort.toString().isBlank()) {
            try {
                port = Integer.parseInt(rawPort.toString().trim());
            } catch (NumberFormatException e) {
                return ResponseEntity.badRequest().body(Map.of("error", "Port must be a number."));
            }
            if (port < 1 || port > 65535)
                return ResponseEntity.badRequest().body(Map.of("error", "Port must be 1–65535."));
        }

        configService.saveSmtpSettings(host, port,
            str(body.get("username")), str(body.get("password")),
            !(body.get("auth")     instanceof Boolean a) || a,
            !(body.get("startTls") instanceof Boolean s) || s,
            str(body.get("from")), str(body.get("fromName")));

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("saved",      true);
        resp.put("configured", dailyReportService.isMailConfigured());
        resp.put("source",     dailyReportService.smtpSource());
        resp.put("activeHost", dailyReportService.smtpHost());
        resp.put("activeFrom", dailyReportService.effectiveFrom());
        return ResponseEntity.ok(resp);
    }

    /**
     * Sends a one-line test email with the current settings — no Jira work involved,
     * so a failure here points squarely at the mail server.
     *
     * POST /api/sla/daily-report/smtp/test
     * Body: { "to": "me@x.com" }   (optional — defaults to the first saved recipient)
     */
    @PostMapping("/sla/daily-report/smtp/test")
    public ResponseEntity<?> testSmtp(@RequestBody(required = false) Map<String, Object> body) {
        String to = body == null ? "" : str(body.get("to"));
        SlaDailyReportService.SendResult r = dailyReportService.sendTest(to);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("sent",       r.sent());
        resp.put("message",    r.message());
        resp.put("recipients", r.recipients());
        return r.sent() ? ResponseEntity.ok(resp) : ResponseEntity.status(502).body(resp);
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString().trim();
    }

    /**
     * Saves recipients + on/off for the scheduled send.
     * POST /api/sla/daily-report/settings
     * Body: { "recipients": "a@x.com, b@x.com", "enabled": true }
     */
    @PostMapping("/sla/daily-report/settings")
    public ResponseEntity<?> saveDailyReportSettings(@RequestBody Map<String, Object> body) {
        String recipients = body.get("recipients") == null ? "" : body.get("recipients").toString();
        boolean enabled   = !(body.get("enabled") instanceof Boolean b) || b;

        configService.saveSlaReportSettings(recipients, enabled);
        return ResponseEntity.ok(Map.of(
            "saved",         true,
            "recipientList", configService.getSlaReportRecipientList(),
            "enabled",       configService.isSlaReportEnabled()));
    }

    /**
     * Builds the pivot without emailing it — used by the Admin "Preview" action.
     * GET /api/sla/daily-report/preview?date=YYYY-MM-DD  (date optional, defaults to yesterday IST)
     */
    @GetMapping("/sla/daily-report/preview")
    public ResponseEntity<?> previewDailyReport(@RequestParam(required = false) String date) {
        LocalDate day;
        try {
            day = (date == null || date.isBlank()) ? null : LocalDate.parse(date);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid date — use YYYY-MM-DD."));
        }
        try {
            SlaDailyReportService.Report report = dailyReportService.build(day);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("report", report);
            resp.put("html",   dailyReportService.renderHtml(report));
            return ResponseEntity.ok(resp);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Sends the report now — the Admin "Send now" button.
     * POST /api/sla/daily-report/send
     * Body (all optional): { "date": "YYYY-MM-DD", "to": "me@x.com" }
     *
     * Ignores the enabled flag: an explicit click always sends.
     */
    @PostMapping("/sla/daily-report/send")
    public ResponseEntity<?> sendDailyReport(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;

        LocalDate day;
        String dateStr = b.get("date") == null ? "" : b.get("date").toString().trim();
        try {
            day = dateStr.isBlank() ? null : LocalDate.parse(dateStr);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid date — use YYYY-MM-DD."));
        }

        List<String> to = new ArrayList<>();
        String toStr = b.get("to") == null ? "" : b.get("to").toString();
        for (String s : toStr.split("[,;\\s]+"))
            if (s.contains("@")) to.add(s.trim());

        try {
            SlaDailyReportService.SendResult r = dailyReportService.send(day, to);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("sent",       r.sent());
            resp.put("message",    r.message());
            resp.put("recipients", r.recipients());
            if (r.report() != null) {
                resp.put("pendingTotal",  r.report().grandTotal());
                resp.put("breachedTotal", r.report().grandBreachedTotal());
                resp.put("reportDate",    r.report().resolvedDate());
            }
            resp.put("attachment", r.attachment() == null ? "" : r.attachment());
            return r.sent() ? ResponseEntity.ok(resp)
                            : ResponseEntity.status(502).body(resp);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("sent", false, "message", e.getMessage()));
        }
    }

    /**
     * Downloads exactly the workbook that gets attached to the report email — lets an
     * admin eyeball the sheet without waiting for 07:30.
     *
     * GET /api/sla/daily-report/sheet?date=YYYY-MM-DD
     */
    @GetMapping("/sla/daily-report/sheet")
    public ResponseEntity<?> downloadDailyReportSheet(@RequestParam(required = false) String date) {
        LocalDate day;
        try {
            day = (date == null || date.isBlank()) ? null : LocalDate.parse(date);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid date — use YYYY-MM-DD."));
        }
        try {
            SlaDailyReportService.Report report = dailyReportService.build(day);
            byte[] xlsx = dailyReportService.buildWorkbook(report);
            if (xlsx == null)
                return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Could not generate the workbook — see server logs."));

            String fileName = "SLA_Tracker_" + report.resolvedDate() + ".xlsx";
            return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"" + fileName + "\"")
                .header("Content-Type",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .body(xlsx);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // -----------------------------------------------------------------------
    // Grouping helper — delegates to SlaGroupingService so the SLA tab, the Excel
    // report and the daily report email all attribute breaches identically.
    // -----------------------------------------------------------------------

    private List<Map<String, Object>> groupByBreachOwner(
            List<JsonNode> tickets, String fieldId, String sevKey, boolean doAttribution) {
        return grouping.groupByBreachOwner(tickets, fieldId, sevKey, doAttribution);
    }

    /**
     * Maps a Jira `updated` timestamp (e.g. "2026-06-01T14:23:45.000+0530") to its
     * calendar-day key "YYYY-MM-DD". The leading 10 chars are the date in Jira's own
     * reported offset — the same basis Jira uses for the `updated >= "date"` range
     * filter — so tickets bucket to the same day Jira matched them on.
     */
    private static String updatedDateKey(String updatedIso) {
        if (updatedIso == null || updatedIso.length() < 10) return null;
        String day = updatedIso.substring(0, 10);
        return day.matches("\\d{4}-\\d{2}-\\d{2}") ? day : null;
    }
}
