package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.models.TransactionType;
import com.chardizard.Norbiz.repositories.AssemblyRepository;
import com.chardizard.Norbiz.repositories.DeliveryReceiptRepository;
import com.chardizard.Norbiz.repositories.InventoryAdjustmentRepository;
import com.chardizard.Norbiz.repositories.OutletDeliveryReceiptRepository;
import com.chardizard.Norbiz.repositories.OutletDeliveryReturnRepository;
import com.chardizard.Norbiz.repositories.OutletPullOutRepository;
import com.chardizard.Norbiz.repositories.OutletReceiptRepository;
import com.chardizard.Norbiz.repositories.PullOutReceiptRepository;
import com.chardizard.Norbiz.repositories.PurchaseInvoiceRepository;
import com.chardizard.Norbiz.repositories.PurchaseOrderRepository;
import com.chardizard.Norbiz.repositories.PurchaseReceiptRepository;
import com.chardizard.Norbiz.repositories.StockTransferRepository;
import com.chardizard.Norbiz.repositories.SalesInvoiceRepository;
import com.chardizard.Norbiz.repositories.SalesInvoiceReturnRepository;
import com.chardizard.Norbiz.repositories.CustomerMemoRepository;
import com.chardizard.Norbiz.repositories.SupplierMemoRepository;
import com.chardizard.Norbiz.repositories.CustomerPaymentRepository;
import com.chardizard.Norbiz.repositories.SupplierPaymentRepository;
import com.chardizard.Norbiz.repositories.CustomerRefundRepository;
import com.chardizard.Norbiz.repositories.SupplierRefundRepository;
import com.chardizard.Norbiz.repositories.DepositRepository;
import com.chardizard.Norbiz.repositories.BankReconciliationRepository;
import com.chardizard.Norbiz.repositories.StatementOfAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

// Resolves the header fields the generic action/history code needs, without the four
// transaction entities sharing a base class. New transaction types must add a branch here.
@Service
@RequiredArgsConstructor
public class TransactionLookupService {

    public record TransactionHeader(Long companyId, String referenceNumber, boolean voided) {}

    private final InventoryAdjustmentRepository inventoryAdjustmentRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseInvoiceRepository purchaseInvoiceRepository;
    private final PurchaseReceiptRepository purchaseReceiptRepository;
    private final DeliveryReceiptRepository deliveryReceiptRepository;
    private final OutletReceiptRepository outletReceiptRepository;
    private final OutletDeliveryReceiptRepository outletDeliveryReceiptRepository;
    private final OutletDeliveryReturnRepository outletDeliveryReturnRepository;
    private final StockTransferRepository stockTransferRepository;
    private final OutletPullOutRepository outletPullOutRepository;
    private final PullOutReceiptRepository pullOutReceiptRepository;
    private final AssemblyRepository assemblyRepository;
    private final SalesInvoiceRepository salesInvoiceRepository;
    private final SalesInvoiceReturnRepository salesInvoiceReturnRepository;
    private final CustomerMemoRepository customerMemoRepository;
    private final SupplierMemoRepository supplierMemoRepository;
    private final CustomerPaymentRepository customerPaymentRepository;
    private final SupplierPaymentRepository supplierPaymentRepository;
    private final CustomerRefundRepository customerRefundRepository;
    private final SupplierRefundRepository supplierRefundRepository;
    private final DepositRepository depositRepository;
    private final BankReconciliationRepository bankReconciliationRepository;
    private final StatementOfAccountRepository statementOfAccountRepository;

