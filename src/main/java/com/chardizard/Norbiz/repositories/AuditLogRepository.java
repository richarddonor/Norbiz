package com.chardizard.Norbiz.repositories;

import com.chardizard.Norbiz.audit.AuditAction;
import com.chardizard.Norbiz.models.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    Page<AuditLog> findByEntityTypeAndEntityIdOrderByChangedAtDesc(String entityType, Long entityId, Pageable pageable);
    Page<AuditLog> findByEntityTypeOrderByChangedAtDesc(String entityType, Pageable pageable);
    Page<AuditLog> findByChangedByOrderByChangedAtDesc(String changedBy, Pageable pageable);
    Page<AuditLog> findByActionOrderByChangedAtDesc(AuditAction action, Pageable pageable);
    Optional<AuditLog> findFirstByEntityTypeAndEntityIdAndActionOrderByIdDesc(String entityType, Long entityId, AuditAction action);

    // A record's history, one row per change set (its own logs plus its @AuditParent children's),
    // newest first. Logs that predate change sets are keyed '#<id>', i.e. one change each.
    @Query(value = """
            select coalesce(a.changeSet, concat('#', cast(a.id as String))), max(a.changedAt)
            from AuditLog a
            where (a.entityType = :type and a.entityId = :id) or (a.parentType = :type and a.parentId = :id)
            group by coalesce(a.changeSet, concat('#', cast(a.id as String)))
            order by max(a.changedAt) desc""",
           countQuery = """
            select count(distinct coalesce(a.changeSet, concat('#', cast(a.id as String))))
            from AuditLog a
            where (a.entityType = :type and a.entityId = :id) or (a.parentType = :type and a.parentId = :id)""")
    Page<Object[]> findChangeSetKeys(@Param("type") String entityType, @Param("id") Long entityId, Pageable pageable);

    @Query("""
            select a from AuditLog a
            where ((a.entityType = :type and a.entityId = :id) or (a.parentType = :type and a.parentId = :id))
              and (a.changeSet in :changeSets or a.id in :ids)
            order by a.changedAt, a.id""")
    List<AuditLog> findInChangeSets(@Param("type") String entityType, @Param("id") Long entityId,
                                    @Param("changeSets") Collection<String> changeSets, @Param("ids") Collection<Long> ids);
}
