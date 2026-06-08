package com.jira.autoassign.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Per-ticket dedupe state for B2B notifications. One row per Jira issue key.
 *
 * Tracks the last-seen assignee (to detect reassignments) and which one-shot
 * notifications have already fired (support need, SLA breach warning) so the
 * scheduler doesn't re-notify every minute.
 */
@Entity
@Table(name = "b2b_assign_state")
public class B2bAssignState {

    @Id
    @Column(name = "issue_key")
    private String issueKey;

    /** Account id of the last-seen assignee (null/blank = unassigned). */
    @Column(name = "account_id")
    private String accountId;

    /** Display name of the last-seen assignee — used as the "from" in reassign cards. */
    @Column(name = "assignee_name")
    private String assigneeName;

    /** Which support message has been sent for the current assignee: NONE / MATRIXX / ARIA. */
    @Column(name = "support_notified", nullable = false)
    private String supportNotified = "NONE";

    /** Whether the 15-minute pre-breach SLA warning has been sent for the current assignee. */
    @Column(name = "sla_warned", nullable = false)
    private boolean slaWarned = false;

    /** Whether the "SLA breached" alert has been sent for the current assignee. */
    @Column(name = "sla_breach_notified", nullable = false, columnDefinition = "boolean default false")
    private boolean slaBreachNotified = false;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    public B2bAssignState() {}

    public B2bAssignState(String issueKey) {
        this.issueKey = issueKey;
    }

    public String        getIssueKey()              { return issueKey; }
    public void          setIssueKey(String v)      { this.issueKey = v; }
    public String        getAccountId()             { return accountId; }
    public void          setAccountId(String v)     { this.accountId = v; }
    public String        getAssigneeName()          { return assigneeName; }
    public void          setAssigneeName(String v)  { this.assigneeName = v; }
    public String        getSupportNotified()       { return supportNotified; }
    public void          setSupportNotified(String v){ this.supportNotified = v; }
    public boolean       isSlaWarned()              { return slaWarned; }
    public void          setSlaWarned(boolean v)    { this.slaWarned = v; }
    public boolean       isSlaBreachNotified()           { return slaBreachNotified; }
    public void          setSlaBreachNotified(boolean v) { this.slaBreachNotified = v; }
    public LocalDateTime getUpdatedAt()             { return updatedAt; }
    public void          setUpdatedAt(LocalDateTime v) { this.updatedAt = v; }
}
