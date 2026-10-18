package com.chardizard.Norbiz.repositories;

import com.chardizard.Norbiz.audit.AuditAction;
import com.chardizard.Norbiz.models.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
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

    // Deleted records of one type: their DELETE logs, whose snapshot still holds the record's
    // company id, code and name. Filters are optional (null = no filter); allCompanies skips the
    // company scope (SUPER_ADMIN, and Users, which aren't owned by one company).
    String DELETED_FROM = """
            from audit_logs a
            cross join lateral (select cast(a.changes as jsonb) as snap) s
            where a.action = 'DELETE' and a.entity_type = :type
              and (:allCompanies or s.snap ->> 'company' in (:companyIds))
              and (cast(:code as text) is null or lower(coalesce(s.snap ->> 'code', s.snap ->> 'skuCode',
                       s.snap ->> 'employeeCode', s.snap ->> 'username', '')) like lower(concat('%', cast(:code as text), '%')))
              and (cast(:name as text) is null or lower(coalesce(s.snap ->> 'displayName', s.snap ->> 'name',
                       concat_ws(' ', s.snap ->> 'firstName', s.snap ->> 'lastName'))) like lower(concat('%', cast(:name as text), '%')))
              and (cast(:deletedBy as text) is null or lower(a.changed_by) like lower(concat('%', cast(:deletedBy as text), '%')))
              and (cast(:deletedFrom as timestamptz) is null or a.changed_at >= cast(:deletedFrom as timestamptz))
              and (cast(:deletedTo as timestamptz) is null or a.changed_at <= cast(:deletedTo as timestamptz))""";

    @Query(nativeQuery = true,
           value = "select a.* " + DELETED_FROM + " order by a.changed_at desc, a.id desc",
           countQuery = "select count(*) " + DELETED_FROM)
    Page<AuditLog> findDeleted(@Param("type") String entityType,
                               @Param("allCompanies") boolean allCompanies,
                               @Param("companyIds") Collection<String> companyIds,
                               @Param("code") String code,
                               @Param("name") String name,
                               @Param("deletedBy") String deletedBy,
                               @Param("deletedFrom") Instant deletedFrom,
                               @Param("deletedTo") Instant deletedTo,
                               Pageable pageable);
}
