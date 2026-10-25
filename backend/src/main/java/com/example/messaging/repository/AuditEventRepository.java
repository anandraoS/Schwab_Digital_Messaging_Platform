package com.example.messaging.repository;

import com.example.messaging.domain.AuditEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface AuditEventRepository extends JpaRepository<AuditEvent, String>, JpaSpecificationExecutor<AuditEvent> {

    List<AuditEvent> findByEntityTypeAndEntityIdOrderByOccurredAtAsc(String entityType, String entityId);

    long countByEntityTypeAndEntityIdAndAction(String entityType, String entityId, String action);

    java.util.Optional<AuditEvent> findFirstByEntityTypeAndEntityIdAndActionOrderByOccurredAtDesc(String entityType,
            String entityId, String action);
}
