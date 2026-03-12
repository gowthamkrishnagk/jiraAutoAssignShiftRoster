package com.jira.autoassign.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestTemplate;

/**
 * Central Spring configuration class.
 * EnableScheduling activates the @Scheduled annotation in AssignScheduler.
 * RestTemplate bean is used by JiraClient to make HTTP calls to the Jira REST API.
 */
@Configuration
@EnableScheduling
public class AppConfig {

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
