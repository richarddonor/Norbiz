package com.chardizard.Norbiz.controllers;

import com.chardizard.Norbiz.dto.AppResponse;
import com.chardizard.Norbiz.dto.BillOfMaterialLookupResponse;
import com.chardizard.Norbiz.dto.CustomerLookupResponse;
import com.chardizard.Norbiz.dto.ItemLookupResponse;
import com.chardizard.Norbiz.dto.LookupResponse;
import com.chardizard.Norbiz.dto.PageResponse;
import com.chardizard.Norbiz.dto.StockLookupResponse;
import com.chardizard.Norbiz.dto.TransactionLookupResponse;
import com.chardizard.Norbiz.models.CustomerType;
import com.chardizard.Norbiz.models.EmployeeTag;
import com.chardizard.Norbiz.models.ItemTag;
import com.chardizard.Norbiz.services.LookupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// Slim, searchable dropdown data for forms. Each lookup is open to the entity's VIEW_ permission
// OR any permission whose form references that entity — see LookupAccess for the mapping.
@Tag(name = "Lookups", description = "Dropdown data (id/code/name only) for transaction and master-data forms. "
        + "Accessible with the entity's VIEW_ permission or any permission whose form needs it (see LookupAccess).")
@RestController
@RequestMapping("/lookups")
@RequiredArgsConstructor
public class LookupController {

    private static final String Q_DESC = "Search text (contains, case-insensitive) over code and name";
    private static final String COMPANY_HEADER = "X-Company-Id";
    private static final String COMPANY_DESC = "Company whose options to return (must be one of the caller's companies). "
            + "Defaults to the X-Company-Id header (the session's active company); one of the two is required.";
    private static final String ACTIVE_DESC = "Return only active records (default true)";
    private static final int MAX_STOCK_ITEMS = 500;

    private final LookupService lookupService;

    // ---- suppliers ----

