package com.jira.autoassign.repository;

import com.jira.autoassign.entity.CoverAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface CoverAssignmentRepository extends JpaRepository<CoverAssignment, Long> {

    List<CoverAssignment> findByTeamIdAndCoverEmailIgnoreCase(String teamId, String coverEmail);

    @Modifying
    @Transactional
    @Query("DELETE FROM CoverAssignment c WHERE c.issueKey = :issueKey")
    void deleteByIssueKey(@Param("issueKey") String issueKey);

    @Modifying
    @Transactional
    @Query("DELETE FROM CoverAssignment c WHERE c.createdAt < :cutoff")
    long deleteByCreatedAtBefore(@Param("cutoff") LocalDateTime cutoff);
}
