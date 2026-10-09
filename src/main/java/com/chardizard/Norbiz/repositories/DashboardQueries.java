package com.chardizard.Norbiz.repositories;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/**
 * Read-only aggregate queries behind the dashboard widgets (see DashboardService), kept in one place rather than
 * spread over each transaction's line repository. Every query is scoped to one company. Business-date ranges are
 * [from, to) over the UTC-midnight business date instants.
 */
@Repository
public class DashboardQueries {

    @PersistenceContext
    private EntityManager em;

    // ---- backlog widgets: one row per document still waiting --------------------------------------------------

    /** One document still waiting on something. {@code quantity}/{@code amount} are null where the widget has none. */
    public record BacklogRow(Long id, String referenceNumber, Instant date, Long counterpartyId, String counterpartyName,
                             String group, BigDecimal quantity, BigDecimal outstandingQuantity, BigDecimal outstandingAmount) {
    }

    /** Outlet Delivery Receipts with quantity still in transit (not voided, not fully received). */
    public List<BacklogRow> pendingOutletReceives(Long companyId) {
        return backlog("""
                SELECT dr.id, dr.referenceNumber, dr.deliveryDate, c.id, c.name, NULL,
                       SUM(l.quantity), SUM(l.quantity - l.quantityLoaded), SUM((l.quantity - l.quantityLoaded) * l.unitPrice)
                FROM DeliveryReceiptLine l JOIN l.deliveryReceipt dr JOIN dr.customer c
                WHERE dr.company.id = :companyId AND dr.voided = false AND dr.loaded = false
                  AND dr.destinationWarehouse IS NOT NULL
                GROUP BY dr.id, dr.referenceNumber, dr.deliveryDate, c.id, c.name
                HAVING SUM(l.quantity - l.quantityLoaded) > 0
                """, companyId);
    }

    /** Purchase Orders not invoiced (loaded) and not fully received. Amount = outstanding × line cost price. */
    public List<BacklogRow> pendingPurchaseOrders(Long companyId) {
        return backlog("""
                SELECT po.id, po.referenceNumber, po.orderDate, s.id, s.name, w.name,
                       SUM(l.quantity), SUM(l.quantity - l.quantityLoaded), SUM((l.quantity - l.quantityLoaded) * l.costPrice)
                FROM PurchaseOrderLine l JOIN l.purchaseOrder po JOIN po.supplier s JOIN po.warehouse w
                WHERE po.company.id = :companyId AND po.voided = false AND po.loaded = false
                GROUP BY po.id, po.referenceNumber, po.orderDate, s.id, s.name, w.name
                HAVING SUM(l.quantity - l.quantityLoaded) > 0
                """, companyId);
    }

    /** Outlet Pull Outs not yet fully received back by Pull Out Receive(s). Group = pull-out reason. */
    public List<BacklogRow> pullOutsAwaitingReceive(Long companyId) {
        return backlog("""
                SELECT p.id, p.referenceNumber, p.pullOutDate, c.id, c.name, r.name,
                       SUM(l.quantity), SUM(l.quantity - l.quantityLoaded), SUM((l.quantity - l.quantityLoaded) * l.unitPrice)
                FROM OutletPullOutLine l JOIN l.outletPullOut p JOIN p.customer c LEFT JOIN p.reason r
                WHERE p.company.id = :companyId AND p.voided = false AND p.loaded = false
                GROUP BY p.id, p.referenceNumber, p.pullOutDate, c.id, c.name, r.name
                HAVING SUM(l.quantity - l.quantityLoaded) > 0
                """, companyId);
    }

    /** A not-yet-PAID invoice: discounted line subtotal, header discount % and fees, combined by the caller. */
    public record InvoiceRow(Long id, String referenceNumber, Instant date, Long supplierId, String supplierName,
                             String paymentStatus, BigDecimal lineSubtotal, BigDecimal headerDiscountPercentage) {
    }

    public List<InvoiceRow> unpaidPurchaseInvoices(Long companyId) {
        return em.createQuery("""
                        SELECT pi.id, pi.referenceNumber, pi.invoiceDate, s.id, s.name, pi.paymentStatus,
                               COALESCE(SUM(l.quantity * l.costPrice * (1 - l.discountPercentage / 100)), 0), pi.discountPercentage
                        FROM PurchaseInvoice pi JOIN pi.supplier s LEFT JOIN pi.lines l
                        WHERE pi.company.id = :companyId AND pi.voided = false
                          AND pi.paymentStatus <> com.chardizard.Norbiz.models.PaymentStatus.PAID
                        GROUP BY pi.id, pi.referenceNumber, pi.invoiceDate, s.id, s.name, pi.paymentStatus, pi.discountPercentage
                        """, Tuple.class)
                .setParameter("companyId", companyId)
                .getResultList().stream()
                .map(t -> new InvoiceRow(lng(t.get(0)), (String) t.get(1), (Instant) t.get(2), lng(t.get(3)), (String) t.get(4),
                        String.valueOf(t.get(5)), bd(t.get(6)), bd(t.get(7))))
                .toList();
    }

