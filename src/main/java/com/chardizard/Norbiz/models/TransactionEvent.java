package com.chardizard.Norbiz.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

// Not Auditable: the transaction history is append-only by design — same reasoning as
// InventoryMovement. Rows are never updated or deleted. See docs/TRANSACTION_ACTIONS.md.
@Getter
@Setter
@Entity
@Table(
    name = "transaction_events",
    // NULL action_definition_id (CREATED/VOIDED rows) is distinct in Postgres, so this only
    // constrains ACTION rows: the same user can take a given action once per transaction.
    uniqueConstraints = @UniqueConstraint(name = "TXN_EVENT_ACTION_USER_UQ",
        columnNames = {"transaction_type", "transaction_id", "action_definition_id", "performed_by"}),
    indexes = @Index(name = "TXN_EVENTS_TRANSACTION_IDX",
        columnList = "transaction_type, transaction_id, performed_at")
)
public class TransactionEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false,
        foreignKey = @ForeignKey(name = "TXN_EVENTS_COMPANY_ID_FK"))
    private Company company;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false, length = 50)
    private TransactionType transactionType;

    @Column(name = "transaction_id", nullable = false)
    private Long transactionId;

    @Column(name = "reference_number", nullable = false, length = 50)
    private String referenceNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 20)
    private TransactionEventType eventType;

    // Set only for ACTION events.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "action_definition_id",
        foreignKey = @ForeignKey(name = "TXN_EVENTS_ACTION_DEFINITION_ID_FK"))
    private TransactionActionDefinition actionDefinition;

    // Snapshots of the definition at the time of the action, so history survives renames.
    @Column(name = "action_code", length = 50)
    private String actionCode;

    @Column(name = "action_name", length = 255)
    private String actionName;

    @Column(name = "performed_by", nullable = false, length = 100)
    private String performedBy;

    @Column(name = "performed_at", nullable = false)
    private Instant performedAt;

    @Column(length = 255)
    private String remarks;
}
