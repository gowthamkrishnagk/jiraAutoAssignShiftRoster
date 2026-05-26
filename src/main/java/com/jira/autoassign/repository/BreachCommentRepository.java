package com.jira.autoassign.repository;

import com.jira.autoassign.entity.BreachComment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BreachCommentRepository extends JpaRepository<BreachComment, Long> {

    Optional<BreachComment> findByIssueKey(String issueKey);

    List<BreachComment> findAll();
}
