package com.jira.autoassign.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Binds all properties prefixed with "jira" from application.properties
 * into this strongly-typed class. Spring Boot handles the mapping automatically.
 *
 * Example: jira.url -> getUrl(), jira.api-token -> getApiToken()
 */
@Data
@Component
@ConfigurationProperties(prefix = "jira")
public class JiraProperties {

    /** Jira Cloud base URL, e.g. https://yourcompany.atlassian.net */
    private String url;

    /** Login email used for Basic Auth */
    private String email;

    /** API token (not your password) from Atlassian account settings */
    private String apiToken;

    /** Jira project key, e.g. PROJ */
    private String projectKey;

    /** Ordered list of assignee emails for round-robin rotation */
    private List<String> assignees;

    /** Only fetch tickets in these statuses; empty = no status filter */
    private List<String> targetStatuses;

    /** Only fetch these issue types (Bug, Story, etc.); empty = all types */
    private List<String> targetIssueTypes;

    /** Only fetch tickets with these labels; empty = all labels */
    private List<String> targetLabels;

    /** When true, only tickets with no current assignee are fetched */
    private boolean onlyUnassigned = true;

    /** When true, logs assignments but does NOT call the Jira assign API */
    private boolean dryRun = false;

    /**
     * Custom JQL query to filter tickets.
     * When set, this takes priority over all other filters
     * (target-statuses, target-issue-types, target-labels, only-unassigned).
     *
     * Example: project = PROJ AND sprint in openSprints() AND priority = High AND assignee is EMPTY
     */
    private String customJql;
}
