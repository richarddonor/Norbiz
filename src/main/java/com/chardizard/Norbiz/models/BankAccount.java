package com.chardizard.Norbiz.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

// One of the company's own bank accounts: where deposits go, what our checks are drawn on, and what a
// Bank Reconciliation reconciles. Its book is the BankEntry ledger.
@Getter
@Setter
@Entity
@Table(
    name = "bank_accounts",
    uniqueConstraints = @UniqueConstraint(name = "BANK_ACCOUNTS_COMPANY_NUMBER_UQ", columnNames = {"company_id", "account_number"})
)
public class BankAccount extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false,
        foreignKey = @ForeignKey(name = "BANK_ACCOUNTS_COMPANY_ID_FK"))
    private Company company;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bank_id", nullable = false,
        foreignKey = @ForeignKey(name = "BANK_ACCOUNTS_BANK_ID_FK"))
    private Bank bank;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(name = "account_number", nullable = false, length = 50)
    private String accountNumber;

    // Balance per bank as of openingBalanceDate; the first reconciliation starts from it.
    @Column(name = "opening_balance", nullable = false, precision = 19, scale = 2)
    private BigDecimal openingBalance = BigDecimal.ZERO;

    @Column(name = "opening_balance_date")
    private Instant openingBalanceDate;

    @Column(nullable = false)
    private boolean active = true;
}
