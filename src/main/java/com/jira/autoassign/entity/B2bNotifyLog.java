package com.jira.autoassign.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * History of B2B notifications fired (reassignment, Matrixx/Aria support, SLA warning/breach).
 * Shown as the "recent activity" list in the B2B tab.
 */
@Entity
@Table(name = "b2b_notify_log")
public class B2bNotifyLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "issue_key")
    private String issueKey;

    /** Short type label, e.g. "Reassigned", "Matrixx Support", "SLA Warning", "SLA Breached". */
    @Column(name = "type")
    private String type;

    /** Assignee display name the card was about / @mentioned. */
    @Column(name = "assignee")
    private String assignee;

    /** Human detail line, e.g. "Reassigned: A → B" or "1h 30m remaining". */
    @Column(name = "detail", columnDefinition = "TEXT")
    private String detail;

    /** Whether the @mention was resolved to a Teams user. */
    @Column(name = "mentioned", nullable = false, columnDefinition = "boolean default false")
    private boolean mentioned = false;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public B2bNotifyLog() {}

    public B2bNotifyLog(String issueKey, String type, String assignee, String detail, boolean mentioned) {
        this.issueKey  = issueKey;
        this.type      = type;
        this.assignee  = assignee;
        this.detail    = detail;
        this.mentioned = mentioned;
        this.createdAt = LocalDateTime.now();
    }

    public Long          getId()        { return id; }
    public String        getIssueKey()  { return issueKey; }
    public String        getType()      { return type; }
    public String        getAssignee()  { return assignee; }
    public String        getDetail()    { return detail; }
    public boolean       isMentioned()  { return mentioned; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
