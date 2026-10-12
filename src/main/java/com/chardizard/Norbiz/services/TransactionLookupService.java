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
        };
    }

    private IllegalArgumentException notFound(TransactionType type, Long id) {
        return new IllegalArgumentException("Transaction not found: " + type + " " + id);
    }
}
