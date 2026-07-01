package com.jira.autoassign.repository;

import com.jira.autoassign.entity.AssignmentLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface AssignmentLogRepository extends JpaRepository<AssignmentLog, Long> {

    // id DESC is a stable tiebreaker: many logs saved in the same scheduler run share
    // an identical assignedAt, so ordering by time alone lets rows reshuffle between
    // refreshes. Adding id keeps the last-100 order deterministic.
    List<AssignmentLog> findTop100ByOrderByAssignedAtDescIdDesc();

    // Recent assignments for one team, newest first — powers the round-robin
    // "Assignment History" view. Filtered to ASSIGN so the cycle reads cleanly.
    List<AssignmentLog> findTop24ByTeamIdAndActionOrderByAssignedAtDescIdDesc(String teamId, String action);

    @Modifying
    @Transactional
    @Query("DELETE FROM AssignmentLog l WHERE l.assignedAt < :cutoff")
    long deleteByAssignedAtBefore(@Param("cutoff") LocalDateTime cutoff);
}
