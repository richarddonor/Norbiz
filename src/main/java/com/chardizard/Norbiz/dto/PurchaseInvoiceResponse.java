package com.chardizard.Norbiz.dto;

import com.chardizard.Norbiz.models.PaymentStatus;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Getter
@Setter
public class PurchaseInvoiceResponse {
    private Long id;
    private Long companyId;
    private String companyName;
    private Long warehouseId;
    private String warehouseName;
    private Long supplierId;
    private String supplierName;
    // Null for Direct-mode invoices.
    private Long purchaseOrderId;
    private String purchaseOrderReferenceNumber;
    private String referenceNumber;
    private String sheetNumber;
    private Instant invoiceDate;
    private String remarks;
    private BigDecimal discountPercentage;
    // Sum of each line's lineNet, before the header-level discount. Null when the caller lacks VIEW_COST_PRICE.
    private BigDecimal itemsSubtotal;
    // itemsSubtotal * discountPercentage / 100. Null when the caller lacks VIEW_COST_PRICE.
    private BigDecimal discountAmount;
    // Sum of fee amounts — independent of cost price, always visible.
    private BigDecimal feesTotal;
    // (itemsSubtotal - discountAmount) + feesTotal. Null when the caller lacks VIEW_COST_PRICE.
    private BigDecimal netPayable;
    private PaymentStatus paymentStatus;
    private Instant createdAt;
    private String createdBy;
    private boolean voided;
    private Instant voidedAt;
    private String voidedBy;
    private boolean loaded;
    private List<PurchaseInvoiceLineResponse> lines;
    private List<PurchaseInvoiceFeeResponse> fees;
}
