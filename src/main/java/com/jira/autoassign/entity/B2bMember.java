package com.jira.autoassign.entity;

import jakarta.persistence.*;

/**
 * Maps a Jira user to their Microsoft Teams identity for B2B @mentions, and tags
 * whether they belong to the Matrixx and/or Aria support groups.
 *
 * Jira Cloud frequently hides the assignee's email in search results, so matching
 * falls back to the Jira display name — store both jiraEmail and jiraName.
 */
@Entity
@Table(name = "b2b_member")
public class B2bMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Jira account email (lowercased). May be blank if only the display name is known. */
    @Column(name = "jira_email")
    private String jiraEmail;

    /** Jira display name — used to match when Jira hides the email. */
    @Column(name = "jira_name")
    private String jiraName;

    /** Teams email / UPN used as the @mention id. */
    @Column(name = "teams_email")
    private String teamsEmail;

    /** Teams display name shown in the @mention. */
    @Column(name = "teams_name")
    private String teamsName;

    @Column(name = "matrixx_support", nullable = false)
    private boolean matrixxSupport = false;

    @Column(name = "aria_support", nullable = false)
    private boolean ariaSupport = false;

    public B2bMember() {}

    public Long    getId()                       { return id; }
    public void    setId(Long v)                 { this.id = v; }
    public String  getJiraEmail()                { return jiraEmail; }
    public void    setJiraEmail(String v)        { this.jiraEmail = v; }
    public String  getJiraName()                 { return jiraName; }
    public void    setJiraName(String v)         { this.jiraName = v; }
    public String  getTeamsEmail()               { return teamsEmail; }
    public void    setTeamsEmail(String v)       { this.teamsEmail = v; }
    public String  getTeamsName()                { return teamsName; }
    public void    setTeamsName(String v)        { this.teamsName = v; }
    public boolean isMatrixxSupport()            { return matrixxSupport; }
    public void    setMatrixxSupport(boolean v)  { this.matrixxSupport = v; }
    public boolean isAriaSupport()               { return ariaSupport; }
    public void    setAriaSupport(boolean v)     { this.ariaSupport = v; }
}
