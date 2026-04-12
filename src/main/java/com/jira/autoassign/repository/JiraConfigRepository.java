package com.jira.autoassign.repository;

import com.jira.autoassign.entity.JiraConfig;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JiraConfigRepository extends JpaRepository<JiraConfig, Long> {}
