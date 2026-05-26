package com.jira.autoassign.scheduler;

import com.jira.autoassign.repository.BreachCommentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Automatically clears the breach_comment table on the 1st of every month at midnight.
 * This keeps the table lightweight — comments are only relevant within the current month.
 */
@Component
public class BreachCommentCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(BreachCommentCleanupScheduler.class);

    private final BreachCommentRepository repo;

    public BreachCommentCleanupScheduler(BreachCommentRepository repo) {
        this.repo = repo;
    }

    // Runs at 00:00:00 on the 1st of every month
    @Scheduled(cron = "0 0 0 1 * *")
    public void clearMonthlyComments() {
        long count = repo.count();
        repo.deleteAll();
        log.info("[BreachComment] Monthly cleanup — deleted {} comment record(s)", count);
    }
}
