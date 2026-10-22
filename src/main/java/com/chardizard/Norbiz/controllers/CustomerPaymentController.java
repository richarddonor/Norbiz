package com.chardizard.Norbiz.controllers;

import com.chardizard.Norbiz.dto.*;
import com.chardizard.Norbiz.models.*;
import com.chardizard.Norbiz.services.CustomerPaymentService;
import com.chardizard.Norbiz.services.OpenItemQueryService;
import com.chardizard.Norbiz.util.DateRangeUtils;
import com.chardizard.Norbiz.util.ListSort;
import com.chardizard.Norbiz.util.ListTotals;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "Customer Payments", description = "Money received from customers — requires VIEW_CUSTOMER_PAYMENT / CREATE_CUSTOMER_PAYMENT / VOID_CUSTOMER_PAYMENT. "
        + "One or more tenders (cash, checks, bank transfers, cards), optionally applied to the customer's open charges; whatever is left "
        + "unapplied is an advance. Checks are tracked through Deposit and clear/bounce (see Customer Payment Tenders). Immutable once posted.")
@RestController
@RequestMapping("/customer-payments")
@RequiredArgsConstructor
public class CustomerPaymentController {

    // Column key → entity path for ?sort= (ListSort); anything else is a 400.
    private static final Map<String, String> SORTABLE = ListSort.fields(
            "company", "company.name",
            "referenceNumber", "referenceNumber",
            "sheetNumber", "sheetNumber",
            "voided", "voided",
            "customer", "customer.name",
            "date", "paymentDate",
            "totalAmount", "totalAmount");

    private final CustomerPaymentService paymentService;
    private final OpenItemQueryService openItemQueryService;

