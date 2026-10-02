package com.chardizard.Norbiz.controllers;

import com.chardizard.Norbiz.dto.*;
import com.chardizard.Norbiz.models.TransactionEvent;
import com.chardizard.Norbiz.models.TransactionType;
import com.chardizard.Norbiz.services.TransactionEventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// Authorization is enforced in TransactionEventService: VIEW_<TYPE> + company access for every
// endpoint, plus the action definition's allowed roles for POST /actions.
@Tag(name = "Transaction History & Actions",
     description = "Per-transaction history (CREATED / VOIDED / actions) and company-configured actions. Requires the transaction type's VIEW_ permission; taking an action also requires one of the action's allowed roles.")
@RestController
@RequestMapping("/transactions/{transactionType}/{id}")
@RequiredArgsConstructor
public class TransactionEventController {

    private final TransactionEventService transactionEventService;

    @Operation(summary = "Get transaction history", description = "Oldest first.")
    @ApiResponse(responseCode = "200", description = "History returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_ permission or no access to company")
    @ApiResponse(responseCode = "400", description = "Transaction not found")
    @GetMapping("/history")
    public ResponseEntity<AppResponse<PageResponse<TransactionEventResponse>>> getHistory(
            @Parameter(description = "Transaction type") @PathVariable TransactionType transactionType,
            @Parameter(description = "Transaction ID") @PathVariable Long id,
            @AuthenticationPrincipal UserDetails userDetails,
            Pageable pageable) {
        var history = transactionEventService.findHistory(transactionType, id, userDetails.getUsername(), pageable)
                .map(this::toResponse);
        return ResponseEntity.ok(AppResponse.of(PageResponse.of(history)));
    }

    @Operation(summary = "List actions available on the transaction",
               description = "Every active configured action with flags for the caller: takenByMe, allowedForMe, prerequisitesMet, canTake.")
    @ApiResponse(responseCode = "200", description = "Actions returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_ permission or no access to company")
    @GetMapping("/actions")
    public ResponseEntity<AppResponse<List<TransactionAvailableActionResponse>>> getAvailableActions(
            @Parameter(description = "Transaction type") @PathVariable TransactionType transactionType,
            @Parameter(description = "Transaction ID") @PathVariable Long id,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(
                transactionEventService.findAvailableActions(transactionType, id, userDetails.getUsername())));
    }

    @Operation(summary = "Take an action on the transaction",
               description = "Immutable once taken. Rejected if the transaction is voided, the caller already took this action, or a prerequisite action has not been taken yet by anyone.")
    @ApiResponse(responseCode = "201", description = "Action recorded")
    @ApiResponse(responseCode = "400", description = "Voided, already taken, inactive, or prerequisites not met")
    @ApiResponse(responseCode = "403", description = "Caller's roles not allowed for this action, or no access to company")
    @PostMapping("/actions")
    public ResponseEntity<AppResponse<TransactionEventResponse>> takeAction(
            @Parameter(description = "Transaction type") @PathVariable TransactionType transactionType,
            @Parameter(description = "Transaction ID") @PathVariable Long id,
            @Valid @RequestBody TransactionActionRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.status(HttpStatus.CREATED).body(AppResponse.of(
                toResponse(transactionEventService.takeAction(transactionType, id, request, userDetails.getUsername()))));
    }

    private TransactionEventResponse toResponse(TransactionEvent event) {
        TransactionEventResponse res = new TransactionEventResponse();
        res.setId(event.getId());
        res.setTransactionType(event.getTransactionType());
        res.setTransactionId(event.getTransactionId());
        res.setReferenceNumber(event.getReferenceNumber());
        res.setEventType(event.getEventType());
        res.setActionDefinitionId(event.getActionDefinition() != null ? event.getActionDefinition().getId() : null);
        res.setActionCode(event.getActionCode());
        res.setActionName(event.getActionName());
        res.setPerformedBy(event.getPerformedBy());
        res.setPerformedAt(event.getPerformedAt());
        res.setRemarks(event.getRemarks());
        return res;
    }
}
