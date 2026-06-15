package com.jira.autoassign.scheduler;

import com.jira.autoassign.service.B2bNotifyService;
import com.jira.autoassign.service.ShiftAssignService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class AssignScheduler {

    private static final Logger log = LoggerFactory.getLogger(AssignScheduler.class);

    private final ShiftAssignService shiftAssignService;
    private final B2bNotifyService   b2bNotifyService;

    /** Timestamp of the most recent scheduler invocation (null = never fired since startup). */
    private volatile Instant lastRunAt = null;

    public AssignScheduler(ShiftAssignService shiftAssignService,
                           B2bNotifyService b2bNotifyService) {
        this.shiftAssignService = shiftAssignService;
        this.b2bNotifyService   = b2bNotifyService;
    }

    /**
     * Catch-up run fired once as soon as the app is fully started.
     * Ensures any trigger missed while the app was down is processed immediately
     * instead of waiting up to 60 seconds for the next cron tick.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void runOnStartup() {
        log.info("Startup catch-up run — processing any missed triggers.");
        try {
            lastRunAt = Instant.now();
            shiftAssignService.runAllTeams();
            b2bNotifyService.runAll();
        } catch (Exception e) {
            log.error("Startup catch-up run failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Runs on the configured cron (default: every minute).
     * - Assigns unassigned tickets to whoever is currently on shift.
     * - Unassigns tickets from people whose shift just ended.
     */
    @Scheduled(cron = "${jira.schedule.cron}")
    public void scheduledRun() {
        lastRunAt = Instant.now();
        log.info("Scheduler triggered — running all teams.");
        // Never let an exception escape a @Scheduled method: an uncaught throwable
        // (or even an Error) propagating out can stop this cron from being rescheduled,
        // silently freezing all auto-assignment while the process stays alive.
        try {
            shiftAssignService.runAllTeams();
        } catch (Exception e) {
            log.error("Scheduled assignment run failed: {}", e.getMessage(), e);
        }
    }

    /**
     * B2B monitor poll — runs on its own (faster) cron so assignee changes are
     * picked up quickly without speeding up the auto-assign run for the other teams.
     * Each tick compares every B2B ticket's current assignee against the previous
     * one stored in B2bAssignState and notifies when it differs (plus support/SLA checks).
     */
    @Scheduled(cron = "${jira.b2b.schedule.cron}")
    public void b2bScheduledRun() {
        try {
            b2bNotifyService.runAll();
        } catch (Exception e) {
            log.error("B2B monitor run failed: {}", e.getMessage(), e);
        }
    }

    /** Returns the instant this scheduler last fired, or {@code null} if it hasn't fired since startup. */
    public Instant getLastRunAt() { return lastRunAt; }

    /** Purges activity log and shift roster entries older than 7 days — runs daily at midnight IST. */
    @Scheduled(cron = "0 0 0 * * *", zone = "Asia/Kolkata")
    public void purgeOldData() {
        log.info("Running daily purge of data older than 7 days.");
        try {
            shiftAssignService.purgeOldData();
            b2bNotifyService.purgeOldState(30);
        } catch (Exception e) {
            log.error("Daily purge failed: {}", e.getMessage(), e);
        }
    }
}
