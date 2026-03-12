package com.jira.autoassign.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Binds all properties prefixed with "jira" from application.properties
 * into this strongly-typed class. Spring Boot handles the mapping automatically.
 */
@Component
@ConfigurationProperties(prefix = "jira")
public class JiraProperties {

    private String url;
    private String email;
    private String apiToken;
    private String projectKey;
    private List<String> assignees;
    private List<String> targetStatuses;
    private List<String> targetIssueTypes;
    private List<String> targetLabels;
    private boolean onlyUnassigned = true;
    private boolean dryRun = false;

    /**
     * Custom JQL query to filter tickets.
     * When set, this takes priority over all other filters.
     * Example: project = PROJ AND sprint in openSprints() AND assignee is EMPTY
     */
    private String customJql;

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getApiToken() { return apiToken; }
    public void setApiToken(String apiToken) { this.apiToken = apiToken; }

    public String getProjectKey() { return projectKey; }
    public void setProjectKey(String projectKey) { this.projectKey = projectKey; }

    public List<String> getAssignees() { return assignees; }
    public void setAssignees(List<String> assignees) { this.assignees = assignees; }

    public List<String> getTargetStatuses() { return targetStatuses; }
    public void setTargetStatuses(List<String> targetStatuses) { this.targetStatuses = targetStatuses; }

    public List<String> getTargetIssueTypes() { return targetIssueTypes; }
    public void setTargetIssueTypes(List<String> targetIssueTypes) { this.targetIssueTypes = targetIssueTypes; }

    public List<String> getTargetLabels() { return targetLabels; }
    public void setTargetLabels(List<String> targetLabels) { this.targetLabels = targetLabels; }

    public boolean isOnlyUnassigned() { return onlyUnassigned; }
    public void setOnlyUnassigned(boolean onlyUnassigned) { this.onlyUnassigned = onlyUnassigned; }

    public boolean isDryRun() { return dryRun; }
    public void setDryRun(boolean dryRun) { this.dryRun = dryRun; }

    public String getCustomJql() { return customJql; }
    public void setCustomJql(String customJql) { this.customJql = customJql; }
}
