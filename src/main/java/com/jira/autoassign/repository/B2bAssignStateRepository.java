package com.jira.autoassign.repository;

import com.jira.autoassign.entity.B2bAssignState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

public interface B2bAssignStateRepository extends JpaRepository<B2bAssignState, String> {

    /** Bulk-deletes dedupe state rows not touched since the given cutoff. Returns rows removed. */
    @Modifying
    @Transactional
    @Query("DELETE FROM B2bAssignState s WHERE s.updatedAt < :cutoff")
    int deleteByUpdatedAtBefore(@Param("cutoff") LocalDateTime cutoff);
}
