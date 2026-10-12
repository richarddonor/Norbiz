package com.chardizard.Norbiz.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

// Not Auditable: purchase receipt lines are immutable once posted (see PurchaseReceipt),
// so there is nothing to diff/track — the ledger (InventoryMovement) is the audit trail.
@Getter
@Setter
@Entity
@Table(name = "purchase_receipt_lines")
public class PurchaseReceiptLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "purchase_receipt_id", nullable = false,
        foreignKey = @ForeignKey(name = "PURCHASE_RECEIPT_LINES_PURCHASE_RECEIPT_ID_FK"))
    private PurchaseReceipt purchaseReceipt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_id", nullable = false,
        foreignKey = @ForeignKey(name = "PURCHASE_RECEIPT_LINES_ITEM_ID_FK"))
    private Item item;

    // Set when the receipt's source is a Purchase Order.
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "purchase_order_line_id",
        foreignKey = @ForeignKey(name = "PURCHASE_RECEIPT_LINES_PURCHASE_ORDER_LINE_ID_FK"))
    private PurchaseOrderLine purchaseOrderLine;

    // Set when the receipt's source is a Direct-mode Purchase Invoice.
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "purchase_invoice_line_id",
        foreignKey = @ForeignKey(name = "PURCHASE_RECEIPT_LINES_PURCHASE_INVOICE_LINE_ID_FK"))
    private PurchaseInvoiceLine purchaseInvoiceLine;

    // Quantity received in this specific receipt transaction — may be a partial slice of the
    // source line's outstanding (quantity - quantityLoaded) amount. See PurchaseReceiptService.
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal quantity;

    // Order the user entered this line in on the frontend — lines are always displayed/returned sorted by this.
    // A single request line that gets split across multiple source lines (see PurchaseReceiptService)
    // produces multiple PurchaseReceiptLine rows sharing the same lineNumber.
    @Column(name = "line_number", nullable = false)
    private Integer lineNumber;

    // Inert: nothing currently loads from a Purchase Receipt, same as InventoryAdjustmentLine.
    @Column(name = "quantity_loaded", nullable = false, precision = 19, scale = 4)
    private BigDecimal quantityLoaded = BigDecimal.ZERO;
}