    /** Fee totals per not-yet-PAID invoice: {invoiceId, total}. */
    public List<Object[]> unpaidPurchaseInvoiceFees(Long companyId) {
        return em.createQuery("""
                        SELECT pi.id, SUM(f.amount)
                        FROM PurchaseInvoiceFee f JOIN f.purchaseInvoice pi
                        WHERE pi.company.id = :companyId AND pi.voided = false
                          AND pi.paymentStatus <> com.chardizard.Norbiz.models.PaymentStatus.PAID
                        GROUP BY pi.id
                        """, Object[].class)
                .setParameter("companyId", companyId)
                .getResultList();
    }

    private List<BacklogRow> backlog(String jpql, Long companyId) {
        return em.createQuery(jpql, Tuple.class)
                .setParameter("companyId", companyId)
                .getResultList().stream()
                .map(t -> new BacklogRow(lng(t.get(0)), (String) t.get(1), (Instant) t.get(2), lng(t.get(3)), (String) t.get(4),
                        (String) t.get(5), bd(t.get(6)), bd(t.get(7)), bd(t.get(8))))
                .toList();
    }

    // ---- stock ----------------------------------------------------------------------------------------------

    public record WarehouseStockRow(Long id, String name, boolean main, boolean outlet, long itemsInTransit,
                                    BigDecimal onHand, BigDecimal transit) {
    }

    public List<WarehouseStockRow> stockByWarehouse(Long companyId) {
        return em.createQuery("""
                        SELECT w.id, w.name, w.main, w.outlet,
                               SUM(CASE WHEN b.transitQuantity <> 0 THEN 1 ELSE 0 END), SUM(b.quantity), SUM(b.transitQuantity)
                        FROM InventoryBalance b JOIN b.warehouse w
                        WHERE w.company.id = :companyId
                        GROUP BY w.id, w.name, w.main, w.outlet
                        """, Tuple.class)
                .setParameter("companyId", companyId)
                .getResultList().stream()
                .map(t -> new WarehouseStockRow(lng(t.get(0)), (String) t.get(1), (Boolean) t.get(2), (Boolean) t.get(3),
                        lng(t.get(4)), bd(t.get(5)), bd(t.get(6))))
                .toList();
    }

    public long distinctItemsInTransit(Long companyId) {
        return em.createQuery("""
                        SELECT COUNT(DISTINCT b.item.id) FROM InventoryBalance b
                        WHERE b.warehouse.company.id = :companyId AND b.transitQuantity <> 0
                        """, Long.class)
                .setParameter("companyId", companyId)
                .getSingleResult();
    }

    /** Units an outlet sold of an item over the period (gross, from Outlet Delivery Receipts). */
    public record OutletItemSalesRow(Long outletId, String outletCode, String outletName, Long warehouseId,
                                     Long itemId, String itemCode, String itemName, BigDecimal sold) {
    }

    public List<OutletItemSalesRow> outletItemSales(Long companyId, Instant from, Instant to) {
        return em.createQuery("""
                        SELECT c.id, c.code, c.name, w.id, i.id, i.itemCode, i.name, SUM(l.quantity)
                        FROM OutletDeliveryReceiptLine l JOIN l.outletDeliveryReceipt o JOIN o.customer c JOIN o.warehouse w JOIN l.item i
                        WHERE o.company.id = :companyId AND o.voided = false
                          AND o.deliveryDate >= :from AND o.deliveryDate < :to
                        GROUP BY c.id, c.code, c.name, w.id, i.id, i.itemCode, i.name
                        """, Tuple.class)
                .setParameter("companyId", companyId).setParameter("from", from).setParameter("to", to)
                .getResultList().stream()
                .map(t -> new OutletItemSalesRow(lng(t.get(0)), (String) t.get(1), (String) t.get(2), lng(t.get(3)),
                        lng(t.get(4)), (String) t.get(5), (String) t.get(6), bd(t.get(7))))
                .toList();
    }

