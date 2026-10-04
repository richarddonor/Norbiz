package com.chardizard.Norbiz.models;

// Master-data records whose change history (audit_logs) is exposed per record via
// GET /master-data/{type}/{id}/history. entityType must match the entity's simple class name,
// which is what AuditableEntityListener stores in audit_logs.entity_type.
public enum MasterDataType {
    BRAND("Brand", "VIEW_BRAND"),
    ITEM_CATEGORY("ItemCategory", "VIEW_ITEM_CATEGORY"),
    ITEM_GROUP("ItemGroup", "VIEW_ITEM_GROUP"),
    ITEM("Item", "VIEW_ITEM"),
    ITEM_SKU("ItemSku", "VIEW_ITEM"),
    EMPLOYEE("Employee", "VIEW_EMPLOYEE"),
    WAREHOUSE("Warehouse", "VIEW_WAREHOUSE"),
    SUPPLIER("Supplier", "VIEW_SUPPLIER"),
    CUSTOMER("Customer", "VIEW_CUSTOMER"),
    USER("User", "VIEW_USER");

    private final String entityType;
    private final String viewPermission;

    MasterDataType(String entityType, String viewPermission) {
        this.entityType = entityType;
        this.viewPermission = viewPermission;
    }

    public String getEntityType() {
        return entityType;
    }

    public String getViewPermission() {
        return viewPermission;
    }
}
