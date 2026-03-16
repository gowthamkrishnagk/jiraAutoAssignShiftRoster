package com.jira.autoassign.repository;

import com.jira.autoassign.entity.AssignmentLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AssignmentLogRepository extends JpaRepository<AssignmentLog, Long> {

    List<AssignmentLog> findTop100ByOrderByAssignedAtDesc();
}
