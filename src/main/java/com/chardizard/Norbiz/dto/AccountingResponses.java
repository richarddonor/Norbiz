package com.chardizard.Norbiz.dto;

import com.chardizard.Norbiz.models.BankEntry;
import com.chardizard.Norbiz.models.CustomerPayment;
import com.chardizard.Norbiz.models.CustomerPaymentTender;
import com.chardizard.Norbiz.models.OpenItem;
import com.chardizard.Norbiz.models.OpenItemAllocation;
import com.chardizard.Norbiz.models.OpenItemStatus;
import com.chardizard.Norbiz.models.SupplierPayment;
import com.chardizard.Norbiz.models.SupplierPaymentTender;

import java.math.BigDecimal;

// Entity → response mappers shared by the AR/AP controllers.
public final class AccountingResponses {

    private AccountingResponses() {
    }

    public static AllocationResponse allocation(OpenItemAllocation a) {
        AllocationResponse res = new AllocationResponse();
        res.setId(a.getId());
        res.setCreditItemId(a.getCreditItem().getId());
        res.setCreditSourceType(a.getCreditItem().getSourceType());
        res.setCreditReferenceNumber(a.getCreditItem().getReferenceNumber());
        res.setChargeItemId(a.getChargeItem().getId());
        res.setChargeSourceType(a.getChargeItem().getSourceType());
        res.setChargeReferenceNumber(a.getChargeItem().getReferenceNumber());
        res.setAmount(a.getAmount());
        res.setAllocationDate(a.getAllocationDate());
        res.setSourceType(a.getSourceType());
        res.setSourceId(a.getSourceId());
        res.setPending(a.isPending());
        res.setReversed(a.isReversed());
        res.setReversedDate(a.getReversedDate());
        res.setReversalReason(a.getReversalReason());
        return res;
    }

    public static OpenItemResponse openItem(OpenItem o) {
        return new OpenItemResponse(o.getId(), o.getCompany().getId(), o.getSide(),
                o.getCustomer() != null ? o.getCustomer().getId() : null, o.getCustomer() != null ? o.getCustomer().getName() : null,
                o.getSupplier() != null ? o.getSupplier().getId() : null, o.getSupplier() != null ? o.getSupplier().getName() : null,
                o.getDirection(), o.getSourceType(), o.getSourceId(), o.getReferenceNumber(), o.getDocumentDate(), o.getDueDate(),
                o.getAmount(), o.getAppliedAmount(), o.getPendingAmount(), o.getBouncedAmount(), o.getBalance(), o.getStatus(), o.isCleared());
    }

    public static ReceivedTenderResponse receivedTender(CustomerPaymentTender t) {
        CustomerPayment p = t.getCustomerPayment();
        ReceivedTenderResponse res = new ReceivedTenderResponse();
        res.setId(t.getId());
        res.setPaymentId(p.getId());
        res.setPaymentReferenceNumber(p.getReferenceNumber());
        res.setPaymentDate(p.getPaymentDate());
        res.setCompanyId(p.getCompany().getId());
        res.setCustomerId(p.getCustomer().getId());
        res.setCustomerName(p.getCustomer().getName());
        res.setLineNumber(t.getLineNumber());
        res.setMode(t.getMode());
        res.setAmount(t.getAmount());
        res.setCheckNumber(t.getCheckNumber());
        res.setCheckDate(t.getCheckDate());
        if (t.getBank() != null) {
            res.setBankId(t.getBank().getId());
            res.setBankName(t.getBank().getName());
        }
        if (t.getDepositBankAccount() != null) {
            res.setDepositBankAccountId(t.getDepositBankAccount().getId());
            res.setDepositBankAccountName(t.getDepositBankAccount().getName());
        }
        res.setOfficialReceiptNumber(t.getOfficialReceiptNumber());
        res.setProvisionalReceiptNumber(t.getProvisionalReceiptNumber());
        res.setStatus(t.getStatus());
        res.setStatusDate(t.getStatusDate());
        res.setBounceReason(t.getBounceReason());
        if (t.getDeposit() != null) {
            res.setDepositId(t.getDeposit().getId());
            res.setDepositReferenceNumber(t.getDeposit().getReferenceNumber());
        }
        if (t.getReplacesTender() != null) res.setReplacesTenderId(t.getReplacesTender().getId());
        res.setReplacedAmount(t.getReplacedAmount());
        if (t.getOpenItem() != null) {
            res.setOpenItemId(t.getOpenItem().getId());
            res.setAppliedAmount(t.getOpenItem().getAppliedAmount());
            res.setUnappliedAmount(t.getOpenItem().getStatus() == OpenItemStatus.VOIDED
                    ? BigDecimal.ZERO : t.getOpenItem().getBalance());
        }
        res.setPaymentVoided(p.isVoided());
        return res;
    }

    public static IssuedTenderResponse issuedTender(SupplierPaymentTender t) {
        SupplierPayment p = t.getSupplierPayment();
        IssuedTenderResponse res = new IssuedTenderResponse();
        res.setId(t.getId());
        res.setPaymentId(p.getId());
        res.setPaymentReferenceNumber(p.getReferenceNumber());
        res.setVoucherNumber(p.getVoucherNumber());
        res.setPaymentDate(p.getPaymentDate());
        res.setCompanyId(p.getCompany().getId());
        res.setSupplierId(p.getSupplier().getId());
        res.setSupplierName(p.getSupplier().getName());
        res.setLineNumber(t.getLineNumber());
        res.setMode(t.getMode());
        res.setAmount(t.getAmount());
        if (t.getBankAccount() != null) {
            res.setBankAccountId(t.getBankAccount().getId());
            res.setBankAccountName(t.getBankAccount().getName());
        }
        res.setCheckNumber(t.getCheckNumber());
        res.setCheckDate(t.getCheckDate());
        res.setStatus(t.getStatus());
        res.setStatusDate(t.getStatusDate());
        res.setCancelReason(t.getCancelReason());
        if (t.getReplacesTender() != null) res.setReplacesTenderId(t.getReplacesTender().getId());
        if (t.getOpenItem() != null) {
            res.setOpenItemId(t.getOpenItem().getId());
            res.setAppliedAmount(t.getOpenItem().getAppliedAmount());
            res.setUnappliedAmount(t.getOpenItem().getStatus() == OpenItemStatus.VOIDED
                    ? BigDecimal.ZERO : t.getOpenItem().getBalance());
        }
        res.setPaymentVoided(p.isVoided());
        return res;
    }

    public static BankEntryResponse bankEntry(BankEntry e) {
        BankEntryResponse res = new BankEntryResponse();
        res.setId(e.getId());
        res.setBankAccountId(e.getBankAccount().getId());
        res.setEntryDate(e.getEntryDate());
        res.setEntryType(e.getEntryType());
        res.setAmount(e.getAmount());
        res.setSourceType(e.getSourceType());
        res.setSourceId(e.getSourceId());
        res.setSourceLineId(e.getSourceLineId());
        res.setReferenceNumber(e.getReferenceNumber());
        res.setCheckNumber(e.getCheckNumber());
        res.setCheckDate(e.getCheckDate());
        res.setDescription(e.getDescription());
        res.setCleared(e.isCleared());
        res.setClearedDate(e.getClearedDate());
        if (e.getBankReconciliation() != null) {
            res.setBankReconciliationId(e.getBankReconciliation().getId());
            res.setBankReconciliationReferenceNumber(e.getBankReconciliation().getReferenceNumber());
        }
        res.setVoided(e.isVoided());
        return res;
    }
}
