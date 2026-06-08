package com.jira.autoassign.repository;

import com.jira.autoassign.entity.B2bNotifyLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface B2bNotifyLogRepository extends JpaRepository<B2bNotifyLog, Long> {

    List<B2bNotifyLog> findTop20ByOrderByCreatedAtDesc();

    @Modifying
    @Transactional
    @Query("DELETE FROM B2bNotifyLog l WHERE l.createdAt < :cutoff")
    int deleteByCreatedAtBefore(@Param("cutoff") LocalDateTime cutoff);
}
