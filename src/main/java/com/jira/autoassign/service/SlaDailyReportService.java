package com.jira.autoassign.service;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.jira.autoassign.client.JiraClient;
import com.jira.autoassign.entity.BreachComment;
import com.jira.autoassign.entity.Team;
import com.jira.autoassign.repository.BreachCommentRepository;
import com.jira.autoassign.repository.TeamRepository;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Daily "breach reasons still missing" report.
 *
 * The pivot answers one question: for every SLA-breached ticket whose Breach Reason
 * is still EMPTY (no reason logged/commented), who owns it and how many do they have?
 *
 *   Assignee              Open   Resolved   Total
 *   Suji Mahindran           4          2       6
 *   Ram Kumar                1          3       4
 *   Unassigned               2          0       2
 *   ────────────────────────────────────────────
 *   Total                    7          5      12
 *
 * "Reason is empty" = the ticket key has no row in {@code breach_comment}, i.e. nobody
 * used the SLA Tracker's Breach Reason dropdown on it yet.
 *
 * Scope per run:
 *   - Open breached   → live snapshot, attributed to whoever held the ticket at breach time.
 *   - Resolved breached → the reported calendar day only, by current assignee.
 *
 * Covers every configured team, one pivot section each, in a single email. The same
 * day's full SLA Tracker sheet is attached as .xlsx (see {@link SlaReportWorkbookService}).
 */
@Service
public class SlaDailyReportService {

    private static final Logger log = LoggerFactory.getLogger(SlaDailyReportService.class);

    private static final DateTimeFormatter SENT_FMT =
        DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm");

    private final JiraClient              jiraClient;
    private final TeamRepository          teamRepository;
    private final BreachCommentRepository commentRepo;
    private final JiraConfigService       configService;
    private final SlaGroupingService      grouping;
    private final SlaReportWorkbookService workbookService;
    /** Optional — absent/unusable when SMTP_HOST is not set. */
    private final ObjectProvider<JavaMailSender> mailSenderProvider;

    @Value("${spring.mail.host:}")     private String smtpHost;
    @Value("${spring.mail.username:}") private String smtpUser;
    @Value("${sla.report.from:}")      private String fromAddress;
    @Value("${sla.report.from-name:SLA Tracker}") private String fromName;
    @Value("${sla.report.zone:Asia/Kolkata}")     private String zoneId;

    public SlaDailyReportService(JiraClient jiraClient,
                                 TeamRepository teamRepository,
                                 BreachCommentRepository commentRepo,
                                 JiraConfigService configService,
                                 SlaGroupingService grouping,
                                 SlaReportWorkbookService workbookService,
                                 ObjectProvider<JavaMailSender> mailSenderProvider) {
        this.jiraClient         = jiraClient;
        this.teamRepository     = teamRepository;
        this.commentRepo        = commentRepo;
        this.configService      = configService;
        this.grouping           = grouping;
        this.workbookService    = workbookService;
        this.mailSenderProvider = mailSenderProvider;
    }

    // -----------------------------------------------------------------------
    // Report model
    // -----------------------------------------------------------------------

    /** One breached ticket with no reason logged. */
    public record PendingTicket(String key, String url, String summary, String status,
                                String severity, String bucket, String breachTime) {}

    /** One pivot row — an assignee and their pending counts. */
    public record PivotRow(String name, String email, int open, int resolved, int total,
                           List<PendingTicket> tickets) {}

    /**
     * One team's pivot. {@code error} is set instead of rows when the Jira query failed.
     * {@code openGroups}/{@code resolvedGroups} keep the full assignee-grouped ticket
     * lists — ALL breached tickets, not just the reason-less ones — so the attached
     * workbook can reproduce the SLA Tracker sheet. They are excluded from JSON to keep
     * the preview response small.
     */
    public record TeamPivot(String teamId, String teamName, List<PivotRow> rows,
                            int open, int resolved, int total, int breachedTotal,
                            String error,
                            @JsonIgnore List<Map<String, Object>> openGroups,
                            @JsonIgnore List<Map<String, Object>> resolvedGroups) {}

    /** The whole report. {@code reasons} maps ticket key → logged reason (for the sheet's last column). */
    public record Report(String resolvedDate, List<TeamPivot> teams,
                         int grandTotal, int grandBreachedTotal, String generatedAt,
                         @JsonIgnore Map<String, String> reasons) {}

