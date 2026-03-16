package com.jira.autoassign.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "assignment_log", indexes = {
    @Index(name = "idx_log_assigned_at", columnList = "assigned_at")
})
public class AssignmentLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "issue_key", nullable = false, length = 50)
    private String issueKey;

    @Column(name = "issue_summary", length = 500)
    private String issueSummary;

    @Column(name = "assigned_to_email", nullable = false, length = 255)
    private String assignedToEmail;

    @Column(name = "assigned_at", nullable = false)
    private LocalDateTime assignedAt;

    @Column(name = "action", length = 20)
    private String action; // ASSIGN or UNASSIGN

    public static AssignmentLog ofAssign(String issueKey, String issueSummary, String email) {
        AssignmentLog log = new AssignmentLog();
        log.issueKey = issueKey;
        log.issueSummary = issueSummary;
        log.assignedToEmail = email;
        log.assignedAt = LocalDateTime.now();
        log.action = "ASSIGN";
        return log;
    }

    public static AssignmentLog ofUnassign(String issueKey, String issueSummary, String email) {
        AssignmentLog log = new AssignmentLog();
        log.issueKey = issueKey;
        log.issueSummary = issueSummary;
        log.assignedToEmail = email;
        log.assignedAt = LocalDateTime.now();
        log.action = "UNASSIGN";
        return log;
    }

    public Long getId()                  { return id; }
    public String getIssueKey()          { return issueKey; }
    public String getIssueSummary()      { return issueSummary; }
    public String getAssignedToEmail()   { return assignedToEmail; }
    public LocalDateTime getAssignedAt() { return assignedAt; }
    public String getAction()            { return action; }
}
