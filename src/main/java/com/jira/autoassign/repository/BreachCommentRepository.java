package com.jira.autoassign.repository;

import com.jira.autoassign.entity.BreachComment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface BreachCommentRepository extends JpaRepository<BreachComment, Long> {

    Optional<BreachComment> findByIssueKey(String issueKey);

    List<BreachComment> findAll();

    /** Bulk-deletes breach comments older than the given cutoff. Returns rows removed. */
    @Modifying
    @Transactional
    @Query("DELETE FROM BreachComment b WHERE b.commentedAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
