package com.chardizard.Norbiz.models;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

// Not Auditable: immutable once posted (only create/view/void) — the inventory ledger and the open-item allocation
// ledger are its audit trail.
// Takes items sold on one or more of a customer's Sales Invoices back into the main warehouse and credits the customer
// (a RECEIVABLE CREDIT for netAmount, applied to the returned invoices by default) — see docs/ACCOUNTING.md
// "Sales Invoice Return".
@Getter
@Setter
@Entity
@Table(
    name = "sales_invoice_returns",
    uniqueConstraints = @UniqueConstraint(name = "SALES_INVOICE_RETURNS_COMPANY_REFERENCE_UQ", columnNames = {"company_id", "reference_number"})
)
public class SalesInvoiceReturn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false,
        foreignKey = @ForeignKey(name = "SALES_INVOICE_RETURNS_COMPANY_ID_FK"))
    private Company company;

    // Counterparty — every returned invoice line must be on one of this customer's invoices.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false,
        foreignKey = @ForeignKey(name = "SALES_INVOICE_RETURNS_CUSTOMER_ID_FK"))
    private Customer customer;

    // The company's main warehouse the stock comes back into (snapshot).
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "warehouse_id", nullable = false,
        foreignKey = @ForeignKey(name = "SALES_INVOICE_RETURNS_WAREHOUSE_ID_FK"))
    private Warehouse warehouse;

    // Auto-generated per docs/TRANSACTIONS.md — see TransactionReferenceService.
    @Column(name = "reference_number", nullable = false, length = 50)
    private String referenceNumber;

    // User-supplied control number from the physical source document, if any.
    @Column(name = "sheet_number", length = 100)
    private String sheetNumber;

    // Business-effective date, represented as the UTC-midnight instant of that day (see InventoryMovement).
    @Column(name = "return_date", nullable = false)
    private Instant returnDate;

    @Column(name = "remarks", length = 255)
    private String remarks;

    // Stored at posting: the sum of the lines' amounts (quantity × the invoice line's net unit price), 2 decimals.
    @Column(name = "net_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal netAmount;

    // The RECEIVABLE CREDIT this return opened. Null when netAmount is zero (e.g. returned free goods).
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "open_item_id", unique = true,
        foreignKey = @ForeignKey(name = "SALES_INVOICE_RETURNS_OPEN_ITEM_ID_FK"))
    private OpenItem openItem;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    // Voiding is the only sanctioned way to cancel an immutable transaction — see docs/TRANSACTIONS.md "Voiding".
    @Column(name = "voided", nullable = false)
    private boolean voided = false;

    @Column(name = "voided_at")
    private Instant voidedAt;

    @Column(name = "voided_by", length = 100)
    private String voidedBy;

    // NATIVE for everything posted through the API; MIGRATED only from the legacy migration loader.
    // ColumnDefault: lets ddl-auto=update add the NOT NULL column to existing rows.
    @ColumnDefault("'NATIVE'")
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransactionOrigin origin = TransactionOrigin.NATIVE;

    @OneToMany(mappedBy = "salesInvoiceReturn", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNumber ASC")
    private List<SalesInvoiceReturnLine> lines = new ArrayList<>();
}
