package com.chardizard.Norbiz.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "document_templates")
public class DocumentTemplate extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false,
        foreignKey = @ForeignKey(name = "DOCUMENT_TEMPLATES_COMPANY_ID_FK"))
    private Company company;

    // Free-text convention shared with InventoryMovement.sourceType, e.g. "INVENTORY_ADJUSTMENT".
    @Column(name = "document_type", nullable = false, length = 50)
    private String documentType;

    @Column(nullable = false, length = 255)
    private String name;

    // Opaque JSON layout (element positions/sizes/bindings) — the frontend owns this
    // shape entirely; the backend never inspects or validates its contents. Deliberately
    // NOT subject to the general "strings under 255 chars" rule since this is structured
    // config data, not a display string.
    @Column(nullable = false, columnDefinition = "TEXT")
    private String layout;

    @Column(name = "is_default", nullable = false)
    private boolean defaultTemplate = false;

    @Column(nullable = false)
    private boolean active = true;
}