    @Operation(summary = "Supplier dropdown")
    @ApiResponse(responseCode = "200", description = "Supplier options returned")
    @ApiResponse(responseCode = "403", description = "No qualifying permission or no access to company")
    @GetMapping("/suppliers")
    @PreAuthorize("@lookupAccess.can(authentication, 'SUPPLIER')")
    public ResponseEntity<AppResponse<PageResponse<LookupResponse>>> suppliers(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = COMPANY_DESC) @RequestParam(required = false) Long companyId,
            @Parameter(hidden = true) @RequestHeader(value = COMPANY_HEADER, required = false) Long headerCompanyId,
            @Parameter(description = Q_DESC) @RequestParam(required = false) @Size(max = 255) String q,
            @Parameter(description = ACTIVE_DESC) @RequestParam(defaultValue = "true") boolean activeOnly,
            Pageable pageable) {
        return ok(lookupService.suppliers(userDetails.getUsername(), company(companyId, headerCompanyId), q, activeOnly, pageable));
    }

    @Operation(summary = "Supplier option by ID", description = "Resolves a selected value (including inactive ones) for display.")
    @ApiResponse(responseCode = "200", description = "Supplier option returned")
    @ApiResponse(responseCode = "403", description = "No qualifying permission or no access to company")
    @GetMapping("/suppliers/{id}")
    @PreAuthorize("@lookupAccess.can(authentication, 'SUPPLIER')")
    public ResponseEntity<AppResponse<LookupResponse>> supplier(@PathVariable Long id, @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(lookupService.supplier(id, userDetails.getUsername())));
    }

    // ---- customers ----

    @Operation(summary = "Customer dropdown", description = "type is CUSTOMER or OUTLET; warehouseId/warehouseName are the outlet's own warehouse (null for a plain customer).")
    @ApiResponse(responseCode = "200", description = "Customer options returned")
    @ApiResponse(responseCode = "403", description = "No qualifying permission or no access to company")
    @GetMapping("/customers")
    @PreAuthorize("@lookupAccess.can(authentication, 'CUSTOMER')")
    public ResponseEntity<AppResponse<PageResponse<CustomerLookupResponse>>> customers(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = COMPANY_DESC) @RequestParam(required = false) Long companyId,
            @Parameter(hidden = true) @RequestHeader(value = COMPANY_HEADER, required = false) Long headerCompanyId,
            @Parameter(description = Q_DESC) @RequestParam(required = false) @Size(max = 255) String q,
            @Parameter(description = "Filter by customer type (CUSTOMER or OUTLET); omit for both") @RequestParam(required = false) CustomerType type,
            @Parameter(description = ACTIVE_DESC) @RequestParam(defaultValue = "true") boolean activeOnly,
            Pageable pageable) {
        return ok(lookupService.customers(userDetails.getUsername(), company(companyId, headerCompanyId), q, type, activeOnly, pageable));
    }

    @Operation(summary = "Customer option by ID")
    @ApiResponse(responseCode = "200", description = "Customer option returned")
    @ApiResponse(responseCode = "403", description = "No qualifying permission or no access to company")
    @GetMapping("/customers/{id}")
    @PreAuthorize("@lookupAccess.can(authentication, 'CUSTOMER')")
    public ResponseEntity<AppResponse<CustomerLookupResponse>> customer(@PathVariable Long id, @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(lookupService.customer(id, userDetails.getUsername())));
    }

    // ---- warehouses ----

    @Operation(summary = "Warehouse dropdown")
    @ApiResponse(responseCode = "200", description = "Warehouse options returned")
    @ApiResponse(responseCode = "403", description = "No qualifying permission or no access to company")
    @GetMapping("/warehouses")
    @PreAuthorize("@lookupAccess.can(authentication, 'WAREHOUSE')")
    public ResponseEntity<AppResponse<PageResponse<LookupResponse>>> warehouses(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = COMPANY_DESC) @RequestParam(required = false) Long companyId,
            @Parameter(hidden = true) @RequestHeader(value = COMPANY_HEADER, required = false) Long headerCompanyId,
            @Parameter(description = Q_DESC) @RequestParam(required = false) @Size(max = 255) String q,
            @Parameter(description = "Only the company's main warehouse (the Delivery Receipt source)") @RequestParam(defaultValue = "false") boolean mainOnly,
            @Parameter(description = ACTIVE_DESC) @RequestParam(defaultValue = "true") boolean activeOnly,
            Pageable pageable) {
        return ok(lookupService.warehouses(userDetails.getUsername(), company(companyId, headerCompanyId), q, mainOnly, activeOnly, pageable));
    }

    @Operation(summary = "Warehouse option by ID")
    @ApiResponse(responseCode = "200", description = "Warehouse option returned")
    @ApiResponse(responseCode = "403", description = "No qualifying permission or no access to company")
    @GetMapping("/warehouses/{id}")
    @PreAuthorize("@lookupAccess.can(authentication, 'WAREHOUSE')")
    public ResponseEntity<AppResponse<LookupResponse>> warehouse(@PathVariable Long id, @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(lookupService.warehouse(id, userDetails.getUsername())));
    }

    // ---- items ----

    @Operation(summary = "Item dropdown", description = "code is the item code. costPrice is populated only for callers with VIEW_COST_PRICE; no other prices are exposed.")
    @ApiResponse(responseCode = "200", description = "Item options returned")
    @ApiResponse(responseCode = "403", description = "No qualifying permission or no access to company")
    @GetMapping("/items")
    @PreAuthorize("@lookupAccess.can(authentication, 'ITEM')")
    public ResponseEntity<AppResponse<PageResponse<ItemLookupResponse>>> items(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = COMPANY_DESC) @RequestParam(required = false) Long companyId,
            @Parameter(hidden = true) @RequestHeader(value = COMPANY_HEADER, required = false) Long headerCompanyId,
            @Parameter(description = Q_DESC) @RequestParam(required = false) @Size(max = 255) String q,
            @Parameter(description = "Only items carrying this tag (e.g. INVENTORY)") @RequestParam(required = false) ItemTag tag,
            @Parameter(description = ACTIVE_DESC) @RequestParam(defaultValue = "true") boolean activeOnly,
            Authentication authentication,
            Pageable pageable) {
        return ok(lookupService.items(userDetails.getUsername(), company(companyId, headerCompanyId), q, tag, activeOnly,
                canViewCostPrice(authentication), pageable));
    }

    @Operation(summary = "Item option by ID")
    @ApiResponse(responseCode = "200", description = "Item option returned")
    @ApiResponse(responseCode = "403", description = "No qualifying permission or no access to company")
    @GetMapping("/items/{id}")
    @PreAuthorize("@lookupAccess.can(authentication, 'ITEM')")
    public ResponseEntity<AppResponse<ItemLookupResponse>> item(@PathVariable Long id, @AuthenticationPrincipal UserDetails userDetails,
                                                               Authentication authentication) {
        return ResponseEntity.ok(AppResponse.of(lookupService.item(id, userDetails.getUsername(), canViewCostPrice(authentication))));
    }

    // ---- item categories ----

    @Operation(summary = "Item category dropdown")
    @ApiResponse(responseCode = "200", description = "Item category options returned")
    @ApiResponse(responseCode = "403", description = "No qualifying permission or no access to company")
    @GetMapping("/item-categories")
    @PreAuthorize("@lookupAccess.can(authentication, 'ITEM_CATEGORY')")
    public ResponseEntity<AppResponse<PageResponse<LookupResponse>>> itemCategories(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = COMPANY_DESC) @RequestParam(required = false) Long companyId,
            @Parameter(hidden = true) @RequestHeader(value = COMPANY_HEADER, required = false) Long headerCompanyId,
            @Parameter(description = "Search text (contains, case-insensitive) over name") @RequestParam(required = false) @Size(max = 255) String q,
            Pageable pageable) {
        return ok(lookupService.itemCategories(userDetails.getUsername(), company(companyId, headerCompanyId), q, pageable));
    }

    @Operation(summary = "Item category option by ID")
    @ApiResponse(responseCode = "200", description = "Item category option returned")
    @ApiResponse(responseCode = "403", description = "No qualifying permission or no access to company")
    @GetMapping("/item-categories/{id}")
    @PreAuthorize("@lookupAccess.can(authentication, 'ITEM_CATEGORY')")
    public ResponseEntity<AppResponse<LookupResponse>> itemCategory(@PathVariable Long id, @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(lookupService.itemCategory(id, userDetails.getUsername())));
    }

    // ---- item groups ----

    @Operation(summary = "Item group dropdown", description = "code is the group's BN initials.")
    @ApiResponse(responseCode = "200", description = "Item group options returned")
    @ApiResponse(responseCode = "403", description = "No qualifying permission or no access to company")
    @GetMapping("/item-groups")
    @PreAuthorize("@lookupAccess.can(authentication, 'ITEM_GROUP')")
    public ResponseEntity<AppResponse<PageResponse<LookupResponse>>> itemGroups(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = COMPANY_DESC) @RequestParam(required = false) Long companyId,
            @Parameter(hidden = true) @RequestHeader(value = COMPANY_HEADER, required = false) Long headerCompanyId,
            @Parameter(description = "Search text (contains, case-insensitive) over name") @RequestParam(required = false) @Size(max = 255) String q,
            @Parameter(description = ACTIVE_DESC) @RequestParam(defaultValue = "true") boolean activeOnly,
            Pageable pageable) {
        return ok(lookupService.itemGroups(userDetails.getUsername(), company(companyId, headerCompanyId), q, activeOnly, pageable));
    }

    @Operation(summary = "Item group option by ID")
    @ApiResponse(responseCode = "200", description = "Item group option returned")
    @ApiResponse(responseCode = "403", description = "No qualifying permission or no access to company")
    @GetMapping("/item-groups/{id}")
    @PreAuthorize("@lookupAccess.can(authentication, 'ITEM_GROUP')")
    public ResponseEntity<AppResponse<LookupResponse>> itemGroup(@PathVariable Long id, @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(lookupService.itemGroup(id, userDetails.getUsername())));
    }

    // ---- employees ----

    @Operation(summary = "Employee dropdown", description = "code is the employee code; name is \"first last\".")
    @ApiResponse(responseCode = "200", description = "Employee options returned")
    @ApiResponse(responseCode = "403", description = "No qualifying permission or no access to company")
    @GetMapping("/employees")
    @PreAuthorize("@lookupAccess.can(authentication, 'EMPLOYEE')")
    public ResponseEntity<AppResponse<PageResponse<LookupResponse>>> employees(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = COMPANY_DESC) @RequestParam(required = false) Long companyId,
            @Parameter(hidden = true) @RequestHeader(value = COMPANY_HEADER, required = false) Long headerCompanyId,
            @Parameter(description = "Search text (contains, case-insensitive) over code, first and last name") @RequestParam(required = false) @Size(max = 255) String q,
            @Parameter(description = "Only employees carrying this tag (e.g. AGENT)") @RequestParam(required = false) EmployeeTag tag,
            @Parameter(description = ACTIVE_DESC) @RequestParam(defaultValue = "true") boolean activeOnly,
            Pageable pageable) {
        return ok(lookupService.employees(userDetails.getUsername(), company(companyId, headerCompanyId), q, tag, activeOnly, pageable));
    }

    @Operation(summary = "Employee option by ID")
    @ApiResponse(responseCode = "200", description = "Employee option returned")
    @ApiResponse(responseCode = "403", description = "No qualifying permission or no access to company")
    @GetMapping("/employees/{id}")
    @PreAuthorize("@lookupAccess.can(authentication, 'EMPLOYEE')")
    public ResponseEntity<AppResponse<LookupResponse>> employee(@PathVariable Long id, @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(lookupService.employee(id, userDetails.getUsername())));
    }

    // ---- users ----

    @Operation(summary = "User dropdown", description = "Users who are members of the requested company. code is the username; name is the display name.")
    @ApiResponse(responseCode = "200", description = "User options returned")
    @ApiResponse(responseCode = "403", description = "No qualifying permission or no access to company")
    @GetMapping("/users")
    @PreAuthorize("@lookupAccess.can(authentication, 'USER')")
    public ResponseEntity<AppResponse<PageResponse<LookupResponse>>> users(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = COMPANY_DESC) @RequestParam(required = false) Long companyId,
            @Parameter(hidden = true) @RequestHeader(value = COMPANY_HEADER, required = false) Long headerCompanyId,
            @Parameter(description = "Search text (contains, case-insensitive) over username and display name") @RequestParam(required = false) @Size(max = 255) String q,
            Pageable pageable) {
        return ok(lookupService.users(userDetails.getUsername(), company(companyId, headerCompanyId), q, pageable));
    }

    @Operation(summary = "User option by ID")
    @ApiResponse(responseCode = "200", description = "User option returned")
    @ApiResponse(responseCode = "403", description = "No qualifying permission or user shares no company with caller")
    @GetMapping("/users/{id}")
    @PreAuthorize("@lookupAccess.can(authentication, 'USER')")
    public ResponseEntity<AppResponse<LookupResponse>> user(@PathVariable Long id, @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(lookupService.user(id, userDetails.getUsername())));
    }

    // ---- roles ----

    @Operation(summary = "Role dropdown", description = "Roles are system-wide. code is the role name; name is the display name.")
    @ApiResponse(responseCode = "200", description = "Role options returned")
    @ApiResponse(responseCode = "403", description = "No qualifying permission")
    @GetMapping("/roles")
    @PreAuthorize("@lookupAccess.can(authentication, 'ROLE')")
    public ResponseEntity<AppResponse<PageResponse<LookupResponse>>> roles(
            @Parameter(description = "Search text (contains, case-insensitive) over name and display name") @RequestParam(required = false) @Size(max = 255) String q,
            Pageable pageable) {
        return ok(lookupService.roles(q, pageable));
    }

    @Operation(summary = "Role option by ID")
    @ApiResponse(responseCode = "200", description = "Role option returned")
    @ApiResponse(responseCode = "403", description = "No qualifying permission")
    @GetMapping("/roles/{id}")
    @PreAuthorize("@lookupAccess.can(authentication, 'ROLE')")
    public ResponseEntity<AppResponse<LookupResponse>> role(@PathVariable Long id) {
        return ResponseEntity.ok(AppResponse.of(lookupService.role(id)));
    }

    // ---- purchase orders ----

    @Operation(summary = "Purchase order dropdown",
            description = "Source POs for invoicing/receiving. openOnly (default) excludes voided and fully loaded POs; "
                    + "the consuming transaction still enforces its own rules.")
    @ApiResponse(responseCode = "200", description = "Purchase order options returned")
    @ApiResponse(responseCode = "403", description = "No qualifying permission or no access to company")
    @GetMapping("/purchase-orders")
    @PreAuthorize("@lookupAccess.can(authentication, 'PURCHASE_ORDER')")
    public ResponseEntity<AppResponse<PageResponse<TransactionLookupResponse>>> purchaseOrders(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = COMPANY_DESC) @RequestParam(required = false) Long companyId,
            @Parameter(hidden = true) @RequestHeader(value = COMPANY_HEADER, required = false) Long headerCompanyId,
            @Parameter(description = "Search text (contains, case-insensitive) over reference and sheet number") @RequestParam(required = false) @Size(max = 255) String q,
            @Parameter(description = "Filter by supplier ID") @RequestParam(required = false) Long supplierId,
            @Parameter(description = "Filter by warehouse ID") @RequestParam(required = false) Long warehouseId,
            @Parameter(description = "Exclude voided and fully loaded POs (default true)") @RequestParam(defaultValue = "true") boolean openOnly,
            Authentication authentication,
            Pageable pageable) {
        return ok(lookupService.purchaseOrders(userDetails.getUsername(), company(companyId, headerCompanyId), q, supplierId, warehouseId,
                openOnly, canViewCostPrice(authentication), pageable));
    }

    @Operation(summary = "Purchase order option by ID")
    @ApiResponse(responseCode = "200", description = "Purchase order option returned")
    @ApiResponse(responseCode = "403", description = "No qualifying permission or no access to company")
    @GetMapping("/purchase-orders/{id}")
    @PreAuthorize("@lookupAccess.can(authentication, 'PURCHASE_ORDER')")
    public ResponseEntity<AppResponse<TransactionLookupResponse>> purchaseOrder(@PathVariable Long id, @AuthenticationPrincipal UserDetails userDetails,
                                                                            Authentication authentication) {
        return ResponseEntity.ok(AppResponse.of(lookupService.purchaseOrder(id, userDetails.getUsername(), canViewCostPrice(authentication))));
    }

    // ---- delivery receipts ----

    @Operation(summary = "Delivery receipt dropdown",
            description = "Source delivery receipts for Outlet Receive. openOnly (default) returns only outlet receipts that are neither voided "
                    + "nor fully received; warehouse fields are the outlet warehouse.")
    @ApiResponse(responseCode = "200", description = "Delivery receipt options returned")
    @ApiResponse(responseCode = "403", description = "No qualifying permission or no access to company")
    @GetMapping("/delivery-receipts")
    @PreAuthorize("@lookupAccess.can(authentication, 'DELIVERY_RECEIPT')")
    public ResponseEntity<AppResponse<PageResponse<TransactionLookupResponse>>> deliveryReceipts(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = COMPANY_DESC) @RequestParam(required = false) Long companyId,
            @Parameter(hidden = true) @RequestHeader(value = COMPANY_HEADER, required = false) Long headerCompanyId,
            @Parameter(description = "Search text (contains, case-insensitive) over reference and sheet number") @RequestParam(required = false) @Size(max = 255) String q,
            @Parameter(description = "Filter by customer (outlet) ID") @RequestParam(required = false) Long customerId,
            @Parameter(description = "Only receivable receipts: outlet, not voided, not fully received (default true)") @RequestParam(defaultValue = "true") boolean openOnly,
            Pageable pageable) {
        return ok(lookupService.deliveryReceipts(userDetails.getUsername(), company(companyId, headerCompanyId), q, customerId, openOnly, pageable));
    }

    @Operation(summary = "Delivery receipt option by ID")
    @ApiResponse(responseCode = "200", description = "Delivery receipt option returned")
    @ApiResponse(responseCode = "403", description = "No qualifying permission or no access to company")
    @GetMapping("/delivery-receipts/{id}")
    @PreAuthorize("@lookupAccess.can(authentication, 'DELIVERY_RECEIPT')")
    public ResponseEntity<AppResponse<TransactionLookupResponse>> deliveryReceipt(@PathVariable Long id, @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(lookupService.deliveryReceipt(id, userDetails.getUsername())));
    }

    // ---- outlet delivery receipts ----

    @Operation(summary = "Outlet delivery receipt dropdown",
            description = "Source outlet delivery receipts for Outlet Delivery Return. openOnly (default) returns only receipts that are neither "
                    + "voided nor fully returned; warehouse fields are the outlet warehouse.")
    @ApiResponse(responseCode = "200", description = "Outlet delivery receipt options returned")
    @ApiResponse(responseCode = "403", description = "No qualifying permission or no access to company")
    @GetMapping("/outlet-delivery-receipts")
    @PreAuthorize("@lookupAccess.can(authentication, 'OUTLET_DELIVERY_RECEIPT')")
    public ResponseEntity<AppResponse<PageResponse<TransactionLookupResponse>>> outletDeliveryReceipts(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = COMPANY_DESC) @RequestParam(required = false) Long companyId,
            @Parameter(hidden = true) @RequestHeader(value = COMPANY_HEADER, required = false) Long headerCompanyId,
            @Parameter(description = "Search text (contains, case-insensitive) over reference and sheet number") @RequestParam(required = false) @Size(max = 255) String q,
            @Parameter(description = "Filter by outlet customer ID") @RequestParam(required = false) Long customerId,
            @Parameter(description = "Filter by agent (employee) ID") @RequestParam(required = false) Long agentId,
            @Parameter(description = "Only returnable receipts: not voided, not fully returned (default true)") @RequestParam(defaultValue = "true") boolean openOnly,
            Pageable pageable) {
        return ok(lookupService.outletDeliveryReceipts(userDetails.getUsername(), company(companyId, headerCompanyId), q, customerId, agentId,
                openOnly, pageable));
    }

    @Operation(summary = "Outlet delivery receipt option by ID")
    @ApiResponse(responseCode = "200", description = "Outlet delivery receipt option returned")
    @ApiResponse(responseCode = "403", description = "No qualifying permission or no access to company")
    @GetMapping("/outlet-delivery-receipts/{id}")
    @PreAuthorize("@lookupAccess.can(authentication, 'OUTLET_DELIVERY_RECEIPT')")
    public ResponseEntity<AppResponse<TransactionLookupResponse>> outletDeliveryReceipt(@PathVariable Long id, @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(lookupService.outletDeliveryReceipt(id, userDetails.getUsername())));
    }

    // ---- stock transfers ----

    @Operation(summary = "Stock transfer dropdown",
            description = "Source stock transfers for Delivery Receipt. openOnly (default) returns only transfers that are neither "
                    + "voided nor delivered; warehouse fields are the main warehouse holding the stock.")
    @ApiResponse(responseCode = "200", description = "Stock transfer options returned")
    @ApiResponse(responseCode = "403", description = "No qualifying permission or no access to company")
    @GetMapping("/stock-transfers")
    @PreAuthorize("@lookupAccess.can(authentication, 'STOCK_TRANSFER')")
    public ResponseEntity<AppResponse<PageResponse<TransactionLookupResponse>>> stockTransfers(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = COMPANY_DESC) @RequestParam(required = false) Long companyId,
            @Parameter(hidden = true) @RequestHeader(value = COMPANY_HEADER, required = false) Long headerCompanyId,
            @Parameter(description = "Search text (contains, case-insensitive) over reference and sheet number") @RequestParam(required = false) @Size(max = 255) String q,
            @Parameter(description = "Filter by customer ID") @RequestParam(required = false) Long customerId,
            @Parameter(description = "Only deliverable transfers: not voided, not delivered (default true)") @RequestParam(defaultValue = "true") boolean openOnly,
            Pageable pageable) {
        return ok(lookupService.stockTransfers(userDetails.getUsername(), company(companyId, headerCompanyId), q, customerId, openOnly, pageable));
    }

    @Operation(summary = "Stock transfer option by ID")
    @ApiResponse(responseCode = "200", description = "Stock transfer option returned")
    @ApiResponse(responseCode = "403", description = "No qualifying permission or no access to company")
    @GetMapping("/stock-transfers/{id}")
    @PreAuthorize("@lookupAccess.can(authentication, 'STOCK_TRANSFER')")
    public ResponseEntity<AppResponse<TransactionLookupResponse>> stockTransfer(@PathVariable Long id, @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(lookupService.stockTransfer(id, userDetails.getUsername())));
    }

    // ---- outlet pull outs ----

    @Operation(summary = "Outlet pull out dropdown",
            description = "Source outlet pull outs for Pull Out Receive. openOnly (default) returns only pull outs that are neither "
                    + "voided nor fully received; warehouse fields are the main warehouse they are in transit to.")
    @ApiResponse(responseCode = "200", description = "Outlet pull out options returned")
    @ApiResponse(responseCode = "403", description = "No qualifying permission or no access to company")
    @GetMapping("/outlet-pull-outs")
    @PreAuthorize("@lookupAccess.can(authentication, 'OUTLET_PULL_OUT')")
    public ResponseEntity<AppResponse<PageResponse<TransactionLookupResponse>>> outletPullOuts(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = COMPANY_DESC) @RequestParam(required = false) Long companyId,
            @Parameter(hidden = true) @RequestHeader(value = COMPANY_HEADER, required = false) Long headerCompanyId,
            @Parameter(description = "Search text (contains, case-insensitive) over reference and sheet number") @RequestParam(required = false) @Size(max = 255) String q,
            @Parameter(description = "Filter by outlet customer ID") @RequestParam(required = false) Long customerId,
            @Parameter(description = "Only receivable pull outs: not voided, not fully received (default true)") @RequestParam(defaultValue = "true") boolean openOnly,
            Pageable pageable) {
        return ok(lookupService.outletPullOuts(userDetails.getUsername(), company(companyId, headerCompanyId), q, customerId, openOnly, pageable));
    }

    @Operation(summary = "Outlet pull out option by ID")
    @ApiResponse(responseCode = "200", description = "Outlet pull out option returned")
    @ApiResponse(responseCode = "403", description = "No qualifying permission or no access to company")
    @GetMapping("/outlet-pull-outs/{id}")
    @PreAuthorize("@lookupAccess.can(authentication, 'OUTLET_PULL_OUT')")
    public ResponseEntity<AppResponse<TransactionLookupResponse>> outletPullOut(@PathVariable Long id, @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(lookupService.outletPullOut(id, userDetails.getUsername())));
    }

    // ---- pull out reasons ----

    @Operation(summary = "Pull out reason dropdown")
    @ApiResponse(responseCode = "200", description = "Pull out reason options returned")
    @ApiResponse(responseCode = "403", description = "No qualifying permission or no access to company")
    @GetMapping("/pull-out-reasons")
    @PreAuthorize("@lookupAccess.can(authentication, 'PULL_OUT_REASON')")
    public ResponseEntity<AppResponse<PageResponse<LookupResponse>>> pullOutReasons(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = COMPANY_DESC) @RequestParam(required = false) Long companyId,
            @Parameter(hidden = true) @RequestHeader(value = COMPANY_HEADER, required = false) Long headerCompanyId,
            @Parameter(description = "Search text (contains, case-insensitive) over name") @RequestParam(required = false) @Size(max = 255) String q,
            @Parameter(description = ACTIVE_DESC) @RequestParam(defaultValue = "true") boolean activeOnly,
            Pageable pageable) {
        return ok(lookupService.pullOutReasons(userDetails.getUsername(), company(companyId, headerCompanyId), q, activeOnly, pageable));
    }

    @Operation(summary = "Pull out reason option by ID")
    @ApiResponse(responseCode = "200", description = "Pull out reason option returned")
    @ApiResponse(responseCode = "403", description = "No qualifying permission or no access to company")
    @GetMapping("/pull-out-reasons/{id}")
    @PreAuthorize("@lookupAccess.can(authentication, 'PULL_OUT_REASON')")
    public ResponseEntity<AppResponse<LookupResponse>> pullOutReason(@PathVariable Long id, @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(lookupService.pullOutReason(id, userDetails.getUsername())));
    }

    // ---- bills of materials ----

    @Operation(summary = "Bill of materials dropdown", description = "Each option carries its components (quantity per unit of the output item).")
    @ApiResponse(responseCode = "200", description = "Bill of materials options returned")
    @ApiResponse(responseCode = "403", description = "No qualifying permission or no access to company")
    @GetMapping("/bills-of-materials")
    @PreAuthorize("@lookupAccess.can(authentication, 'BILL_OF_MATERIAL')")
    public ResponseEntity<AppResponse<PageResponse<BillOfMaterialLookupResponse>>> billsOfMaterials(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = COMPANY_DESC) @RequestParam(required = false) Long companyId,
            @Parameter(hidden = true) @RequestHeader(value = COMPANY_HEADER, required = false) Long headerCompanyId,
            @Parameter(description = "Search text (contains, case-insensitive) over code and output item code/name") @RequestParam(required = false) @Size(max = 255) String q,
            @Parameter(description = "Only BOMs that produce this item") @RequestParam(required = false) Long itemId,
            @Parameter(description = ACTIVE_DESC) @RequestParam(defaultValue = "true") boolean activeOnly,
            Pageable pageable) {
        return ok(lookupService.billsOfMaterials(userDetails.getUsername(), company(companyId, headerCompanyId), q, itemId, activeOnly, pageable));
    }

    @Operation(summary = "Bill of materials option by ID")
    @ApiResponse(responseCode = "200", description = "Bill of materials option returned")
    @ApiResponse(responseCode = "403", description = "No qualifying permission or no access to company")
    @GetMapping("/bills-of-materials/{id}")
    @PreAuthorize("@lookupAccess.can(authentication, 'BILL_OF_MATERIAL')")
    public ResponseEntity<AppResponse<BillOfMaterialLookupResponse>> billOfMaterial(@PathVariable Long id, @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(lookupService.billOfMaterial(id, userDetails.getUsername())));
    }

    // ---- purchase invoices ----

    @Operation(summary = "Purchase invoice dropdown",
            description = "Source invoices for receiving. openOnly (default) returns only Direct-mode invoices that are neither voided nor fully loaded.")
    @ApiResponse(responseCode = "200", description = "Purchase invoice options returned")
    @ApiResponse(responseCode = "403", description = "No qualifying permission or no access to company")
    @GetMapping("/purchase-invoices")
    @PreAuthorize("@lookupAccess.can(authentication, 'PURCHASE_INVOICE')")
    public ResponseEntity<AppResponse<PageResponse<TransactionLookupResponse>>> purchaseInvoices(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = COMPANY_DESC) @RequestParam(required = false) Long companyId,
            @Parameter(hidden = true) @RequestHeader(value = COMPANY_HEADER, required = false) Long headerCompanyId,
            @Parameter(description = "Search text (contains, case-insensitive) over reference and sheet number") @RequestParam(required = false) @Size(max = 255) String q,
            @Parameter(description = "Filter by supplier ID") @RequestParam(required = false) Long supplierId,
            @Parameter(description = "Filter by warehouse ID") @RequestParam(required = false) Long warehouseId,
            @Parameter(description = "Only receivable invoices: Direct-mode, not voided, not fully loaded (default true)") @RequestParam(defaultValue = "true") boolean openOnly,
            Authentication authentication,
            Pageable pageable) {
        return ok(lookupService.purchaseInvoices(userDetails.getUsername(), company(companyId, headerCompanyId), q, supplierId, warehouseId,
                openOnly, canViewCostPrice(authentication), pageable));
    }

    @Operation(summary = "Purchase invoice option by ID")
    @ApiResponse(responseCode = "200", description = "Purchase invoice option returned")
    @ApiResponse(responseCode = "403", description = "No qualifying permission or no access to company")
    @GetMapping("/purchase-invoices/{id}")
    @PreAuthorize("@lookupAccess.can(authentication, 'PURCHASE_INVOICE')")
    public ResponseEntity<AppResponse<TransactionLookupResponse>> purchaseInvoice(@PathVariable Long id, @AuthenticationPrincipal UserDetails userDetails,
                                                                            Authentication authentication) {
        return ResponseEntity.ok(AppResponse.of(lookupService.purchaseInvoice(id, userDetails.getUsername(), canViewCostPrice(authentication))));
    }

    // ---- stock ----

    @Operation(summary = "Current stock per item in a warehouse",
            description = "Live on-hand (quantity) and in-transit (transitQuantity) balance for each requested item in one warehouse, "
                    + "shown beside lines while creating an inventory transaction. One row per requested item (zeros if it never moved there). "
                    + "Not paginated: bounded by itemIds (max " + MAX_STOCK_ITEMS + ").")
    @ApiResponse(responseCode = "200", description = "Stock rows returned")
    @ApiResponse(responseCode = "400", description = "Missing/oversized itemIds, missing company, or unknown warehouse")
    @ApiResponse(responseCode = "403", description = "No qualifying permission, no access to company, or warehouse outside the company")
    @GetMapping("/stock")
    @PreAuthorize("@lookupAccess.can(authentication, 'STOCK')")
    public ResponseEntity<AppResponse<List<StockLookupResponse>>> stock(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = COMPANY_DESC) @RequestParam(required = false) Long companyId,
            @Parameter(hidden = true) @RequestHeader(value = COMPANY_HEADER, required = false) Long headerCompanyId,
            @Parameter(description = "Warehouse whose balances to read") @RequestParam @NotNull @Positive Long warehouseId,
            @Parameter(description = "Item IDs (comma-separated or repeated)") @RequestParam @NotEmpty @Size(max = MAX_STOCK_ITEMS) List<@NotNull @Positive Long> itemIds) {
        return ResponseEntity.ok(AppResponse.of(lookupService.stock(userDetails.getUsername(), company(companyId, headerCompanyId), warehouseId, itemIds)));
    }

    private static Long company(Long companyId, Long headerCompanyId) {
        return companyId != null ? companyId : headerCompanyId;
    }

    private static boolean canViewCostPrice(Authentication authentication) {
        return authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("VIEW_COST_PRICE"));
    }

    private static <T> ResponseEntity<AppResponse<PageResponse<T>>> ok(Page<T> page) {
        return ResponseEntity.ok(AppResponse.of(PageResponse.of(page)));
    }
}
