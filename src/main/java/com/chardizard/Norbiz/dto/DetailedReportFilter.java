package com.chardizard.Norbiz.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Query-string filters shared by every "&lt;Transaction&gt; - Detailed" report. Text filters are
 * case-insensitive "contains", all filters are AND-ed. A counterparty filter the transaction type
 * doesn't have (e.g. supplierId on Inventory Adjustment) is rejected with 400.
 */
@Getter
@Setter
public class DetailedReportFilter {

    private static final String DATE = "^\\d{4}-\\d{2}-\\d{2}$";

    @Schema(description = "Filter by warehouse ID")
    @Positive
    private Long warehouseId;

    @Schema(description = "Filter by supplier ID (Purchase Order, Purchase Invoice, Purchase Receive)")
    @Positive
    private Long supplierId;

    @Schema(description = "Filter by customer ID (Delivery Receipt, Outlet Receive, Outlet Delivery Receipt, Outlet Delivery Return)")
    @Positive
    private Long customerId;

    @Schema(description = "Filter by agent (employee) ID (Outlet Delivery Receipt, Outlet Delivery Return)")
    @Positive
    private Long agentId;

    @Schema(description = "Filter by item ID")
    @Positive
    private Long itemId;

    @Schema(description = "Filter by voided status; omit for both")
    private Boolean voided;

    @Schema(description = "Reference number (contains)")
    @Size(max = 255)
    private String referenceNumber;

    @Schema(description = "Sheet number (contains)")
    @Size(max = 255)
    private String sheetNumber;

    @Schema(description = "Source transaction reference number, e.g. the PO a Purchase Invoice was loaded from (contains)")
    @Size(max = 255)
    private String sourceReferenceNumber;

    @Schema(description = "Warehouse name (contains)")
    @Size(max = 255)
    private String warehouse;

    @Schema(description = "Supplier/customer name (contains)")
    @Size(max = 255)
    private String counterparty;

    @Schema(description = "Item code (contains)")
    @Size(max = 255)
    private String itemCode;

    @Schema(description = "Item name (contains)")
    @Size(max = 255)
    private String itemName;

    @Schema(description = "Remarks (contains)")
    @Size(max = 255)
    private String remarks;

    @Schema(description = "Transaction date, range start (yyyy-MM-dd, inclusive)")
    @Pattern(regexp = DATE, message = "must be yyyy-MM-dd")
    private String dateFrom;

    @Schema(description = "Transaction date, range end (yyyy-MM-dd, inclusive)")
    @Pattern(regexp = DATE, message = "must be yyyy-MM-dd")
    private String dateTo;
}
