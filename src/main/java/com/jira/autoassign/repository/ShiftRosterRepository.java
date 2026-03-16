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

    /**
     * Returns shifts that are active RIGHT NOW.
     *
     * Three cases:
     *  1. Regular (non-overnight):  shift_date=today,     start <= now <= end,  start < end
     *  2. Overnight first half:     shift_date=today,     start <= now,         start > end
     *  3. Overnight second half:    shift_date=yesterday, now <= end,            start > end
     */
    @Query("""
        SELECT s FROM ShiftRoster s WHERE
          (s.shiftDate = :today     AND s.shiftStart <= s.shiftEnd AND s.shiftStart <= :now AND s.shiftEnd >= :now)
          OR
          (s.shiftDate = :today     AND s.shiftStart > s.shiftEnd AND s.shiftStart <= :now)
          OR
          (s.shiftDate = :yesterday AND s.shiftStart > s.shiftEnd AND s.shiftEnd >= :now)
        """)
    List<ShiftRoster> findActiveShifts(
        @Param("today")     LocalDate today,
        @Param("yesterday") LocalDate yesterday,
        @Param("now")       LocalTime now
    );

    /** All distinct emails that have any shift entry for today or yesterday (for unassign sweep). */
    @Query("""
        SELECT DISTINCT s.email FROM ShiftRoster s
        WHERE s.shiftDate = :today OR s.shiftDate = :yesterday
        """)
    List<String> findAllEmailsInRange(
        @Param("today")     LocalDate today,
        @Param("yesterday") LocalDate yesterday
    );

    /** Ordered list of shifts for a date range (used by UI schedule endpoint). */
    List<ShiftRoster> findByShiftDateBetweenOrderByShiftDateAscShiftStartAsc(
        LocalDate start, LocalDate end
    );

    /** Delete all entries for a given month so re-uploading replaces them cleanly. */
    @Modifying
    @Transactional
    @Query("DELETE FROM ShiftRoster s WHERE s.shiftDate >= :start AND s.shiftDate < :end")
    void deleteByMonthRange(@Param("start") LocalDate start, @Param("end") LocalDate end);
}
