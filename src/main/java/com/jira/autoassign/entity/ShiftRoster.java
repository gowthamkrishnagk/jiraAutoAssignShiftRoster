package com.jira.autoassign.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Entity
@Table(name = "shift_roster", indexes = {
    @Index(name = "idx_email",       columnList = "email"),
    @Index(name = "idx_shift_date",  columnList = "shift_date")
})
public class ShiftRoster {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String email;

    @Column(name = "shift_date", nullable = false)
    private LocalDate shiftDate;

    @Column(name = "shift_start", nullable = false)
    private LocalTime shiftStart;

    @Column(name = "shift_end", nullable = false)
    private LocalTime shiftEnd;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public Long getId()               { return id; }
    public String getEmail()          { return email; }
    public void setEmail(String v)    { this.email = v; }
    public LocalDate getShiftDate()   { return shiftDate; }
    public void setShiftDate(LocalDate v) { this.shiftDate = v; }
    public LocalTime getShiftStart()  { return shiftStart; }
    public void setShiftStart(LocalTime v) { this.shiftStart = v; }
    public LocalTime getShiftEnd()    { return shiftEnd; }
    public void setShiftEnd(LocalTime v) { this.shiftEnd = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
