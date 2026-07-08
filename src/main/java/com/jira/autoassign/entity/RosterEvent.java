package com.jira.autoassign.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * One roster edit event, shown in the "Today's Roster Edits" history.
 * Structural changes only — breaks are deliberately NOT recorded here;
 * this is a transparency feed for roster edits, not a monitoring tool.
 *
 * ticketsJson optionally holds the ticket movements the edit caused, as a JSON
 * array of {"key": "SAC-123", "to": "email"} entries — appended by the cover
 * hand-back and by the off-shift sweep when it runs on the heels of an edit.
 */
@Entity
@Table(name = "roster_event", indexes = {
    @Index(name = "idx_roster_event_created", columnList = "created_at"),
    @Index(name = "idx_roster_event_team", columnList = "team_id, created_at")
})
public class RosterEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "team_id", nullable = false)
    private String teamId;

    /**
     * ROSTER_ADD, ROSTER_REMOVE, SHIFT_SWAP, EMAIL_REPLACE,
     * COVER_SET, COVER_CLEAR, ASSIGNEE_SWAP
     */
    @Column(nullable = false)
    private String action;

    /** The person the event is about (the shift owner). */
    @Column(nullable = false)
    private String email;

    /** Short human line, e.g. "15:30–00:00" or "covered by rajesh.sankar@…". */
    @Column(columnDefinition = "TEXT")
    private String detail;

    /** JSON array of {"key","to"} — tickets this edit moved. Null when none. */
    @Column(name = "tickets_json", columnDefinition = "TEXT")
    private String ticketsJson;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public static RosterEvent of(String teamId, String action, String email, String detail) {
        RosterEvent e = new RosterEvent();
        e.teamId    = teamId;
        e.action    = action;
        e.email     = email;
        e.detail    = detail;
        e.createdAt = LocalDateTime.now();
        return e;
    }

    public Long getId()                { return id; }
    public String getTeamId()          { return teamId; }
    public String getAction()          { return action; }
    public String getEmail()           { return email; }
    public String getDetail()          { return detail; }
    public String getTicketsJson()     { return ticketsJson; }
    public LocalDateTime getCreatedAt(){ return createdAt; }

    public void setTicketsJson(String ticketsJson) { this.ticketsJson = ticketsJson; }
}
