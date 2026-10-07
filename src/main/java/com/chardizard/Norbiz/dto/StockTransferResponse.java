package com.chardizard.Norbiz.dto;

import com.chardizard.Norbiz.models.CustomerType;
import com.chardizard.Norbiz.models.TransactionOrigin;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Getter
@Setter
public class StockTransferResponse {
    private Long id;
    private Long companyId;
    private String companyName;
    private Long customerId;
    private String customerName;
    private CustomerType customerType;
    // The main warehouse the stock is set aside in.
    private Long warehouseId;
    private String warehouseName;
    private String referenceNumber;
    private String sheetNumber;
    private Instant transferDate;
    private String remarks;
    // Sum of line amounts — computed, not persisted.
    private BigDecimal totalAmount;
    // The (non-voided) Delivery Receipt that loaded this transfer, if any.
    private Long deliveryReceiptId;
    private String deliveryReceiptReferenceNumber;
    private Instant createdAt;
    private String createdBy;
    private boolean voided;
    private Instant voidedAt;
    private String voidedBy;
    private boolean loaded;
    private TransactionOrigin origin;
    private List<StockTransferLineResponse> lines;
}
