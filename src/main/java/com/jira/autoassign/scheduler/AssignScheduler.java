package com.jira.autoassign.scheduler;

import com.jira.autoassign.service.ShiftAssignService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class AssignScheduler {

    private static final Logger log = LoggerFactory.getLogger(AssignScheduler.class);

    private final ShiftAssignService shiftAssignService;

    /** Timestamp of the most recent scheduler invocation (null = never fired since startup). */
    private volatile Instant lastRunAt = null;

    public AssignScheduler(ShiftAssignService shiftAssignService) {
        this.shiftAssignService = shiftAssignService;
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
        shiftAssignService.runAllTeams();
    }

    /** Returns the instant this scheduler last fired, or {@code null} if it hasn't fired since startup. */
    public Instant getLastRunAt() { return lastRunAt; }

    /** Purges activity log and shift roster entries older than 7 days — runs daily at midnight IST. */
    @Scheduled(cron = "0 0 0 * * *", zone = "Asia/Kolkata")
    public void purgeOldData() {
        log.info("Running daily purge of data older than 7 days.");
        try {
            shiftAssignService.purgeOldData();
        } catch (Exception e) {
            log.error("Daily purge failed: {}", e.getMessage(), e);
        }
    }
}
