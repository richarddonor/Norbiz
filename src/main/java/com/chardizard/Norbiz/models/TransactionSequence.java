package com.chardizard.Norbiz.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

// Backs TransactionReferenceService's per-company, per-transaction-type numbering.
// Rows are only ever touched via the atomic upsert-increment native query in
// TransactionSequenceRepository — not through normal JPA save/update.
@Getter
@Setter
@Entity
@Table(
    name = "transaction_sequences",
    uniqueConstraints = @UniqueConstraint(name = "TRANSACTION_SEQUENCES_COMPANY_TYPE_UQ", columnNames = {"company_id", "transaction_type"})
)
public class TransactionSequence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false,
        foreignKey = @ForeignKey(name = "TRANSACTION_SEQUENCES_COMPANY_ID_FK"))
    private Company company;

    @Column(name = "transaction_type", nullable = false, length = 50)
    private String transactionType;

    @Column(name = "last_number", nullable = false)
    private Long lastNumber;
}
