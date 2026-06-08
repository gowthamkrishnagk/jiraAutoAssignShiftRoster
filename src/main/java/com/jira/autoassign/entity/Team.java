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

    @Column(name = "dry_run", nullable = false)
    private boolean dryRun = false;

    /**
     * Whether this team is auto-assigned round-robin by the shift scheduler.
     * Monitor-only teams (e.g. B2B) set this to false: they are never assigned,
     * only watched for assignee changes / support needs / SLA warnings.
     */
    @Column(name = "auto_assign", nullable = false)
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
    public boolean isDryRun()           { return dryRun; }
    public void    setDryRun(boolean v) { this.dryRun = v; }
    public boolean isAutoAssign()           { return autoAssign; }
    public void    setAutoAssign(boolean v) { this.autoAssign = v; }
}
