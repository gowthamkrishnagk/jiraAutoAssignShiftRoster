package com.jira.autoassign.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Entity
@Table(name = "shift_roster", indexes = {
    @Index(name = "idx_roster_team_date", columnList = "team_id, shift_date")
})
public class ShiftRoster {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "team_id", nullable = false)
    private String teamId;

    @Column(nullable = false)
    private String email;

    @Column(name = "shift_date", nullable = false)
    private LocalDate shiftDate;

    @Column(name = "shift_start", nullable = false)
    private LocalTime shiftStart;

    @Column(name = "shift_end", nullable = false)
    private LocalTime shiftEnd;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public Long getId()                      { return id; }
    public void setId(Long id)               { this.id = id; }
    public String getTeamId()                { return teamId; }
    public void setTeamId(String v)          { this.teamId = v; }
    public String getEmail()                 { return email; }
    public void setEmail(String v)           { this.email = v; }
    public LocalDate getShiftDate()          { return shiftDate; }
    public void setShiftDate(LocalDate v)    { this.shiftDate = v; }
    public LocalTime getShiftStart()         { return shiftStart; }
    public void setShiftStart(LocalTime v)   { this.shiftStart = v; }
    public LocalTime getShiftEnd()           { return shiftEnd; }
    public void setShiftEnd(LocalTime v)     { this.shiftEnd = v; }
    public LocalDateTime getCreatedAt()      { return createdAt; }
    public void setCreatedAt(LocalDateTime v){ this.createdAt = v; }
}
