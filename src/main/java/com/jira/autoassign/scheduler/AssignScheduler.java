package com.jira.autoassign.scheduler;

import com.jira.autoassign.service.AssignService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AssignScheduler {

    private static final Logger log = LoggerFactory.getLogger(AssignScheduler.class);

    private final AssignService assignService;

    public AssignScheduler(AssignService assignService) {
        this.assignService = assignService;
    }

    /**
     * Triggered automatically based on the cron expression in application.properties.
     * Default: every 30 minutes -> 0 *\/30 * * * *
     *
     * To change frequency, update jira.schedule.cron in application.properties.
     * Examples:
     *   Every 10 min  : 0 *\/10 * * * *
     *   Every hour    : 0 0 * * * *
     *   Daily 9 AM    : 0 0 9 * * *
     */
    @Scheduled(cron = "${jira.schedule.cron}")
    public void scheduledRun() {
        log.info("Scheduler triggered — starting assignment run.");
        assignService.runAssignment();
    }
}
