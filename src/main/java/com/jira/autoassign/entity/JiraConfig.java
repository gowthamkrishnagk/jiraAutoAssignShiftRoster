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

    @Column(name = "sla_field_id")
    private String slaFieldId;

    @Column(name = "webhook_url", columnDefinition = "TEXT")
    private String webhookUrl;

    @Column(name = "b2b_webhook_url", columnDefinition = "TEXT")
    private String b2bWebhookUrl;

    /** Teams email domain (e.g. "prodapt.com") used to derive a Teams @mention address
     *  from the Jira email's local-part when no explicit member mapping exists. */
    @Column(name = "b2b_teams_domain")
    private String b2bTeamsDomain;

    /** Recipients of the daily SLA breach-reason report — comma/semicolon/newline separated. */
    @Column(name = "sla_report_recipients", columnDefinition = "TEXT")
    private String slaReportRecipients;

    // --- SMTP for the daily report. When smtpHost is set these win over the
    //     spring.mail.* env vars, so the mail server can be changed from the UI
    //     without touching the service file or restarting. ---

    @Column(name = "smtp_host")
    private String smtpHost;

    @Column(name = "smtp_port")
    private Integer smtpPort;

    @Column(name = "smtp_username")
    private String smtpUsername;

    @Column(name = "smtp_password")
    private String smtpPassword;

    @Column(name = "smtp_auth", nullable = false, columnDefinition = "boolean default true")
    private boolean smtpAuth = true;

    @Column(name = "smtp_starttls", nullable = false, columnDefinition = "boolean default true")
    private boolean smtpStartTls = true;

    /** From address on the report mail. Blank → falls back to smtpUsername. */
    @Column(name = "sla_report_from")
    private String slaReportFrom;

    /** Display name shown next to the From address. */
    @Column(name = "sla_report_from_name")
    private String slaReportFromName;

    /** Whether the scheduled daily report is sent. Manual "send now" ignores this. */
    @Column(name = "sla_report_enabled", nullable = false, columnDefinition = "boolean default true")
    private boolean slaReportEnabled = true;

    public Long getId()                    { return id; }
    public String getJiraEmail()           { return jiraEmail; }
    public void   setJiraEmail(String v)   { this.jiraEmail = v; }
    public String getApiToken()            { return apiToken; }
    public void   setApiToken(String v)    { this.apiToken = v; }
    public String getSlaFieldId()          { return slaFieldId; }
    public void   setSlaFieldId(String v)  { this.slaFieldId = v; }
    public String getWebhookUrl()          { return webhookUrl; }
    public void   setWebhookUrl(String v)  { this.webhookUrl = v; }
    public String getB2bWebhookUrl()         { return b2bWebhookUrl; }
    public void   setB2bWebhookUrl(String v) { this.b2bWebhookUrl = v; }
    public String getB2bTeamsDomain()         { return b2bTeamsDomain; }
    public void   setB2bTeamsDomain(String v) { this.b2bTeamsDomain = v; }
    public String getSlaReportRecipients()         { return slaReportRecipients; }
    public void   setSlaReportRecipients(String v) { this.slaReportRecipients = v; }
    public String  getSmtpHost()             { return smtpHost; }
    public void    setSmtpHost(String v)     { this.smtpHost = v; }
    public Integer getSmtpPort()             { return smtpPort; }
    public void    setSmtpPort(Integer v)    { this.smtpPort = v; }
    public String  getSmtpUsername()         { return smtpUsername; }
    public void    setSmtpUsername(String v) { this.smtpUsername = v; }
    public String  getSmtpPassword()         { return smtpPassword; }
    public void    setSmtpPassword(String v) { this.smtpPassword = v; }
    public boolean isSmtpAuth()              { return smtpAuth; }
    public void    setSmtpAuth(boolean v)    { this.smtpAuth = v; }
    public boolean isSmtpStartTls()          { return smtpStartTls; }
    public void    setSmtpStartTls(boolean v){ this.smtpStartTls = v; }
    public String  getSlaReportFrom()             { return slaReportFrom; }
    public void    setSlaReportFrom(String v)     { this.slaReportFrom = v; }
    public String  getSlaReportFromName()         { return slaReportFromName; }
    public void    setSlaReportFromName(String v) { this.slaReportFromName = v; }
    public boolean isSlaReportEnabled()            { return slaReportEnabled; }
    public void    setSlaReportEnabled(boolean v)  { this.slaReportEnabled = v; }
}