    /** Outcome of a send attempt. */
    public record SendResult(boolean sent, String message, List<String> recipients,
                             Report report, String attachment) {}

    // -----------------------------------------------------------------------
    // Build
    // -----------------------------------------------------------------------

    /** The calendar day the report covers: the current day in the report timezone (IST). */
    public LocalDate defaultResolvedDate() {
        return LocalDate.now(ZoneId.of(zoneId));
    }

    /**
     * Teams the report covers, in the order they are stored. An empty selection in Admin
     * means every team — so the report never silently goes blank if a team id is renamed,
     * it just widens.
     */
    public List<Team> reportTeams() {
        List<Team> all = teamRepository.findAll();
        List<String> wanted = configService.getSlaReportTeamList();
        if (wanted.isEmpty()) return all;

        List<Team> picked = all.stream().filter(t -> wanted.contains(t.getId())).toList();
        if (picked.isEmpty()) {
            log.warn("[SLA-Report] Configured teams {} match none of the {} existing teams — "
                   + "reporting on all of them instead.", wanted, all.size());
            return all;
        }
        return picked;
    }

    /**
     * Builds the pivot for each team the report is scoped to.
     *
     * @param resolvedDate day to scope resolved/closed breaches to; null → {@link #defaultResolvedDate()}
     */
    public Report build(LocalDate resolvedDate) {
        LocalDate day = resolvedDate != null ? resolvedDate : defaultResolvedDate();

        String fieldId = configService.getSlaFieldId();
        if (fieldId == null || fieldId.isBlank())
            throw new IllegalStateException(
                "SLA field ID is not configured — set it in Admin → SLA Configuration.");

        // Ticket key → logged reason. A key that is absent here has an EMPTY reason,
        // which is exactly what the pivot counts.
        Map<String, String> reasons = new HashMap<>();
        for (BreachComment bc : commentRepo.findAll()) reasons.put(bc.getIssueKey(), bc.getReason());
        Set<String> withReason = reasons.keySet();

        String sevKey = jiraClient.discoverSeverityFieldKey();

        List<TeamPivot> teamPivots = new ArrayList<>();
        int grandTotal = 0, grandBreached = 0;

        for (Team t : reportTeams()) {
            try {
                List<JsonNode> openBreached     =
                    jiraClient.getOpenSlaTickets(t.getJql(), fieldId);
                List<JsonNode> resolvedBreached =
                    jiraClient.getResolvedSlaTickets(t.getJql(), fieldId, day.toString());

                // Same attribution rules as the SLA tab: changelog owner for open,
                // current assignee for resolved.
                List<Map<String, Object>> openGroups =
                    grouping.groupByBreachOwner(openBreached, fieldId, sevKey, true);
                List<Map<String, Object>> resolvedGroups =
                    grouping.groupByBreachOwner(resolvedBreached, fieldId, sevKey, false);

                TeamPivot pivot = pivot(t, openGroups, resolvedGroups, withReason,
                                        openBreached.size() + resolvedBreached.size());
                teamPivots.add(pivot);
                grandTotal    += pivot.total();
                grandBreached += pivot.breachedTotal();

                log.info("[SLA-Report] {} — {} breached, {} still missing a reason (open {}, resolved {})",
                    t.getName(), pivot.breachedTotal(), pivot.total(), pivot.open(), pivot.resolved());

            } catch (Exception e) {
                log.warn("[SLA-Report] Team {} failed: {}", t.getName(), e.getMessage());
                teamPivots.add(new TeamPivot(t.getId(), t.getName(), List.of(),
                                             0, 0, 0, 0, e.getMessage(), List.of(), List.of()));
            }
        }

        String generatedAt = LocalDateTime.now(ZoneId.of(zoneId)).format(SENT_FMT);
        return new Report(day.toString(), teamPivots, grandTotal, grandBreached, generatedAt, reasons);
    }

