package com.jira.autoassign.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Records a ticket that was assigned to a COVER on behalf of a shift owner.
 *
 * Jira only knows "assignee = cover", so without this record it is impossible
 * to tell the owner's tickets a cover is holding apart from the cover's own
 * workload. When a cover is cleared/replaced, ONLY the tickets recorded here
 * are handed back to the owner (or moved to the new cover) — the cover's own
 * tickets are never touched.
 *
 * A row is deleted as soon as the ticket is assigned anywhere else by this
 * app, and aged out with the rest of the 7-day purge as a safety net.
 */
@Entity
@Table(name = "cover_assignment", indexes = {
    @Index(name = "idx_cover_issue_key", columnList = "issue_key"),
    @Index(name = "idx_cover_team_cover", columnList = "team_id, cover_email")
})
public class CoverAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "team_id", nullable = false)
    private String teamId;

    @Column(name = "issue_key", nullable = false)
    private String issueKey;

    @Column(name = "owner_email", nullable = false)
    private String ownerEmail;

    @Column(name = "cover_email", nullable = false)
    private String coverEmail;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public static CoverAssignment of(String teamId, String issueKey, String ownerEmail, String coverEmail) {
        CoverAssignment c = new CoverAssignment();
        c.teamId     = teamId;
        c.issueKey   = issueKey;
        c.ownerEmail = ownerEmail;
        c.coverEmail = coverEmail;
        c.createdAt  = LocalDateTime.now();
        return c;
    }

    public Long getId()                { return id; }
    public String getTeamId()          { return teamId; }
    public String getIssueKey()        { return issueKey; }
    public String getOwnerEmail()      { return ownerEmail; }
    public String getCoverEmail()      { return coverEmail; }
    public LocalDateTime getCreatedAt(){ return createdAt; }

    public void setCoverEmail(String coverEmail) { this.coverEmail = coverEmail; }
}
