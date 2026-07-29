package com.jira.autoassign.scheduler;

import com.jira.autoassign.service.JiraConfigService;
import com.jira.autoassign.service.SlaDailyReportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Emails the daily "breach reasons still missing" pivot at 07:30 IST, with the current
 * day's full SLA Tracker sheet attached as .xlsx.
 *
 * The run covers the CURRENT calendar day (IST): open breaches as a live snapshot plus
 * breaches resolved since midnight — i.e. the night shift's work, which is exactly what
 * a 07:30 handover mail should chase. Pass ?date= on the manual endpoint for any other day.
 *
 * Silently skips when the report is switched off in Admin, when no recipients are
 * saved, or when SMTP is not configured — a missing report must never break the app.
 */
@Component
public class SlaDailyReportScheduler {

    private static final Logger log = LoggerFactory.getLogger(SlaDailyReportScheduler.class);

    private final SlaDailyReportService reportService;
    private final JiraConfigService     configService;

    public SlaDailyReportScheduler(SlaDailyReportService reportService,
                                   JiraConfigService configService) {
        this.reportService = reportService;
        this.configService = configService;
    }

    @Scheduled(cron = "${sla.report.cron:0 30 7 * * *}", zone = "${sla.report.zone:Asia/Kolkata}")
    public void sendDailyReport() {
        if (!configService.isSlaReportEnabled()) {
            log.info("[SLA-Report] Daily report is disabled in Admin — skipping.");
            return;
        }
        if (configService.getSlaReportRecipientList().isEmpty()) {
            log.info("[SLA-Report] No recipients configured — skipping.");
            return;
        }
        if (!reportService.isMailConfigured()) {
            log.warn("[SLA-Report] SMTP not configured (spring.mail.host is empty) — skipping.");
            return;
        }

        try {
            SlaDailyReportService.SendResult result =
                reportService.send(reportService.defaultResolvedDate(), null);
            if (!result.sent())
                log.warn("[SLA-Report] Daily report not sent: {}", result.message());
        } catch (Exception e) {
            log.error("[SLA-Report] Daily report run failed: {}", e.getMessage(), e);
        }
    }
}
