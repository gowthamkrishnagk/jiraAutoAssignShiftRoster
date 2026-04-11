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

    List<AssignmentLog> findTop100ByOrderByAssignedAtDesc();

    @Modifying
    @Transactional
    @Query("DELETE FROM AssignmentLog l WHERE l.assignedAt < :cutoff")
    long deleteByAssignedAtBefore(@Param("cutoff") LocalDateTime cutoff);
}
