package com.jira.autoassign.repository;

import com.jira.autoassign.entity.RosterEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface RosterEventRepository extends JpaRepository<RosterEvent, Long> {

    List<RosterEvent> findByTeamIdAndCreatedAtAfterOrderByCreatedAtDescIdDesc(
            String teamId, LocalDateTime after);

    // Recent edit events for one person — used to attach sweep-caused ticket
    // movements to the roster edit that triggered them.
    List<RosterEvent> findTop3ByTeamIdAndEmailIgnoreCaseAndActionInAndCreatedAtAfterOrderByCreatedAtDescIdDesc(
            String teamId, String email, List<String> actions, LocalDateTime after);

    @Modifying
    @Transactional
    @Query("DELETE FROM RosterEvent e WHERE e.createdAt < :cutoff")
    long deleteByCreatedAtBefore(@Param("cutoff") LocalDateTime cutoff);
}
