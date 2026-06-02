package com.jira.autoassign.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestTemplate;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Central Spring configuration class.
 * EnableScheduling activates the @Scheduled annotation in AssignScheduler.
 *
 * The RestTemplate is backed by Java's {@link HttpClient}, which keeps a pool of
 * persistent (keep-alive) connections to Jira. Reusing connections avoids a fresh
 * TLS handshake on every call — the dominant cost when the SLA dashboard and the
 * report make many sequential Jira requests. No external HTTP-client dependency
 * is needed (Java 21 + Spring's JdkClientHttpRequestFactory).
 */
@Configuration
@EnableScheduling
public class AppConfig {

    @Bean
    public RestTemplate restTemplate() {
        // HTTP/1.1 keeps connection reuse predictable for Jira Cloud.
        HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(30));

        return new RestTemplate(factory);
    }
}
