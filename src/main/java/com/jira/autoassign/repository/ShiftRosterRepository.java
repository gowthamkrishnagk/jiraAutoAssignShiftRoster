package com.jira.autoassign.repository;

import com.jira.autoassign.entity.ShiftRoster;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public interface ShiftRosterRepository extends JpaRepository<ShiftRoster, Long> {

    /** Active shifts right now for a given team — handles overnight shifts. */
    @Query("""
        SELECT s FROM ShiftRoster s WHERE s.teamId = :teamId AND (
            (s.shiftStart <= s.shiftEnd
                AND s.shiftDate = :today
                AND s.shiftStart <= :now AND s.shiftEnd >= :now)
            OR
            (s.shiftStart > s.shiftEnd
                AND s.shiftDate = :today
                AND s.shiftStart <= :now)
            OR
            (s.shiftStart > s.shiftEnd
                AND s.shiftDate = :yesterday
                AND s.shiftEnd >= :now)
        )
        """)
    List<ShiftRoster> findActiveShifts(
        @Param("teamId")    String teamId,
        @Param("today")     LocalDate today,
        @Param("yesterday") LocalDate yesterday,
        @Param("now")       LocalTime now
    );

    /** Distinct emails with any shift on today or yesterday for a given team. */
    @Query("""
        SELECT DISTINCT s.email FROM ShiftRoster s
        WHERE s.teamId = :teamId
          AND (s.shiftDate = :today OR s.shiftDate = :yesterday)
        """)
    List<String> findAllEmailsInRange(
        @Param("teamId")    String teamId,
        @Param("today")     LocalDate today,
        @Param("yesterday") LocalDate yesterday
    );

    /** Shifts for a team within a date range, ordered by date then start time. */
    @Query("""
        SELECT s FROM ShiftRoster s
        WHERE s.teamId = :teamId
          AND s.shiftDate >= :start AND s.shiftDate <= :end
        ORDER BY s.shiftDate, s.shiftStart
        """)
    List<ShiftRoster> findByShiftDateBetween(
        @Param("teamId") String teamId,
        @Param("start")  LocalDate start,
        @Param("end")    LocalDate end
    );

    /** Delete a team's entries within a date range (used on re-upload). */
    @Modifying
    @Transactional
    @Query("""
        DELETE FROM ShiftRoster s
        WHERE s.teamId = :teamId
          AND s.shiftDate >= :start AND s.shiftDate < :end
        """)
    void deleteByMonthRange(
        @Param("teamId") String teamId,
        @Param("start")  LocalDate start,
        @Param("end")    LocalDate end
    );

    /** Purge old entries across all teams. */
    @Modifying
    @Transactional
    @Query("DELETE FROM ShiftRoster s WHERE s.shiftDate < :cutoff")
    long deleteByShiftDateBefore(@Param("cutoff") LocalDate cutoff);
}
