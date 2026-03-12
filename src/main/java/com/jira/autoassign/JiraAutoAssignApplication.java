package com.jira.autoassign;

import com.jira.autoassign.service.AssignService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties
public class JiraAutoAssignApplication implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(JiraAutoAssignApplication.class);

    private final AssignService assignService;

    public JiraAutoAssignApplication(AssignService assignService) {
        this.assignService = assignService;
    }

    public static void main(String[] args) {
        SpringApplication.run(JiraAutoAssignApplication.class, args);
    }

    /**
     * Runs once immediately when the app starts.
     * After this, the scheduler takes over and runs on the configured cron interval.
     *
     * Pass --once as a command-line arg to run once and exit:
     *   java -jar app.jar --once
     */
    @Override
    public void run(ApplicationArguments args) {
        log.info("Application started. Running initial assignment...");
        assignService.runAssignment();

        if (args.containsOption("once")) {
            log.info("--once flag detected. Exiting after initial run.");
            System.exit(0);
        }

        log.info("Scheduler is active. App will keep running on the configured cron.");
    }
}
