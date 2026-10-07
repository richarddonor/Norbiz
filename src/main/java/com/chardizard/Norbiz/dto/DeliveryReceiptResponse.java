package com.chardizard.Norbiz.dto;

import com.chardizard.Norbiz.models.TransactionOrigin;
import com.chardizard.Norbiz.models.CustomerType;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Getter
@Setter
public class DeliveryReceiptResponse {
    private Long id;
    private Long companyId;
    private String companyName;
    private Long customerId;
    private String customerName;
    private CustomerType customerType;
    // Source (main) warehouse the stock left from.
    private Long warehouseId;
    private String warehouseName;
    // OUTLET only: the outlet warehouse holding the delivery in transit.
    private Long destinationWarehouseId;
    private String destinationWarehouseName;
    // Set when the receipt delivered a Stock Transfer (its lines were copied from it).
    private Long stockTransferId;
    private String stockTransferReferenceNumber;
    private String referenceNumber;
    private String sheetNumber;
    private Instant deliveryDate;
    private String remarks;
    // Sum of line amounts — computed, not persisted.
    private BigDecimal totalAmount;
    private Instant createdAt;
    private String createdBy;
    private boolean voided;
    private Instant voidedAt;
    private String voidedBy;
    private boolean loaded;
    private TransactionOrigin origin;
    private List<DeliveryReceiptLineResponse> lines;
}
