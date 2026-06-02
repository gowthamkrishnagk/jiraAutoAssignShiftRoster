package com.jira.autoassign.scheduler;

import com.jira.autoassign.repository.BreachCommentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Keeps the breach_comment table to a rolling 45-day window.
 * Runs daily at 00:30 and removes any comment older than 45 days, so reasons
 * fall off gradually instead of all disappearing on the 1st of the month.
 */
@Component
public class BreachCommentCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(BreachCommentCleanupScheduler.class);

    /** How long a breach reason is retained. */
    private static final int RETENTION_DAYS = 45;

    private final BreachCommentRepository repo;

    public BreachCommentCleanupScheduler(BreachCommentRepository repo) {
        this.repo = repo;
    }

    // Runs at 00:30:00 every day
    @Scheduled(cron = "0 30 0 * * *")
    public void purgeOldComments() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(RETENTION_DAYS);
        int deleted = repo.deleteOlderThan(cutoff);
        log.info("[BreachComment] Daily cleanup — deleted {} comment(s) older than {} days (before {})",
            deleted, RETENTION_DAYS, cutoff);
    }
}
