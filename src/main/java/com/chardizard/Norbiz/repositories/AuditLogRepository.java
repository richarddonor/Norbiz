package com.chardizard.Norbiz.repositories;

import com.chardizard.Norbiz.audit.AuditAction;
import com.chardizard.Norbiz.models.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    Page<AuditLog> findByEntityTypeAndEntityIdOrderByChangedAtDesc(String entityType, Long entityId, Pageable pageable);
    Page<AuditLog> findByEntityTypeOrderByChangedAtDesc(String entityType, Pageable pageable);
    Page<AuditLog> findByChangedByOrderByChangedAtDesc(String changedBy, Pageable pageable);
    Page<AuditLog> findByActionOrderByChangedAtDesc(AuditAction action, Pageable pageable);
}
