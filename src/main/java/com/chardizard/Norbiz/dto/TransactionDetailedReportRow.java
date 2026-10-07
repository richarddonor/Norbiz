package com.chardizard.Norbiz.dto;

import com.chardizard.Norbiz.models.TransactionOrigin;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One line item of a "&lt;Transaction&gt; - Detailed" report, flattened with its transaction's header.
 * The same shape serves every transaction type; fields a type doesn't have are null.
 */
@Getter
@Setter
public class TransactionDetailedReportRow {
    // Line id — unique per row.
    private Long id;
    private Long transactionId;
    private Long companyId;
    private String companyName;
    private String referenceNumber;
    private String sheetNumber;
    private Instant transactionDate;
    private Long warehouseId;
    private String warehouseName;
    // Supplier (purchases) or customer/outlet (sales); null for Inventory Adjustment, whose counterparty is the warehouse.
    private Long counterpartyId;
    private String counterpartyName;
    // Sales agent credited with the sale (Outlet Delivery Receipt / Return); null for other types.
    private Long agentId;
    private String agentName;
    // Reference number of the transaction this one was loaded from (PO/PI/DR/ODR); null when none.
    private String sourceReferenceNumber;
    private String remarks;
    private boolean voided;
    private Instant voidedAt;
    private String voidedBy;
    // NATIVE, or MIGRATED / RECONSTRUCTED for transactions written by the legacy migration.
    private TransactionOrigin origin;
    private Instant createdAt;
    private String createdBy;

    private Integer lineNumber;
    private Long itemId;
    private String itemCode;
    private String itemName;
    private BigDecimal quantity;
    private BigDecimal quantityLoaded;
    // Cost price for purchase transactions (null without VIEW_COST_PRICE), selling price for
    // Delivery Receipt / Outlet Receive / Outlet Delivery Receipt / Return, null for Inventory Adjustment.
    private BigDecimal price;
    // Line discount (Purchase Invoice only).
    private BigDecimal discountPercentage;
    // quantity * price * (1 - discountPercentage / 100); null whenever price is.
    private BigDecimal amount;
}