    /**
     * Merges the open + resolved groups into pivot rows, keeping only reason-less tickets.
     * The untouched groups are carried through on the result for the xlsx attachment.
     */
    private TeamPivot pivot(Team team,
                            List<Map<String, Object>> openGroups,
                            List<Map<String, Object>> resolvedGroups,
                            Set<String> withReason,
                            int breachedTotal) {

        // key → mutable accumulator
        Map<String, Object[]> acc = new LinkedHashMap<>(); // [name, email, open, resolved, tickets]

        collect(acc, openGroups,     withReason, "Open");
        collect(acc, resolvedGroups, withReason, "Resolved");

        List<PivotRow> rows = new ArrayList<>();
        int openTotal = 0, resolvedTotal = 0;
        for (Object[] a : acc.values()) {
            @SuppressWarnings("unchecked")
            List<PendingTicket> tickets = (List<PendingTicket>) a[4];
            int open     = (int) a[2];
            int resolved = (int) a[3];
            if (open + resolved == 0) continue;
            rows.add(new PivotRow((String) a[0], (String) a[1], open, resolved,
                                  open + resolved, tickets));
            openTotal     += open;
            resolvedTotal += resolved;
        }

        // Highest pending count first — the people to chase are at the top.
        rows.sort(Comparator.comparingInt(PivotRow::total).reversed()
                            .thenComparing(PivotRow::name, String.CASE_INSENSITIVE_ORDER));

        return new TeamPivot(team.getId(), team.getName(), rows,
                             openTotal, resolvedTotal, openTotal + resolvedTotal,
                             breachedTotal, null, openGroups, resolvedGroups);
    }

    /** Folds one grouped list into the accumulator, skipping tickets that already have a reason. */
    @SuppressWarnings("unchecked")
    private void collect(Map<String, Object[]> acc, List<Map<String, Object>> groups,
                         Set<String> withReason, String bucket) {

        for (Map<String, Object> g : groups) {
            String email = (String) g.getOrDefault("email", "");
            String name  = (String) g.getOrDefault("name",  "");
            if (name == null || name.isBlank()) name = email.isBlank() ? "Unknown" : email;

            String key = email.isBlank() ? name.toLowerCase() : email.toLowerCase();
            String displayEmail = "__unassigned__".equals(email) ? "" : email;

            final String fName = name, fEmail = displayEmail;
            Object[] a = acc.computeIfAbsent(key,
                k -> new Object[]{ fName, fEmail, 0, 0, new ArrayList<PendingTicket>() });

            for (Map<String, Object> tk : (List<Map<String, Object>>) g.get("tickets")) {
                String issueKey = (String) tk.get("key");
                if (issueKey == null || withReason.contains(issueKey)) continue;  // reason already logged

                Map<String, Object> sla = (Map<String, Object>) tk.get("sla");
                String breachTime = sla == null ? "" : String.valueOf(sla.getOrDefault("breachTime", ""));

                ((List<PendingTicket>) a[4]).add(new PendingTicket(
                    issueKey,
                    jiraClient.browseUrl(issueKey),
                    String.valueOf(tk.getOrDefault("summary",  "")),
                    String.valueOf(tk.getOrDefault("status",   "")),
                    String.valueOf(tk.getOrDefault("severity", "")),
                    bucket,
                    "null".equals(breachTime) ? "" : breachTime));

                if ("Open".equals(bucket)) a[2] = (int) a[2] + 1;
                else                       a[3] = (int) a[3] + 1;
            }
        }
    }

    // -----------------------------------------------------------------------
    // Send
    // -----------------------------------------------------------------------

    /**
     * True when SMTP is wired up well enough to attempt a send. The mail server is
     * configured only through the environment (SMTP_HOST / SMTP_USER / SMTP_PASS) —
     * deliberately not exposed in the UI, so credentials stay out of the app DB.
     */
    public boolean isMailConfigured() {
        return smtpHost != null && !smtpHost.isBlank()
            && mailSenderProvider.getIfAvailable() != null;
    }

    public String smtpHost() { return smtpHost == null ? "" : smtpHost; }

    /** Effective From address — sla.report.from, else the SMTP username. */
    public String effectiveFrom() {
        if (fromAddress != null && !fromAddress.isBlank()) return fromAddress.trim();
        return smtpUser == null ? "" : smtpUser.trim();
    }

    /** Display name shown next to the From address. */
    private String effectiveFromName() {
        return (fromName == null || fromName.isBlank()) ? "SLA Tracker" : fromName;
    }

