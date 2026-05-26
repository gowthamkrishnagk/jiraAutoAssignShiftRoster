package com.jira.autoassign.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Stores breach-reason comments posted to Jira tickets.
 * Auto-cleared on the 1st of every month by BreachCommentCleanupScheduler.
 */
@Entity
@Table(name = "breach_comment")
public class BreachComment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "issue_key", nullable = false, unique = true)
    private String issueKey;

    @Column(name = "reason", nullable = false)
    private String reason;

    @Column(name = "commented_at", nullable = false)
    private LocalDateTime commentedAt;

    public BreachComment() {}

    public BreachComment(String issueKey, String reason) {
        this.issueKey    = issueKey;
        this.reason      = reason;
        this.commentedAt = LocalDateTime.now();
    }

    public Long          getId()          { return id; }
    public String        getIssueKey()    { return issueKey; }
    public String        getReason()      { return reason; }
    public LocalDateTime getCommentedAt() { return commentedAt; }

    public void setReason(String reason)              { this.reason = reason; }
    public void setCommentedAt(LocalDateTime dt)      { this.commentedAt = dt; }
}