    /** On-hand per {warehouseId, itemId, quantity} for the given warehouses. */
    public List<Object[]> onHand(Collection<Long> warehouseIds) {
        if (warehouseIds.isEmpty()) return List.of();
        return em.createQuery("""
                        SELECT b.warehouse.id, b.item.id, b.quantity FROM InventoryBalance b
                        WHERE b.warehouse.id IN :warehouseIds
                        """, Object[].class)
                .setParameter("warehouseIds", warehouseIds)
                .getResultList();
    }

    // ---- outlet sales (ODR − returns) -------------------------------------------------------------------------

    /** An amount on one business date, optionally per entity (outlet/agent). */
    public record DatedAmount(Long id, String name, Instant date, BigDecimal amount, long documents) {
    }

    public List<DatedAmount> outletSalesByDay(Long companyId, Instant from, Instant to) {
        return dated("""
                SELECT NULL, NULL, o.deliveryDate, SUM(l.quantity * l.unitPrice), COUNT(DISTINCT o.id)
                FROM OutletDeliveryReceiptLine l JOIN l.outletDeliveryReceipt o
                WHERE o.company.id = :companyId AND o.voided = false AND o.deliveryDate >= :from AND o.deliveryDate < :to
                GROUP BY o.deliveryDate
                """, companyId, from, to);
    }

    public List<DatedAmount> outletReturnsByDay(Long companyId, Instant from, Instant to) {
        return dated("""
                SELECT NULL, NULL, r.returnDate, SUM(l.quantity * l.unitPrice), COUNT(DISTINCT r.id)
                FROM OutletDeliveryReturnLine l JOIN l.outletDeliveryReturn r
                WHERE r.company.id = :companyId AND r.voided = false AND r.returnDate >= :from AND r.returnDate < :to
                GROUP BY r.returnDate
                """, companyId, from, to);
    }

    public List<DatedAmount> outletSalesByOutlet(Long companyId, Instant from, Instant to) {
        return dated("""
                SELECT c.id, c.name, NULL, SUM(l.quantity * l.unitPrice), COUNT(DISTINCT o.id)
                FROM OutletDeliveryReceiptLine l JOIN l.outletDeliveryReceipt o JOIN o.customer c
                WHERE o.company.id = :companyId AND o.voided = false AND o.deliveryDate >= :from AND o.deliveryDate < :to
                GROUP BY c.id, c.name
                """, companyId, from, to);
    }

    public List<DatedAmount> outletReturnsByOutlet(Long companyId, Instant from, Instant to) {
        return dated("""
                SELECT c.id, c.name, NULL, SUM(l.quantity * l.unitPrice), COUNT(DISTINCT r.id)
                FROM OutletDeliveryReturnLine l JOIN l.outletDeliveryReturn r JOIN r.customer c
                WHERE r.company.id = :companyId AND r.voided = false AND r.returnDate >= :from AND r.returnDate < :to
                GROUP BY c.id, c.name
                """, companyId, from, to);
    }

    /** Per agent per day. */
    public List<DatedAmount> agentSalesByDay(Long companyId, Instant from, Instant to) {
        return dated("""
                SELECT a.id, CONCAT(a.firstName, ' ', a.lastName), o.deliveryDate, SUM(l.quantity * l.unitPrice), COUNT(DISTINCT o.id)
                FROM OutletDeliveryReceiptLine l JOIN l.outletDeliveryReceipt o JOIN o.agent a
                WHERE o.company.id = :companyId AND o.voided = false AND o.deliveryDate >= :from AND o.deliveryDate < :to
                GROUP BY a.id, a.firstName, a.lastName, o.deliveryDate
                """, companyId, from, to);
    }

    /** Per agent per day. */
    public List<DatedAmount> agentReturnsByDay(Long companyId, Instant from, Instant to) {
        return dated("""
                SELECT a.id, CONCAT(a.firstName, ' ', a.lastName), r.returnDate, SUM(l.quantity * l.unitPrice), COUNT(DISTINCT r.id)
                FROM OutletDeliveryReturnLine l JOIN l.outletDeliveryReturn r JOIN r.agent a
                WHERE r.company.id = :companyId AND r.voided = false AND r.returnDate >= :from AND r.returnDate < :to
                GROUP BY a.id, a.firstName, a.lastName, r.returnDate
                """, companyId, from, to);
    }

    private List<DatedAmount> dated(String jpql, Long companyId, Instant from, Instant to) {
        return em.createQuery(jpql, Tuple.class)
                .setParameter("companyId", companyId).setParameter("from", from).setParameter("to", to)
                .getResultList().stream()
                .map(t -> new DatedAmount(lng(t.get(0)), (String) t.get(1), (Instant) t.get(2), bd(t.get(3)), lng(t.get(4))))
                .toList();
    }

    // ---- inventory adjustments --------------------------------------------------------------------------------

