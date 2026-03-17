package com.jira.autoassign;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

@SpringBootApplication
@EnableConfigurationProperties
@EnableScheduling
public class JiraAutoAssignApplication {

    private static final Logger log = LoggerFactory.getLogger(JiraAutoAssignApplication.class);

    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
        SpringApplication.run(JiraAutoAssignApplication.class, args);
        log.info("Jira Shift Roster started (timezone: Asia/Kolkata). Upload a roster at /");
    }
}
