package com.jira.autoassign;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties
@EnableScheduling
public class JiraAutoAssignApplication {

    private static final Logger log = LoggerFactory.getLogger(JiraAutoAssignApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(JiraAutoAssignApplication.class, args);
        log.info("Jira Shift Roster started. Upload a roster at /");
    }
}