    /** Units added/removed per business date (or per warehouse when grouped by it). */
    public record AdjustmentTotal(Long warehouseId, String warehouseName, Instant date, BigDecimal added, BigDecimal removed, long documents) {
    }

    public List<AdjustmentTotal> adjustmentsByDay(Long companyId, Instant from, Instant to) {
        return adjustments("""
                SELECT NULL, NULL, a.adjustmentDate,
                       SUM(CASE WHEN l.quantity > 0 THEN l.quantity ELSE 0 END),
                       SUM(CASE WHEN l.quantity < 0 THEN -l.quantity ELSE 0 END),
                       COUNT(DISTINCT a.id)
                FROM InventoryAdjustmentLine l JOIN l.adjustment a
                WHERE a.company.id = :companyId AND a.voided = false AND a.adjustmentDate >= :from AND a.adjustmentDate < :to
                GROUP BY a.adjustmentDate
                """, companyId, from, to);
    }

    public List<AdjustmentTotal> adjustmentsByWarehouse(Long companyId, Instant from, Instant to) {
        return adjustments("""
                SELECT w.id, w.name, NULL,
                       SUM(CASE WHEN l.quantity > 0 THEN l.quantity ELSE 0 END),
                       SUM(CASE WHEN l.quantity < 0 THEN -l.quantity ELSE 0 END),
                       COUNT(DISTINCT a.id)
                FROM InventoryAdjustmentLine l JOIN l.adjustment a JOIN a.warehouse w
                WHERE a.company.id = :companyId AND a.voided = false AND a.adjustmentDate >= :from AND a.adjustmentDate < :to
                GROUP BY w.id, w.name
                """, companyId, from, to);
    }

    /** {reason (as first written), document count}, most frequent first, grouped case-insensitively. */
    public List<Object[]> topAdjustmentReasons(Long companyId, Instant from, Instant to, int limit) {
        return em.createQuery("""
                        SELECT MIN(TRIM(a.reason)), COUNT(a.id)
                        FROM InventoryAdjustment a
                        WHERE a.company.id = :companyId AND a.voided = false AND a.adjustmentDate >= :from AND a.adjustmentDate < :to
                          AND a.reason IS NOT NULL AND TRIM(a.reason) <> ''
                        GROUP BY LOWER(TRIM(a.reason))
                        ORDER BY COUNT(a.id) DESC
                        """, Object[].class)
                .setParameter("companyId", companyId).setParameter("from", from).setParameter("to", to)
                .setMaxResults(limit)
                .getResultList();
    }

    private List<AdjustmentTotal> adjustments(String jpql, Long companyId, Instant from, Instant to) {
        return em.createQuery(jpql, Tuple.class)
                .setParameter("companyId", companyId).setParameter("from", from).setParameter("to", to)
                .getResultList().stream()
                .map(t -> new AdjustmentTotal(lng(t.get(0)), (String) t.get(1), (Instant) t.get(2), bd(t.get(3)), bd(t.get(4)), lng(t.get(5))))
                .toList();
    }

    // ---- transaction activity ---------------------------------------------------------------------------------

    public record ActivityRow(String transactionType, String eventType, LocalDate day, long count) {
    }

    /**
     * CREATED/VOIDED transaction events per type per calendar day in {@code timeZone} (an IANA id the caller has
     * already validated). performed_at is a real timestamp, not a business date, hence the zone conversion.
     */
    @SuppressWarnings("unchecked")
    public List<ActivityRow> transactionActivity(Long companyId, Instant from, Instant to, String timeZone) {
        List<Object[]> rows = em.createNativeQuery("""
                        SELECT transaction_type, event_type, CAST(performed_at AT TIME ZONE :tz AS date), COUNT(*)
                        FROM transaction_events
                        WHERE company_id = :companyId AND performed_at >= :from AND performed_at < :to
                          AND event_type IN ('CREATED', 'VOIDED')
                        GROUP BY 1, 2, 3
                        """)
                .setParameter("tz", timeZone).setParameter("companyId", companyId)
                .setParameter("from", from).setParameter("to", to)
                .getResultList();
        return rows.stream()
                .map(r -> new ActivityRow((String) r[0], (String) r[1], toLocalDate(r[2]), lng(r[3])))
                .toList();
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private static LocalDate toLocalDate(Object value) {
        if (value instanceof LocalDate d) return d;
        if (value instanceof Date d) return d.toLocalDate();
        return LocalDate.parse(String.valueOf(value));
    }

    static Long lng(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    static BigDecimal bd(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal b) return b;
        return new BigDecimal(value.toString());
    }
}
