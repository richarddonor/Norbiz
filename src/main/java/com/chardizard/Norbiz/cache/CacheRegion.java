package com.chardizard.Norbiz.cache;

import com.chardizard.Norbiz.models.*;

import java.util.List;

/**
 * One value per cached endpoint. {@code dependsOn} must list <b>every</b> entity whose fields end up
 * in the response (including names copied from associations, e.g. a PO lookup's supplier name) — a
 * write to any of them invalidates the region. Missing one means serving stale data until TTL.
 */
public enum CacheRegion {

    // ---- /lookups/* dropdowns ----
    LOOKUP_SUPPLIER(Kind.LOOKUP, Supplier.class),
    LOOKUP_CUSTOMER(Kind.LOOKUP, Customer.class, Warehouse.class),
    LOOKUP_WAREHOUSE(Kind.LOOKUP, Warehouse.class),
    LOOKUP_ITEM(Kind.LOOKUP, Item.class, ItemPrice.class),
    LOOKUP_ITEM_CATEGORY(Kind.LOOKUP, ItemCategory.class),
    LOOKUP_ITEM_GROUP(Kind.LOOKUP, ItemGroup.class),
    LOOKUP_EMPLOYEE(Kind.LOOKUP, Employee.class),
    LOOKUP_USER(Kind.LOOKUP, User.class),
    LOOKUP_ROLE(Kind.LOOKUP, Role.class),
    LOOKUP_PURCHASE_ORDER(Kind.LOOKUP, PurchaseOrder.class, PurchaseOrderLine.class, Item.class, Supplier.class, Warehouse.class),
    LOOKUP_PURCHASE_INVOICE(Kind.LOOKUP, PurchaseInvoice.class, PurchaseInvoiceLine.class, Item.class, Supplier.class, Warehouse.class),
    LOOKUP_DELIVERY_RECEIPT(Kind.LOOKUP, DeliveryReceipt.class, DeliveryReceiptLine.class, Item.class, Customer.class, Warehouse.class),
    LOOKUP_OUTLET_DELIVERY_RECEIPT(Kind.LOOKUP, OutletDeliveryReceipt.class, OutletDeliveryReceiptLine.class, Item.class, Customer.class,
            Warehouse.class, Employee.class),
    LOOKUP_STOCK_TRANSFER(Kind.LOOKUP, StockTransfer.class, StockTransferLine.class, Item.class, Customer.class, Warehouse.class),
    LOOKUP_OUTLET_PULL_OUT(Kind.LOOKUP, OutletPullOut.class, OutletPullOutLine.class, Item.class, Customer.class, Warehouse.class),
    LOOKUP_PULL_OUT_REASON(Kind.LOOKUP, PullOutReason.class),
    LOOKUP_BILL_OF_MATERIAL(Kind.LOOKUP, BillOfMaterial.class, BillOfMaterialLine.class, Item.class),

    // ---- master-data list endpoints ----
    LIST_BRAND(Kind.LIST, Brand.class, Company.class),
    LIST_ITEM_CATEGORY(Kind.LIST, ItemCategory.class, Company.class),
    LIST_ITEM_GROUP(Kind.LIST, ItemGroup.class, Company.class),
    LIST_PULL_OUT_REASON(Kind.LIST, PullOutReason.class, Company.class),
    LIST_BILL_OF_MATERIAL(Kind.LIST, BillOfMaterial.class, BillOfMaterialLine.class, Item.class, Company.class),
    LIST_ITEM(Kind.LIST, Item.class, ItemPrice.class, ItemSku.class, ItemCategory.class, ItemGroup.class, Company.class),
    LIST_ITEM_SKU(Kind.LIST, ItemSku.class, Item.class),
    LIST_WAREHOUSE(Kind.LIST, Warehouse.class, Company.class),
    LIST_SUPPLIER(Kind.LIST, Supplier.class, Company.class),
    LIST_CUSTOMER(Kind.LIST, Customer.class, Company.class, Warehouse.class),
    LIST_EMPLOYEE(Kind.LIST, Employee.class, User.class, Company.class),
    LIST_DOCUMENT_TEMPLATE(Kind.LIST, DocumentTemplate.class, Company.class),
    LIST_TRANSACTION_ACTION_DEFINITION(Kind.LIST, TransactionActionDefinition.class, Role.class, Company.class),
    LIST_ROLE(Kind.LIST, Role.class, Permission.class),
    LIST_PERMISSION(Kind.LIST, Permission.class),
    LIST_USER(Kind.LIST, User.class, Role.class, Company.class);

    public enum Kind { LOOKUP, LIST }

    private final Kind kind;
    private final List<String> dependsOn;

    CacheRegion(Kind kind, Class<?>... dependsOn) {
        this.kind = kind;
        this.dependsOn = java.util.Arrays.stream(dependsOn).map(Class::getSimpleName).sorted().toList();
    }

    public Kind kind() {
        return kind;
    }

    /** Entity names (simple class names) whose writes invalidate this region. */
    public List<String> dependsOn() {
        return dependsOn;
    }
}
