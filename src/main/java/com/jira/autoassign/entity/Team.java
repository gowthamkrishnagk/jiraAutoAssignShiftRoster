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

    public Team() {}

    public Team(String id, String name, String jql) {
        this.id   = id;
        this.name = name;
        this.jql  = jql;
    }

    public String getId()           { return id; }
    public void   setId(String v)   { this.id = v; }
    public String getName()         { return name; }
    public void   setName(String v) { this.name = v; }
    public String getJql()          { return jql; }
    public void   setJql(String v)  { this.jql = v; }
    public boolean isDryRun()           { return dryRun; }
    public void    setDryRun(boolean v) { this.dryRun = v; }
}
