package com.chardizard.Norbiz.controllers;

import com.chardizard.Norbiz.dto.AppResponse;
import com.chardizard.Norbiz.dto.ItemLookupResponse;
import com.chardizard.Norbiz.dto.LookupResponse;
import com.chardizard.Norbiz.dto.PageResponse;
import com.chardizard.Norbiz.dto.TransactionLookupResponse;
import com.chardizard.Norbiz.models.ItemTag;
import com.chardizard.Norbiz.services.LookupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
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

    @Operation(summary = "Customer dropdown")
    @ApiResponse(responseCode = "200", description = "Customer options returned")
    @ApiResponse(responseCode = "403", description = "No qualifying permission or no access to company")
    @GetMapping("/customers")
    @PreAuthorize("@lookupAccess.can(authentication, 'CUSTOMER')")
    public ResponseEntity<AppResponse<PageResponse<LookupResponse>>> customers(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = COMPANY_DESC) @RequestParam(required = false) Long companyId,
            @Parameter(hidden = true) @RequestHeader(value = COMPANY_HEADER, required = false) Long headerCompanyId,
            @Parameter(description = Q_DESC) @RequestParam(required = false) @Size(max = 255) String q,
            @Parameter(description = ACTIVE_DESC) @RequestParam(defaultValue = "true") boolean activeOnly,
            Pageable pageable) {
        return ok(lookupService.customers(userDetails.getUsername(), company(companyId, headerCompanyId), q, activeOnly, pageable));
    }

    @Operation(summary = "Customer option by ID")
    @ApiResponse(responseCode = "200", description = "Customer option returned")
    @ApiResponse(responseCode = "403", description = "No qualifying permission or no access to company")
    @GetMapping("/customers/{id}")
    @PreAuthorize("@lookupAccess.can(authentication, 'CUSTOMER')")
    public ResponseEntity<AppResponse<LookupResponse>> customer(@PathVariable Long id, @AuthenticationPrincipal UserDetails userDetails) {
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
            @Parameter(description = ACTIVE_DESC) @RequestParam(defaultValue = "true") boolean activeOnly,
            Pageable pageable) {
        return ok(lookupService.warehouses(userDetails.getUsername(), company(companyId, headerCompanyId), q, activeOnly, pageable));
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
            @Parameter(description = ACTIVE_DESC) @RequestParam(defaultValue = "true") boolean activeOnly,
            Pageable pageable) {
        return ok(lookupService.employees(userDetails.getUsername(), company(companyId, headerCompanyId), q, activeOnly, pageable));
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
