package com.chardizard.Norbiz.models;

import com.chardizard.Norbiz.audit.AuditableEntityListener;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * Base class for all auditable entities.
 * Provides who/when tracking columns and plugs in the audit-log listener
 * that records field-level changes in JSON format.
 */
@MappedSuperclass
@EntityListeners({AuditingEntityListener.class,     AuditableEntityListener.class})
@Getter
@Setter
public abstract class Auditable {

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private Instant updatedAt;

    @CreatedBy
    @Column(name = "created_by", updatable = false, length = 100)
    private String createdBy;

    @LastModifiedBy
    @Column(name = "updated_by", length = 100)
    private String updatedBy;

    /**
     * Transient snapshot of the entity's scalar fields captured at @PostLoad.
     * Used by AuditableEntityListener to compute field-level diffs on UPDATE.
     * Never persisted to the database.
     */
    @Transient
    private String originalSnapshot;
}