    @Operation(summary = "List customer payments", description = "Returns payments belonging to the caller's accessible companies. SUPER_ADMIN sees all.")
    @ApiResponse(responseCode = "200", description = "Payment list returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_CUSTOMER_PAYMENT permission")
    @GetMapping
    @PreAuthorize("hasAuthority('VIEW_CUSTOMER_PAYMENT')")
    public ResponseEntity<AppResponse<PageResponse<CustomerPaymentResponse>>> getAll(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = "Filter by customer ID") @RequestParam(required = false) Long customerId,
            @Parameter(description = "Filter by reference number (contains)") @RequestParam(required = false) String referenceNumber,
            @Parameter(description = "Filter by sheet number (contains)") @RequestParam(required = false) String sheetNumber,
            @Parameter(description = "Filter by customer name (contains)") @RequestParam(required = false) String customer,
            @Parameter(description = "Filter by any tender's check number (contains)") @RequestParam(required = false) String checkNumber,
            @Parameter(description = "Filter by any tender's OR/PR number (contains)") @RequestParam(required = false) String receiptNumber,
            @Parameter(description = "Filter by remarks (contains)") @RequestParam(required = false) String remarks,
            @Parameter(description = "Filter by voided status (true or false)") @RequestParam(required = false) String voided,
            @Parameter(description = "Filter by payment date, range start (yyyy-MM-dd, inclusive)") @RequestParam(required = false) String dateFrom,
            @Parameter(description = "Filter by payment date, range end (yyyy-MM-dd, inclusive)") @RequestParam(required = false) String dateTo,
            @Parameter(description = "Filter by origin") @RequestParam(required = false) TransactionOrigin origin,
            @Parameter(description = ListTotals.PARAM) @RequestParam(defaultValue = "false") boolean totals,
            Pageable pageable) {
        Pageable sorted = ListSort.apply(pageable, SORTABLE, ListSort.CREATED_FIRST);
        Map<String, String> filters = new LinkedHashMap<>();
        if (StringUtils.hasText(referenceNumber)) filters.put("referenceNumber", referenceNumber);
        if (StringUtils.hasText(sheetNumber)) filters.put("sheetNumber", sheetNumber);
        if (StringUtils.hasText(customer)) filters.put("customer", customer);
        if (StringUtils.hasText(checkNumber)) filters.put("checkNumber", checkNumber);
        if (StringUtils.hasText(receiptNumber)) filters.put("receiptNumber", receiptNumber);
        if (StringUtils.hasText(remarks)) filters.put("remarks", remarks);
        if (StringUtils.hasText(voided)) filters.put("voided", voided);
        if (origin != null) filters.put("origin", origin.name());
        Instant fromInstant = DateRangeUtils.startOfDayUtc(dateFrom);
        Instant toInstant = DateRangeUtils.endOfDayUtc(dateTo);

        var page = paymentService.findAllForUser(userDetails.getUsername(), customerId, filters, fromInstant, toInstant, sorted)
                .map(p -> toResponse(p, false));
        var response = PageResponse.of(page);
        if (totals) {
            response.withTotals(ListTotals.within(page.getTotalElements(), () -> paymentService.findAllForUser(userDetails.getUsername(),
                            customerId, filters, fromInstant, toInstant, ListSort.unpaged(sorted)).map(p -> toResponse(p, false)).getContent(),
                    ListTotals.column("totalAmount", CustomerPaymentResponse::getTotalAmount),
                    ListTotals.column("unappliedAmount", CustomerPaymentResponse::getUnappliedAmount)));
        }
        return ResponseEntity.ok(AppResponse.of(response));
    }

    @Operation(summary = "Get customer payment by ID", description = "Includes its tenders and the applications it made.")
    @ApiResponse(responseCode = "200", description = "Payment returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_CUSTOMER_PAYMENT permission or no access to company")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('VIEW_CUSTOMER_PAYMENT')")
    public ResponseEntity<AppResponse<CustomerPaymentResponse>> getById(@Parameter(description = "Payment ID") @PathVariable Long id,
                                                                        @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(toResponse(paymentService.findById(id, userDetails.getUsername()), true)));
    }

    @Operation(summary = "Post customer payment", description = "Tenders: CASH and CHECK (checkNumber, checkDate, bankId required) stay ON_HAND until "
            + "deposited; BANK_TRANSFER and CREDIT_CARD (depositBankAccountId required) land in that account at once; OTHERS settles with no bank "
            + "effect. replacesTenderId covers a BOUNCED check. Applications pay the customer's open charges (GET /lookups/open-items), drawing "
            + "from settled tenders first and then checks by check date unless tenderIndex says otherwise; the rest stays as an advance. "
            + "Dated on/after the accounting start date. Numbered CPY-.")
    @ApiResponse(responseCode = "201", description = "Payment posted")
    @ApiResponse(responseCode = "400", description = "Validation error (e.g. missing check details, over-application, inactive customer)")
    @ApiResponse(responseCode = "403", description = "Missing CREATE_CUSTOMER_PAYMENT permission or no access to company")
    @PostMapping
    @PreAuthorize("hasAuthority('CREATE_CUSTOMER_PAYMENT')")
    public ResponseEntity<AppResponse<CustomerPaymentResponse>> create(@Valid @RequestBody CustomerPaymentRequest request,
                                                                       @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AppResponse.of(toResponse(paymentService.create(request, userDetails.getUsername()), true)));
    }

    @Operation(summary = "Void customer payment", description = "Cancels a payment posted in error and reverses its applications. Fails if a cash/check "
            + "tender has been deposited (void the deposit first), cleared or bounced, or if a later Customer Credit Application/Refund used its credit.")
    @ApiResponse(responseCode = "200", description = "Payment voided")
    @ApiResponse(responseCode = "400", description = "Already voided, deposited, or used by later documents")
    @ApiResponse(responseCode = "403", description = "Missing VOID_CUSTOMER_PAYMENT permission or no access to company")
    @PostMapping("/{id}/void")
    @PreAuthorize("hasAuthority('VOID_CUSTOMER_PAYMENT')")
    public ResponseEntity<AppResponse<CustomerPaymentResponse>> voidPayment(@Parameter(description = "Payment ID") @PathVariable Long id,
                                                                            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(toResponse(paymentService.voidPayment(id, userDetails.getUsername()), true)));
    }

    private CustomerPaymentResponse toResponse(CustomerPayment payment, boolean withApplications) {
        CustomerPaymentResponse res = new CustomerPaymentResponse();
        res.setId(payment.getId());
        res.setCompanyId(payment.getCompany().getId());
        res.setCompanyName(payment.getCompany().getName());
        res.setCustomerId(payment.getCustomer().getId());
        res.setCustomerName(payment.getCustomer().getName());
        res.setReferenceNumber(payment.getReferenceNumber());
        res.setSheetNumber(payment.getSheetNumber());
        res.setPaymentDate(payment.getPaymentDate());
        res.setRemarks(payment.getRemarks());
        res.setTotalAmount(payment.getTotalAmount());
        res.setCreatedAt(payment.getCreatedAt());
        res.setCreatedBy(payment.getCreatedBy());
        res.setVoided(payment.isVoided());
        res.setVoidedAt(payment.getVoidedAt());
        res.setVoidedBy(payment.getVoidedBy());
        res.setOrigin(payment.getOrigin());
        List<ReceivedTenderResponse> tenders = payment.getTenders().stream().map(AccountingResponses::receivedTender).toList();
        res.setTenders(tenders);
        res.setAppliedAmount(tenders.stream().map(ReceivedTenderResponse::getAppliedAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
        res.setUnappliedAmount(tenders.stream().map(ReceivedTenderResponse::getUnappliedAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
        if (withApplications) {
            res.setApplications(openItemQueryService.allocationsBySource(TransactionType.CUSTOMER_PAYMENT, payment.getId()).stream()
                    .map(AccountingResponses::allocation).toList());
        }
        return res;
    }
}
