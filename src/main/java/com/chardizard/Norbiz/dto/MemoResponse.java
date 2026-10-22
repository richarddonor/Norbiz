package com.chardizard.Norbiz.dto;

import com.chardizard.Norbiz.models.MemoType;
import com.chardizard.Norbiz.models.OpenItemDirection;
import com.chardizard.Norbiz.models.TransactionOrigin;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

// Customer or supplier memo — the other side's party fields are null.
@Getter
@Setter
public class MemoResponse {
    private Long id;
    private Long companyId;
    private String companyName;
    private Long customerId;
    private String customerName;
    private Long supplierId;
    private String supplierName;
    private MemoType memoType;
    private String reason;
    private String referenceNumber;
    private String sheetNumber;
    private Instant memoDate;
    private int termDays;
    private Instant dueDate;
    private String originalReference;
    private String remarks;
    private BigDecimal amount;
    // Whether the memo raised (CHARGE) or lowered (CREDIT) the balance.
    private OpenItemDirection direction;
    private Long openItemId;
    private BigDecimal appliedAmount;
    private BigDecimal balance;
    private Instant createdAt;
    private String createdBy;
    private boolean voided;
    private Instant voidedAt;
    private String voidedBy;
    private TransactionOrigin origin;
    private List<MemoLineResponse> lines;
    // Applications this memo made when posted (credit-side memos).
    private List<AllocationResponse> applications;
}
