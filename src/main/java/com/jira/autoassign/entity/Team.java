package com.jira.autoassign.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "teams")
public class Team {

    @Id
    private String id;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String jql;

    @Column(name = "workload_jql", columnDefinition = "TEXT")
    private String workloadJql;

    /** Null on legacy rows until migrated; blank disables alerts for this team. */
    @Column(name = "webhook_url", columnDefinition = "TEXT")
    private String webhookUrl = "";

    @Column(name = "alert_assignment")
    private Boolean assignmentAlerts;

    @Column(name = "alert_reassignment")
    private Boolean reassignmentAlerts;

    @Column(name = "alert_sla")
    private Boolean slaAlerts;

    @Column(name = "alert_sla_warning")
    private Boolean slaWarningAlerts;

    @Column(name = "alert_sla_breach")
    private Boolean slaBreachAlerts;

    @Column(name = "dry_run", nullable = false)
    private boolean dryRun = false;

    /**
     * Whether this team is auto-assigned round-robin by the shift scheduler.
     * Monitor-only teams (e.g. B2B) set this to false: they are never assigned,
     * only watched for assignee changes / support needs / SLA warnings.
     */
    @Column(name = "auto_assign", nullable = false, columnDefinition = "boolean default true")
    private boolean autoAssign = true;

    public Team() {}

    public Team(String id, String name, String jql) {
        this.id   = id;
        this.name = name;
        this.jql  = jql;
    }

    public Team(String id, String name, String jql, boolean autoAssign) {
        this.id         = id;
        this.name       = name;
        this.jql        = jql;
        this.autoAssign = autoAssign;
    }

    public String getId()           { return id; }
    public void   setId(String v)   { this.id = v; }
    public String getName()         { return name; }
    public void   setName(String v) { this.name = v; }
    public String getJql()          { return jql; }
    public void   setJql(String v)  { this.jql = v; }
    public String getWorkloadJql() { return workloadJql; }
    public void setWorkloadJql(String v) { workloadJql = v; }
    public String getWebhookUrl() { return webhookUrl; }
    public void setWebhookUrl(String v) { this.webhookUrl = v; }
    public boolean isAssignmentAlerts() { return assignmentAlerts != null ? assignmentAlerts : !autoAssign; }
    public void setAssignmentAlerts(boolean v) { assignmentAlerts = v; }
    public boolean isReassignmentAlerts() { return reassignmentAlerts == null || reassignmentAlerts; }
    public void setReassignmentAlerts(boolean v) { reassignmentAlerts = v; }
    public boolean isSlaAlerts() { return slaAlerts != null ? slaAlerts : !autoAssign; }
    public void setSlaAlerts(boolean v) { slaAlerts = v; }
    public boolean isSlaWarningAlerts() { return slaWarningAlerts != null ? slaWarningAlerts : isSlaAlerts(); }
    public void setSlaWarningAlerts(boolean v) { slaWarningAlerts = v; }
    public boolean isSlaBreachAlerts() { return slaBreachAlerts != null ? slaBreachAlerts : isSlaAlerts(); }
    public void setSlaBreachAlerts(boolean v) { slaBreachAlerts = v; }
    public boolean isDryRun()           { return dryRun; }
    public void    setDryRun(boolean v) { this.dryRun = v; }
    public boolean isAutoAssign()           { return autoAssign; }
    public void    setAutoAssign(boolean v) { this.autoAssign = v; }
}
