package com.chardizard.Norbiz.dto;

import com.chardizard.Norbiz.models.AccountSide;
import com.chardizard.Norbiz.models.OpenItemDirection;
import com.chardizard.Norbiz.models.OpenItemSourceType;
import com.chardizard.Norbiz.models.OpenItemStatus;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

// A receivable/payable (CHARGE) or credit (CREDIT) in the AR/AP subledger.
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class OpenItemResponse {
    private Long id;
    private Long companyId;
    private AccountSide side;
    private Long customerId;
    private String customerName;
    private Long supplierId;
    private String supplierName;
    private OpenItemDirection direction;
    private OpenItemSourceType sourceType;
    private Long sourceId;
    private String referenceNumber;
    private Instant documentDate;
    private Instant dueDate;
    private BigDecimal amount;
    private BigDecimal appliedAmount;
    private BigDecimal pendingAmount;
    private BigDecimal bouncedAmount;
    private BigDecimal balance;
    private OpenItemStatus status;
    // CREDIT items: false while the paying instrument hasn't cleared.
    private boolean cleared;
}