    public TransactionHeader resolve(TransactionType type, Long id) {
        return switch (type) {
            case INVENTORY_ADJUSTMENT -> inventoryAdjustmentRepository.findById(id)
                    .map(t -> new TransactionHeader(t.getCompany().getId(), t.getReferenceNumber(), t.isVoided()))
                    .orElseThrow(() -> notFound(type, id));
            case PURCHASE_ORDER -> purchaseOrderRepository.findById(id)
                    .map(t -> new TransactionHeader(t.getCompany().getId(), t.getReferenceNumber(), t.isVoided()))
                    .orElseThrow(() -> notFound(type, id));
            case PURCHASE_INVOICE -> purchaseInvoiceRepository.findById(id)
                    .map(t -> new TransactionHeader(t.getCompany().getId(), t.getReferenceNumber(), t.isVoided()))
                    .orElseThrow(() -> notFound(type, id));
            case PURCHASE_RECEIPT -> purchaseReceiptRepository.findById(id)
                    .map(t -> new TransactionHeader(t.getCompany().getId(), t.getReferenceNumber(), t.isVoided()))
                    .orElseThrow(() -> notFound(type, id));
            case DELIVERY_RECEIPT -> deliveryReceiptRepository.findById(id)
                    .map(t -> new TransactionHeader(t.getCompany().getId(), t.getReferenceNumber(), t.isVoided()))
                    .orElseThrow(() -> notFound(type, id));
            case OUTLET_RECEIPT -> outletReceiptRepository.findById(id)
                    .map(t -> new TransactionHeader(t.getCompany().getId(), t.getReferenceNumber(), t.isVoided()))
                    .orElseThrow(() -> notFound(type, id));
            case OUTLET_DELIVERY_RECEIPT -> outletDeliveryReceiptRepository.findById(id)
                    .map(t -> new TransactionHeader(t.getCompany().getId(), t.getReferenceNumber(), t.isVoided()))
                    .orElseThrow(() -> notFound(type, id));
            case OUTLET_DELIVERY_RETURN -> outletDeliveryReturnRepository.findById(id)
                    .map(t -> new TransactionHeader(t.getCompany().getId(), t.getReferenceNumber(), t.isVoided()))
                    .orElseThrow(() -> notFound(type, id));
            case STOCK_TRANSFER -> stockTransferRepository.findById(id)
                    .map(t -> new TransactionHeader(t.getCompany().getId(), t.getReferenceNumber(), t.isVoided()))
                    .orElseThrow(() -> notFound(type, id));
            case OUTLET_PULL_OUT -> outletPullOutRepository.findById(id)
                    .map(t -> new TransactionHeader(t.getCompany().getId(), t.getReferenceNumber(), t.isVoided()))
                    .orElseThrow(() -> notFound(type, id));
            case PULL_OUT_RECEIPT -> pullOutReceiptRepository.findById(id)
                    .map(t -> new TransactionHeader(t.getCompany().getId(), t.getReferenceNumber(), t.isVoided()))
                    .orElseThrow(() -> notFound(type, id));
            case ASSEMBLY -> assemblyRepository.findById(id)
                    .map(t -> new TransactionHeader(t.getCompany().getId(), t.getReferenceNumber(), t.isVoided()))
                    .orElseThrow(() -> notFound(type, id));
            case SALES_INVOICE -> salesInvoiceRepository.findById(id)
                    .map(t -> new TransactionHeader(t.getCompany().getId(), t.getReferenceNumber(), t.isVoided()))
                    .orElseThrow(() -> notFound(type, id));
            case SALES_INVOICE_RETURN -> salesInvoiceReturnRepository.findById(id)
                    .map(t -> new TransactionHeader(t.getCompany().getId(), t.getReferenceNumber(), t.isVoided()))
                    .orElseThrow(() -> notFound(type, id));
            case CUSTOMER_MEMO -> customerMemoRepository.findById(id)
                    .map(t -> new TransactionHeader(t.getCompany().getId(), t.getReferenceNumber(), t.isVoided()))
                    .orElseThrow(() -> notFound(type, id));
            case SUPPLIER_MEMO -> supplierMemoRepository.findById(id)
                    .map(t -> new TransactionHeader(t.getCompany().getId(), t.getReferenceNumber(), t.isVoided()))
                    .orElseThrow(() -> notFound(type, id));
            case CUSTOMER_PAYMENT -> customerPaymentRepository.findById(id)
                    .map(t -> new TransactionHeader(t.getCompany().getId(), t.getReferenceNumber(), t.isVoided()))
                    .orElseThrow(() -> notFound(type, id));
            case SUPPLIER_PAYMENT -> supplierPaymentRepository.findById(id)
                    .map(t -> new TransactionHeader(t.getCompany().getId(), t.getReferenceNumber(), t.isVoided()))
                    .orElseThrow(() -> notFound(type, id));
            case CUSTOMER_REFUND -> customerRefundRepository.findById(id)
                    .map(t -> new TransactionHeader(t.getCompany().getId(), t.getReferenceNumber(), t.isVoided()))
                    .orElseThrow(() -> notFound(type, id));
            case SUPPLIER_REFUND -> supplierRefundRepository.findById(id)
                    .map(t -> new TransactionHeader(t.getCompany().getId(), t.getReferenceNumber(), t.isVoided()))
                    .orElseThrow(() -> notFound(type, id));
            case DEPOSIT -> depositRepository.findById(id)
                    .map(t -> new TransactionHeader(t.getCompany().getId(), t.getReferenceNumber(), t.isVoided()))
                    .orElseThrow(() -> notFound(type, id));
            case BANK_RECONCILIATION -> bankReconciliationRepository.findById(id)
                    .map(t -> new TransactionHeader(t.getCompany().getId(), t.getReferenceNumber(), t.isVoided()))
                    .orElseThrow(() -> notFound(type, id));
            case STATEMENT_OF_ACCOUNT -> statementOfAccountRepository.findById(id)
                    .map(t -> new TransactionHeader(t.getCompany().getId(), t.getReferenceNumber(), t.isVoided()))
                    .orElseThrow(() -> notFound(type, id));
        };
    }

    private IllegalArgumentException notFound(TransactionType type, Long id) {
        return new IllegalArgumentException("Transaction not found: " + type + " " + id);
    }
}
