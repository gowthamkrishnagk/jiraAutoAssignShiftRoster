package com.jira.autoassign.repository;

import com.jira.autoassign.entity.B2bMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface B2bMemberRepository extends JpaRepository<B2bMember, Long> {

    Optional<B2bMember> findByJiraEmailIgnoreCase(String jiraEmail);

    Optional<B2bMember> findByJiraNameIgnoreCase(String jiraName);
}