    /**
     * Sends a one-line test mail with the current settings — the Admin "Send test" button.
     * Deliberately does no Jira work, so it isolates mail problems from report problems.
     */
    public SendResult sendTest(String to) {
        String target = (to == null || !to.contains("@")) ? null : to.trim();
        if (target == null) {
            List<String> saved = configService.getSlaReportRecipientList();
            if (saved.isEmpty())
                return new SendResult(false, "Enter a test address, or save recipients first.",
                                      List.of(), null, null);
            target = saved.get(0);
        }
        if (!isMailConfigured())
            return new SendResult(false, "No mail server configured yet.", List.of(target), null, null);

        String from = effectiveFrom();
        if (from.isBlank())
            return new SendResult(false, "No From address — set it in the mail server settings.",
                                  List.of(target), null, null);

        try {
            JavaMailSender sender = mailSenderProvider.getObject();
            MimeMessage msg = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, false, "UTF-8");
            helper.setFrom(from, effectiveFromName());
            helper.setTo(target);
            helper.setSubject("SLA Tracker — mail server test");
            helper.setText(
                "<div style=\"font-family:Segoe UI,Arial,sans-serif;color:#172b4d;\">"
                + "<h3 style=\"margin:0 0 8px;\">✓ Mail server works</h3>"
                + "<p style=\"font-size:13px;color:#5e6c84;margin:0;\">Sent from the SLA Tracker via <strong>"
                + esc(smtpHost()) + "</strong> as <strong>" + esc(from) + "</strong>.<br>"
                + "The daily breach-reason report will arrive at 07:30 IST with the day's "
                + "SLA Tracker sheet attached.</p></div>", true);
            sender.send(msg);

            log.info("[SLA-Report] Test mail sent to {} via {}", target, smtpHost());
            return new SendResult(true, "Test email sent to " + target + ".",
                                  List.of(target), null, null);
        } catch (Exception e) {
            log.warn("[SLA-Report] Test mail failed: {}", e.getMessage());
            return new SendResult(false, "Test failed: " + e.getMessage(),
                                  List.of(target), null, null);
        }
    }

    /**
     * Builds and emails the report.
     *
     * @param resolvedDate day to scope resolved breaches to; null → yesterday (IST)
     * @param overrideTo   recipients for this send only; null/empty → the saved Admin list
     */
    public SendResult send(LocalDate resolvedDate, List<String> overrideTo) {
        List<String> to = (overrideTo != null && !overrideTo.isEmpty())
            ? overrideTo : configService.getSlaReportRecipientList();

        if (to.isEmpty())
            return new SendResult(false,
                "No recipients configured — add them in Admin → Daily SLA Report Email.",
                List.of(), null, null);

        if (!isMailConfigured())
            return new SendResult(false,
                "No mail server configured — set it in Admin → Mail Server (SMTP).",
                to, null, null);

        String from = effectiveFrom();
        if (from.isBlank())
            return new SendResult(false,
                "No From address — set it in Admin → Mail Server (SMTP).", to, null, null);

        Report report = build(resolvedDate);

        String subject = "SLA Breach Reasons Pending — " + report.resolvedDate()
                       + " — " + report.grandTotal() + " ticket(s)";
        if (report.grandTotal() == 0)
            subject = "SLA Breach Reasons — " + report.resolvedDate() + " — all reasons logged ✅";

        // The day's full SLA Tracker sheet. A workbook failure must not lose the email,
        // so a null here just means the mail goes out without the attachment.
        byte[] xlsx        = workbookService.build(report, report.reasons());
        String fileName    = "SLA_Tracker_" + report.resolvedDate() + ".xlsx";
        boolean hasAttach  = xlsx != null && xlsx.length > 0;

        try {
            JavaMailSender sender = mailSenderProvider.getObject();
            MimeMessage msg = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, hasAttach, "UTF-8");
            helper.setFrom(from, effectiveFromName());
            helper.setTo(to.toArray(new String[0]));
            helper.setSubject(subject);
            helper.setText(renderHtml(report, hasAttach ? fileName : null), true);
            if (hasAttach)
                helper.addAttachment(fileName, new ByteArrayResource(xlsx),
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            sender.send(msg);

            log.info("[SLA-Report] Sent to {} — {} pending ticket(s) for {}{}",
                to, report.grandTotal(), report.resolvedDate(),
                hasAttach ? " (+ " + fileName + ")" : " (attachment unavailable)");
            return new SendResult(true,
                "Report sent to " + to.size() + " recipient(s)."
                + (hasAttach ? "" : " Excel attachment could not be generated — see logs."),
                to, report, hasAttach ? fileName : null);

        } catch (Exception e) {
            log.error("[SLA-Report] Send failed: {}", e.getMessage(), e);
            return new SendResult(false, "Send failed: " + e.getMessage(), to, report, null);
        }
    }

    /** Builds the day's SLA Tracker workbook on its own — used by the Admin download. */
    public byte[] buildWorkbook(Report report) {
        return workbookService.build(report, report.reasons());
    }

    // -----------------------------------------------------------------------
    // HTML rendering — inline styles only; Outlook strips <style> blocks
    // -----------------------------------------------------------------------

    private static final String TD  = "padding:7px 10px;border-bottom:1px solid #ebecf0;font-size:13px;";
    private static final String TH  = "padding:8px 10px;background:#f4f5f7;border-bottom:2px solid #dfe1e6;"
                                    + "font-size:12px;text-transform:uppercase;letter-spacing:.4px;"
                                    + "color:#5e6c84;text-align:left;";
    private static final String NUM = "text-align:right;font-variant-numeric:tabular-nums;";

    public String renderHtml(Report report) {
        return renderHtml(report, null);
    }

    /**
     * @param attachmentName filename of the attached SLA Tracker sheet, or null when
     *                       no workbook is attached (the callout is then omitted)
     */
    public String renderHtml(Report report, String attachmentName) {
        StringBuilder h = new StringBuilder();
        h.append("<div style=\"font-family:Segoe UI,Arial,sans-serif;color:#172b4d;max-width:860px;\">");

        h.append("<h2 style=\"margin:0 0 4px;font-size:19px;\">SLA Breach Reasons — Pending</h2>")
         .append("<div style=\"font-size:12.5px;color:#5e6c84;margin-bottom:4px;\">")
         .append("Breached tickets with an <strong>empty Breach Reason</strong>, by assignee.</div>")
         .append("<div style=\"font-size:12px;color:#7a869a;margin-bottom:18px;\">")
         .append("Open breaches as of now &nbsp;·&nbsp; resolved breaches for <strong>")
         .append(esc(report.resolvedDate())).append("</strong> &nbsp;·&nbsp; generated ")
         .append(esc(report.generatedAt())).append("</div>");

        // Headline number
        String bannerBg  = report.grandTotal() == 0 ? "#e3fcef" : "#fffae6";
        String bannerCol = report.grandTotal() == 0 ? "#006644" : "#974f0c";
        h.append("<div style=\"background:").append(bannerBg).append(";border-radius:6px;")
         .append("padding:12px 14px;margin-bottom:20px;font-size:14px;color:").append(bannerCol).append(";\">");
        if (report.grandTotal() == 0) {
            h.append("<strong>All clear</strong> — every breached ticket in scope has a reason logged.");
        } else {
            h.append("<strong>").append(report.grandTotal()).append("</strong> breached ticket(s) still have no reason")
             .append(" &nbsp;·&nbsp; out of ").append(report.grandBreachedTotal()).append(" breached in scope.");
        }
        h.append("</div>");

        // Attachment callout — the full tracker sheet for the same day
        if (attachmentName != null && !attachmentName.isBlank()) {
            h.append("<div style=\"background:#deebff;border-radius:6px;padding:11px 14px;")
             .append("margin-bottom:20px;font-size:13px;color:#0747a6;\">")
             .append("📎 Attached: <strong>").append(esc(attachmentName)).append("</strong>")
             .append(" — the full SLA Tracker sheet for ").append(esc(report.resolvedDate()))
             .append(" (pivot + every breached ticket per team, reason column included).</div>");
        }

        for (TeamPivot tp : report.teams()) {
            h.append("<div style=\"font-size:15px;font-weight:600;margin:22px 0 8px;\">")
             .append(esc(tp.teamName())).append("</div>");

            if (tp.error() != null) {
                h.append("<div style=\"font-size:12.5px;color:#bf2600;background:#ffebe6;")
                 .append("border-radius:4px;padding:9px 12px;\">Could not build this team's pivot: ")
                 .append(esc(tp.error())).append("</div>");
                continue;
            }
            if (tp.rows().isEmpty()) {
                h.append("<div style=\"font-size:12.5px;color:#5e6c84;background:#f4f5f7;")
                 .append("border-radius:4px;padding:9px 12px;\">No breached tickets missing a reason")
                 .append(" (").append(tp.breachedTotal()).append(" breached in scope).</div>");
                continue;
            }

            // ---- the pivot ----
            h.append("<table role=\"presentation\" cellspacing=\"0\" cellpadding=\"0\" ")
             .append("style=\"border-collapse:collapse;width:100%;border:1px solid #dfe1e6;border-radius:4px;\">")
             .append("<thead><tr>")
             .append("<th style=\"").append(TH).append("\">Assignee</th>")
             .append("<th style=\"").append(TH).append(NUM).append("\">Open</th>")
             .append("<th style=\"").append(TH).append(NUM).append("\">Resolved</th>")
             .append("<th style=\"").append(TH).append(NUM).append("\">Total</th>")
             .append("</tr></thead><tbody>");

            for (PivotRow r : tp.rows()) {
                h.append("<tr><td style=\"").append(TD).append("\">")
                 .append("<span style=\"font-weight:600;\">").append(esc(r.name())).append("</span>");
                if (r.email() != null && !r.email().isBlank())
                    h.append("<br><span style=\"font-size:11.5px;color:#7a869a;\">")
                     .append(esc(r.email())).append("</span>");
                h.append("</td>")
                 .append("<td style=\"").append(TD).append(NUM).append("\">").append(r.open()).append("</td>")
                 .append("<td style=\"").append(TD).append(NUM).append("\">").append(r.resolved()).append("</td>")
                 .append("<td style=\"").append(TD).append(NUM)
                 .append("font-weight:700;\">").append(r.total()).append("</td></tr>");
            }

            h.append("<tr><td style=\"").append(TD)
             .append("font-weight:700;background:#f4f5f7;\">Total</td>")
             .append("<td style=\"").append(TD).append(NUM).append("font-weight:700;background:#f4f5f7;\">")
             .append(tp.open()).append("</td>")
             .append("<td style=\"").append(TD).append(NUM).append("font-weight:700;background:#f4f5f7;\">")
             .append(tp.resolved()).append("</td>")
             .append("<td style=\"").append(TD).append(NUM).append("font-weight:700;background:#f4f5f7;\">")
             .append(tp.total()).append("</td></tr>")
             .append("</tbody></table>");

            // ---- the tickets behind the numbers ----
            h.append("<div style=\"font-size:12px;color:#5e6c84;margin:14px 0 6px;font-weight:600;\">")
             .append("Tickets awaiting a reason</div>")
             .append("<table role=\"presentation\" cellspacing=\"0\" cellpadding=\"0\" ")
             .append("style=\"border-collapse:collapse;width:100%;border:1px solid #dfe1e6;\">")
             .append("<thead><tr>")
             .append("<th style=\"").append(TH).append("\">Ticket</th>")
             .append("<th style=\"").append(TH).append("\">Assignee</th>")
             .append("<th style=\"").append(TH).append("\">Status</th>")
             .append("<th style=\"").append(TH).append("\">Severity</th>")
             .append("<th style=\"").append(TH).append("\">Breached</th>")
             .append("</tr></thead><tbody>");

            for (PivotRow r : tp.rows()) {
                for (PendingTicket pt : r.tickets()) {
                    h.append("<tr><td style=\"").append(TD).append("\">")
                     .append("<a href=\"").append(esc(pt.url()))
                     .append("\" style=\"color:#0052cc;text-decoration:none;font-weight:600;\">")
                     .append(esc(pt.key())).append("</a>")
                     .append("<br><span style=\"font-size:11.5px;color:#7a869a;\">")
                     .append(esc(trim(pt.summary(), 90))).append("</span></td>")
                     .append("<td style=\"").append(TD).append("\">").append(esc(r.name())).append("</td>")
                     .append("<td style=\"").append(TD).append("\">").append(esc(pt.status())).append("</td>")
                     .append("<td style=\"").append(TD).append("\">").append(esc(pt.severity())).append("</td>")
                     .append("<td style=\"").append(TD).append("\">").append(esc(pt.breachTime())).append("</td>")
                     .append("</tr>");
                }
            }
            h.append("</tbody></table>");
        }

        h.append("<div style=\"font-size:11.5px;color:#7a869a;margin-top:24px;")
         .append("border-top:1px solid #ebecf0;padding-top:10px;\">")
         .append("Log a reason from the <strong>SLA Tracker</strong> → Breach Reason dropdown. ")
         .append("Automated daily report — reply to this address is not monitored.</div>")
         .append("</div>");

        return h.toString();
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
