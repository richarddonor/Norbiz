package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.models.TransactionType;
import com.chardizard.Norbiz.repositories.DeliveryReceiptRepository;
import com.chardizard.Norbiz.repositories.InventoryAdjustmentRepository;
import com.chardizard.Norbiz.repositories.OutletReceiveRepository;
import com.chardizard.Norbiz.repositories.PurchaseInvoiceRepository;
import com.chardizard.Norbiz.repositories.PurchaseOrderRepository;
import com.chardizard.Norbiz.repositories.PurchaseReceiveRepository;
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
    private final PurchaseReceiveRepository purchaseReceiveRepository;
    private final DeliveryReceiptRepository deliveryReceiptRepository;
    private final OutletReceiveRepository outletReceiveRepository;

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
            case PURCHASE_RECEIVE -> purchaseReceiveRepository.findById(id)
                    .map(t -> new TransactionHeader(t.getCompany().getId(), t.getReferenceNumber(), t.isVoided()))
                    .orElseThrow(() -> notFound(type, id));
            case DELIVERY_RECEIPT -> deliveryReceiptRepository.findById(id)
                    .map(t -> new TransactionHeader(t.getCompany().getId(), t.getReferenceNumber(), t.isVoided()))
                    .orElseThrow(() -> notFound(type, id));
            case OUTLET_RECEIVE -> outletReceiveRepository.findById(id)
                    .map(t -> new TransactionHeader(t.getCompany().getId(), t.getReferenceNumber(), t.isVoided()))
                    .orElseThrow(() -> notFound(type, id));
        };
    }

    private IllegalArgumentException notFound(TransactionType type, Long id) {
        return new IllegalArgumentException("Transaction not found: " + type + " " + id);
    }
}
