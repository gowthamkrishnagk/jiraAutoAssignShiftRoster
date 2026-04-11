package com.jira.autoassign.repository;

import com.jira.autoassign.entity.Team;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeamRepository extends JpaRepository<Team, String> {
    // findAll(), findById(), save(), deleteById(), existsById() — all provided by JpaRepository
}
