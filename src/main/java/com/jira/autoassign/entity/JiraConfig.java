package com.jira.autoassign.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "jira_config")
public class JiraConfig {

    @Id
    private Long id = 1L; // single-row table

    @Column(name = "jira_email")
    private String jiraEmail;

    @Column(name = "api_token")
    private String apiToken;

    public Long getId()                  { return id; }
    public String getJiraEmail()         { return jiraEmail; }
    public void   setJiraEmail(String v) { this.jiraEmail = v; }
    public String getApiToken()          { return apiToken; }
    public void   setApiToken(String v)  { this.apiToken = v; }
}
