package com.chardizard.Norbiz.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

// Per-company AR/AP settings (one row per company). startDate is the go-live cut-off: invoices dated on/after
// it are tracked as open items; balances from before it are entered as OPENING_BALANCE memos.
@Getter
@Setter
@Entity
@Table(
    name = "accounting_settings",
    uniqueConstraints = @UniqueConstraint(name = "ACCOUNTING_SETTINGS_COMPANY_UQ", columnNames = {"company_id"})
)
public class AccountingSettings extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false,
        foreignKey = @ForeignKey(name = "ACCOUNTING_SETTINGS_COMPANY_ID_FK"))
    private Company company;

    // UTC-midnight instant of the cut-off day.
    @Column(name = "start_date", nullable = false)
    private Instant startDate;

    // Days a deposited check usually takes to clear (legacy tblCheckTypes "Local" = 3). Informational:
    // used for the expected clearing date shown beside received checks.
    @Column(name = "check_clearing_days", nullable = false)
    private int checkClearingDays = 3;
}
