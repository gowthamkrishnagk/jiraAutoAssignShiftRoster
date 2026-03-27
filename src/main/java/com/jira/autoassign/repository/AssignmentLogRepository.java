package com.jira.autoassign.repository;

import com.jira.autoassign.entity.AssignmentLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface AssignmentLogRepository extends JpaRepository<AssignmentLog, Long> {

    List<AssignmentLog> findTop100ByOrderByAssignedAtDesc();

    @Modifying
    @Transactional
    long deleteByAssignedAtBefore(LocalDateTime cutoff);
}
