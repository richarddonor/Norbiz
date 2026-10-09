package com.chardizard.Norbiz.config;

import com.chardizard.Norbiz.models.Company;
import com.chardizard.Norbiz.models.DashboardWidget;
import com.chardizard.Norbiz.models.DetailedReportType;
import com.chardizard.Norbiz.models.Permission;
import com.chardizard.Norbiz.models.Role;
import com.chardizard.Norbiz.models.User;
import com.chardizard.Norbiz.repositories.CompanyRepository;
import com.chardizard.Norbiz.repositories.PermissionRepository;
import com.chardizard.Norbiz.repositories.RoleRepository;
import com.chardizard.Norbiz.repositories.UserRepository;
import com.chardizard.Norbiz.services.DefaultDocumentTemplateProvisioner;
import com.chardizard.Norbiz.services.OutletWarehouseProvisioner;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final CompanyRepository companyRepository;
    private final PasswordEncoder passwordEncoder;
    private final DefaultDocumentTemplateProvisioner defaultDocumentTemplateProvisioner;
    private final OutletWarehouseProvisioner outletWarehouseProvisioner;

    @Override
    public void run(String... args) {
        // Permissions
        Permission manageSystemPermission = findOrCreate("MANAGE_SYSTEM", "Security - System Manage");
        Permission viewUserPermission     = findOrCreate("VIEW_USER",     "Security - User View");
        Permission createUserPermission   = findOrCreate("CREATE_USER",   "Security - User Create");
        Permission updateUserPermission   = findOrCreate("UPDATE_USER",   "Security - User Update");
        Permission deleteUserPermission   = findOrCreate("DELETE_USER",   "Security - User Delete");
        Permission resetUserPasswordPermission = findOrCreate("RESET_USER_PASSWORD", "Security - User Reset Password");
        Permission viewRolePermission     = findOrCreate("VIEW_ROLE",     "Security - Role View");
        Permission createRolePermission   = findOrCreate("CREATE_ROLE",   "Security - Role Create");
        Permission updateRolePermission   = findOrCreate("UPDATE_ROLE",   "Security - Role Update");
        Permission deleteRolePermission   = findOrCreate("DELETE_ROLE",   "Security - Role Delete");
        Permission viewItemPermission     = findOrCreate("VIEW_ITEM",     "Maintenance - Item View");
        Permission createItemPermission   = findOrCreate("CREATE_ITEM",   "Maintenance - Item Create");
        Permission updateItemPermission   = findOrCreate("UPDATE_ITEM",   "Maintenance - Item Update");
        Permission deleteItemPermission   = findOrCreate("DELETE_ITEM",   "Maintenance - Item Delete");
        Permission viewCostPricePermission = findOrCreate("VIEW_COST_PRICE", "Maintenance - Item Cost Price View");
        Permission viewBrandPermission            = findOrCreate("VIEW_BRAND",            "Maintenance - Brand View");
        Permission createBrandPermission          = findOrCreate("CREATE_BRAND",          "Maintenance - Brand Create");
        Permission updateBrandPermission          = findOrCreate("UPDATE_BRAND",          "Maintenance - Brand Update");
        Permission deleteBrandPermission          = findOrCreate("DELETE_BRAND",          "Maintenance - Brand Delete");
        Permission viewItemCategoryPermission     = findOrCreate("VIEW_ITEM_CATEGORY",    "Maintenance - Item Category View");
        Permission createItemCategoryPermission   = findOrCreate("CREATE_ITEM_CATEGORY",  "Maintenance - Item Category Create");
        Permission updateItemCategoryPermission   = findOrCreate("UPDATE_ITEM_CATEGORY",  "Maintenance - Item Category Update");
        Permission deleteItemCategoryPermission   = findOrCreate("DELETE_ITEM_CATEGORY",  "Maintenance - Item Category Delete");
        Permission viewItemGroupPermission        = findOrCreate("VIEW_ITEM_GROUP",       "Maintenance - Item Group View");
        Permission createItemGroupPermission      = findOrCreate("CREATE_ITEM_GROUP",     "Maintenance - Item Group Create");
        Permission updateItemGroupPermission      = findOrCreate("UPDATE_ITEM_GROUP",     "Maintenance - Item Group Update");
        Permission deleteItemGroupPermission      = findOrCreate("DELETE_ITEM_GROUP",     "Maintenance - Item Group Delete");
        Permission viewWarehousePermission        = findOrCreate("VIEW_WAREHOUSE",        "Maintenance - Warehouse View");
        Permission createWarehousePermission      = findOrCreate("CREATE_WAREHOUSE",      "Maintenance - Warehouse Create");
        Permission updateWarehousePermission      = findOrCreate("UPDATE_WAREHOUSE",      "Maintenance - Warehouse Update");
        Permission deleteWarehousePermission      = findOrCreate("DELETE_WAREHOUSE",      "Maintenance - Warehouse Delete");
        Permission viewEmployeePermission         = findOrCreate("VIEW_EMPLOYEE",         "HR - Employee View");
        Permission createEmployeePermission       = findOrCreate("CREATE_EMPLOYEE",       "HR - Employee Create");
        Permission updateEmployeePermission       = findOrCreate("UPDATE_EMPLOYEE",       "HR - Employee Update");
        Permission deleteEmployeePermission       = findOrCreate("DELETE_EMPLOYEE",       "HR - Employee Delete");
        Permission viewSupplierPermission         = findOrCreate("VIEW_SUPPLIER",         "Maintenance - Supplier View");
        Permission createSupplierPermission       = findOrCreate("CREATE_SUPPLIER",       "Maintenance - Supplier Create");
        Permission updateSupplierPermission       = findOrCreate("UPDATE_SUPPLIER",       "Maintenance - Supplier Update");
        Permission deleteSupplierPermission       = findOrCreate("DELETE_SUPPLIER",       "Maintenance - Supplier Delete");
        Permission viewCustomerPermission         = findOrCreate("VIEW_CUSTOMER",         "Maintenance - Customer View");
        Permission createCustomerPermission       = findOrCreate("CREATE_CUSTOMER",       "Maintenance - Customer Create");
        Permission updateCustomerPermission       = findOrCreate("UPDATE_CUSTOMER",       "Maintenance - Customer Update");
        Permission deleteCustomerPermission       = findOrCreate("DELETE_CUSTOMER",       "Maintenance - Customer Delete");
        Permission viewInventoryAdjustmentPermission   = findOrCreate("VIEW_INVENTORY_ADJUSTMENT",   "Inventory - Adjustment View");
        Permission createInventoryAdjustmentPermission = findOrCreate("CREATE_INVENTORY_ADJUSTMENT", "Inventory - Adjustment Create");
        Permission voidInventoryAdjustmentPermission   = findOrCreate("VOID_INVENTORY_ADJUSTMENT",   "Inventory - Adjustment Void");
        Permission viewPurchaseOrderPermission         = findOrCreate("VIEW_PURCHASE_ORDER",         "Purchases - Purchase Order View");
        Permission createPurchaseOrderPermission       = findOrCreate("CREATE_PURCHASE_ORDER",       "Purchases - Purchase Order Create");
        Permission voidPurchaseOrderPermission         = findOrCreate("VOID_PURCHASE_ORDER",         "Purchases - Purchase Order Void");
        Permission viewPurchaseInvoicePermission       = findOrCreate("VIEW_PURCHASE_INVOICE",       "Purchases - Purchase Invoice View");
        Permission createPurchaseInvoicePermission     = findOrCreate("CREATE_PURCHASE_INVOICE",     "Purchases - Purchase Invoice Create");
        Permission voidPurchaseInvoicePermission       = findOrCreate("VOID_PURCHASE_INVOICE",       "Purchases - Purchase Invoice Void");
        Permission viewPurchaseReceivePermission       = findOrCreate("VIEW_PURCHASE_RECEIVE",       "Purchases - Purchase Receive View");
        Permission createPurchaseReceivePermission     = findOrCreate("CREATE_PURCHASE_RECEIVE",     "Purchases - Purchase Receive Create");
        Permission voidPurchaseReceivePermission       = findOrCreate("VOID_PURCHASE_RECEIVE",       "Purchases - Purchase Receive Void");
        Permission viewDeliveryReceiptPermission       = findOrCreate("VIEW_DELIVERY_RECEIPT",       "Sales - Delivery Receipt View");
        Permission createDeliveryReceiptPermission     = findOrCreate("CREATE_DELIVERY_RECEIPT",     "Sales - Delivery Receipt Create");
        Permission voidDeliveryReceiptPermission       = findOrCreate("VOID_DELIVERY_RECEIPT",       "Sales - Delivery Receipt Void");
        Permission viewOutletReceivePermission         = findOrCreate("VIEW_OUTLET_RECEIVE",         "Inventory - Outlet Receive View");
        Permission createOutletReceivePermission       = findOrCreate("CREATE_OUTLET_RECEIVE",       "Inventory - Outlet Receive Create");
        Permission voidOutletReceivePermission         = findOrCreate("VOID_OUTLET_RECEIVE",         "Inventory - Outlet Receive Void");
        Permission viewOutletDeliveryReceiptPermission   = findOrCreate("VIEW_OUTLET_DELIVERY_RECEIPT",   "Sales - Outlet Delivery Receipt View");
        Permission createOutletDeliveryReceiptPermission = findOrCreate("CREATE_OUTLET_DELIVERY_RECEIPT", "Sales - Outlet Delivery Receipt Create");
        Permission voidOutletDeliveryReceiptPermission   = findOrCreate("VOID_OUTLET_DELIVERY_RECEIPT",   "Sales - Outlet Delivery Receipt Void");
        Permission viewOutletDeliveryReturnPermission    = findOrCreate("VIEW_OUTLET_DELIVERY_RETURN",    "Sales - Outlet Delivery Return View");
        Permission createOutletDeliveryReturnPermission  = findOrCreate("CREATE_OUTLET_DELIVERY_RETURN",  "Sales - Outlet Delivery Return Create");
        Permission voidOutletDeliveryReturnPermission    = findOrCreate("VOID_OUTLET_DELIVERY_RETURN",    "Sales - Outlet Delivery Return Void");
        Permission viewStockTransferPermission           = findOrCreate("VIEW_STOCK_TRANSFER",            "Sales - Stock Transfer View");
        Permission createStockTransferPermission         = findOrCreate("CREATE_STOCK_TRANSFER",          "Sales - Stock Transfer Create");
        Permission voidStockTransferPermission           = findOrCreate("VOID_STOCK_TRANSFER",            "Sales - Stock Transfer Void");
        Permission viewOutletPullOutPermission           = findOrCreate("VIEW_OUTLET_PULL_OUT",           "Sales - Outlet Pull Out View");
        Permission createOutletPullOutPermission         = findOrCreate("CREATE_OUTLET_PULL_OUT",         "Sales - Outlet Pull Out Create");
        Permission voidOutletPullOutPermission           = findOrCreate("VOID_OUTLET_PULL_OUT",           "Sales - Outlet Pull Out Void");
        Permission viewPullOutReceivePermission          = findOrCreate("VIEW_PULL_OUT_RECEIVE",          "Sales - Pull Out Receive View");
        Permission createPullOutReceivePermission        = findOrCreate("CREATE_PULL_OUT_RECEIVE",        "Sales - Pull Out Receive Create");
        Permission voidPullOutReceivePermission          = findOrCreate("VOID_PULL_OUT_RECEIVE",          "Sales - Pull Out Receive Void");
        Permission viewAssemblyPermission                = findOrCreate("VIEW_ASSEMBLY",                  "Inventory - Assembly View");
        Permission createAssemblyPermission              = findOrCreate("CREATE_ASSEMBLY",                "Inventory - Assembly Create");
        Permission voidAssemblyPermission                = findOrCreate("VOID_ASSEMBLY",                  "Inventory - Assembly Void");
        Permission viewPullOutReasonPermission           = findOrCreate("VIEW_PULL_OUT_REASON",           "Maintenance - Pull Out Reason View");
        Permission createPullOutReasonPermission         = findOrCreate("CREATE_PULL_OUT_REASON",         "Maintenance - Pull Out Reason Create");
        Permission updatePullOutReasonPermission         = findOrCreate("UPDATE_PULL_OUT_REASON",         "Maintenance - Pull Out Reason Update");
        Permission deletePullOutReasonPermission         = findOrCreate("DELETE_PULL_OUT_REASON",         "Maintenance - Pull Out Reason Delete");
        Permission viewBillOfMaterialPermission          = findOrCreate("VIEW_BILL_OF_MATERIAL",          "Maintenance - Bill of Materials View");
        Permission createBillOfMaterialPermission        = findOrCreate("CREATE_BILL_OF_MATERIAL",        "Maintenance - Bill of Materials Create");
        Permission updateBillOfMaterialPermission        = findOrCreate("UPDATE_BILL_OF_MATERIAL",        "Maintenance - Bill of Materials Update");
        Permission deleteBillOfMaterialPermission        = findOrCreate("DELETE_BILL_OF_MATERIAL",        "Maintenance - Bill of Materials Delete");
        Permission viewInventoryReportPermission       = findOrCreate("VIEW_INVENTORY_REPORT",       "Inventory - Report View");
        Permission manageDocumentTemplatesPermission   = findOrCreate("MANAGE_DOCUMENT_TEMPLATES",   "Document Templates - Manage (design + print)");
        Permission manageTransactionActionsPermission  = findOrCreate("MANAGE_TRANSACTION_ACTIONS",  "Configuration - Transaction Actions Manage");
        // One VIEW_<TYPE>_DETAILED_REPORT per "<Transaction> - Detailed" report
        List<Permission> detailedReportPermissions = Arrays.stream(DetailedReportType.values())
                .map(t -> findOrCreate(t.getPermission(), t.getPermissionDescription()))
                .toList();
        // One VIEW_DASHBOARD_<KEY> per dashboard widget
        List<Permission> dashboardWidgetPermissions = Arrays.stream(DashboardWidget.values())
                .map(w -> findOrCreate(w.getPermission(), w.getPermissionDescription()))
                .toList();
        List<Permission> reportPermissions = new ArrayList<>(detailedReportPermissions);
        reportPermissions.addAll(dashboardWidgetPermissions);

        // Roles — permissions are always synced on startup
        Role adminRole = roleRepository.findByName("ADMIN").orElseGet(() -> {
            Role r = new Role();
            r.setName("ADMIN");
            return r;
        });
        adminRole.setDisplayName("Administrator");
        adminRole.setPermissions(Set.of());
        roleRepository.save(adminRole);

        // SYSTEM_ADMIN: business-level access
        Role systemAdminRole = roleRepository.findByName("SYSTEM_ADMIN").orElseGet(() -> {
            Role r = new Role();
            r.setName("SYSTEM_ADMIN");
            return r;
        });
        systemAdminRole.setDisplayName("System Administrator");
        systemAdminRole.setPermissions(withAll(reportPermissions,
                viewUserPermission, createUserPermission, viewRolePermission,
                viewItemPermission, createItemPermission, updateItemPermission,
                viewBrandPermission, createBrandPermission, updateBrandPermission,
                viewItemCategoryPermission, createItemCategoryPermission, updateItemCategoryPermission,
                viewItemGroupPermission, createItemGroupPermission, updateItemGroupPermission,
                viewWarehousePermission, createWarehousePermission, updateWarehousePermission,
                viewEmployeePermission, createEmployeePermission, updateEmployeePermission,
                viewSupplierPermission, createSupplierPermission, updateSupplierPermission,
                viewCustomerPermission, createCustomerPermission, updateCustomerPermission,
                viewInventoryAdjustmentPermission, createInventoryAdjustmentPermission, voidInventoryAdjustmentPermission,
                viewPurchaseOrderPermission, createPurchaseOrderPermission, voidPurchaseOrderPermission,
                viewPurchaseInvoicePermission, createPurchaseInvoicePermission, voidPurchaseInvoicePermission,
                viewPurchaseReceivePermission, createPurchaseReceivePermission, voidPurchaseReceivePermission,
                viewDeliveryReceiptPermission, createDeliveryReceiptPermission, voidDeliveryReceiptPermission,
                viewOutletReceivePermission, createOutletReceivePermission, voidOutletReceivePermission,
                viewOutletDeliveryReceiptPermission, createOutletDeliveryReceiptPermission, voidOutletDeliveryReceiptPermission,
                viewOutletDeliveryReturnPermission, createOutletDeliveryReturnPermission, voidOutletDeliveryReturnPermission,
                viewStockTransferPermission, createStockTransferPermission, voidStockTransferPermission,
                viewOutletPullOutPermission, createOutletPullOutPermission, voidOutletPullOutPermission,
                viewPullOutReceivePermission, createPullOutReceivePermission, voidPullOutReceivePermission,
                viewAssemblyPermission, createAssemblyPermission, voidAssemblyPermission,
                viewPullOutReasonPermission, createPullOutReasonPermission, updatePullOutReasonPermission,
                viewBillOfMaterialPermission, createBillOfMaterialPermission, updateBillOfMaterialPermission,
                viewInventoryReportPermission, manageDocumentTemplatesPermission, manageTransactionActionsPermission));
        roleRepository.save(systemAdminRole);

        // SUPER_ADMIN: complete access including system management
        Role superAdminRole = roleRepository.findByName("SUPER_ADMIN").orElseGet(() -> {
            Role r = new Role();
            r.setName("SUPER_ADMIN");
            return r;
        });
        superAdminRole.setDisplayName("Super Administrator");
        superAdminRole.setPermissions(withAll(reportPermissions, manageSystemPermission,
                viewUserPermission, createUserPermission, updateUserPermission, deleteUserPermission, resetUserPasswordPermission,
                viewRolePermission, createRolePermission, updateRolePermission, deleteRolePermission,
                viewItemPermission, createItemPermission, updateItemPermission, deleteItemPermission, viewCostPricePermission,
                viewBrandPermission, createBrandPermission, updateBrandPermission, deleteBrandPermission,
                viewItemCategoryPermission, createItemCategoryPermission, updateItemCategoryPermission, deleteItemCategoryPermission,
                viewItemGroupPermission, createItemGroupPermission, updateItemGroupPermission, deleteItemGroupPermission,
                viewWarehousePermission, createWarehousePermission, updateWarehousePermission, deleteWarehousePermission,
                viewEmployeePermission, createEmployeePermission, updateEmployeePermission, deleteEmployeePermission,
                viewSupplierPermission, createSupplierPermission, updateSupplierPermission, deleteSupplierPermission,
                viewCustomerPermission, createCustomerPermission, updateCustomerPermission, deleteCustomerPermission,
                viewInventoryAdjustmentPermission, createInventoryAdjustmentPermission, voidInventoryAdjustmentPermission,
                viewPurchaseOrderPermission, createPurchaseOrderPermission, voidPurchaseOrderPermission,
                viewPurchaseInvoicePermission, createPurchaseInvoicePermission, voidPurchaseInvoicePermission,
                viewPurchaseReceivePermission, createPurchaseReceivePermission, voidPurchaseReceivePermission,
                viewDeliveryReceiptPermission, createDeliveryReceiptPermission, voidDeliveryReceiptPermission,
                viewOutletReceivePermission, createOutletReceivePermission, voidOutletReceivePermission,
                viewOutletDeliveryReceiptPermission, createOutletDeliveryReceiptPermission, voidOutletDeliveryReceiptPermission,
                viewOutletDeliveryReturnPermission, createOutletDeliveryReturnPermission, voidOutletDeliveryReturnPermission,
                viewStockTransferPermission, createStockTransferPermission, voidStockTransferPermission,
                viewOutletPullOutPermission, createOutletPullOutPermission, voidOutletPullOutPermission,
                viewPullOutReceivePermission, createPullOutReceivePermission, voidPullOutReceivePermission,
                viewAssemblyPermission, createAssemblyPermission, voidAssemblyPermission,
                viewPullOutReasonPermission, createPullOutReasonPermission, updatePullOutReasonPermission, deletePullOutReasonPermission,
                viewBillOfMaterialPermission, createBillOfMaterialPermission, updateBillOfMaterialPermission, deleteBillOfMaterialPermission,
                viewInventoryReportPermission, manageDocumentTemplatesPermission, manageTransactionActionsPermission));
        roleRepository.save(superAdminRole);

        // Default company — super admin is pre-assigned; other users are assigned to tenants later
        Company defaultCompany = companyRepository.findByName("Norbiz").orElseGet(() -> {
            Company c = new Company();
            c.setName("Norbiz");
            return companyRepository.save(c);
        });

        // Every company gets a default print template per document type (only missing ones are created)
        defaultDocumentTemplateProvisioner.ensureDefaultsForAllCompanies();

        // Every OUTLET customer gets its own warehouse (only outlets still missing one)
        outletWarehouseProvisioner.ensureForAllOutlets();

        // Seed users (skip if already present)
        if (userRepository.findByUsername("admin").isEmpty()) {
            User admin = new User();
            admin.setUsername("admin");
            admin.setEmail("admin@norbiz.com");
            admin.setPassword(passwordEncoder.encode("password"));
            admin.setRoles(Set.of(adminRole));
            userRepository.save(admin);
        }

        if (userRepository.findByUsername("super_admin").isEmpty()) {
            User superAdmin = new User();
            superAdmin.setUsername("super_admin");
            superAdmin.setEmail("super.admin@norbiz.com");
            superAdmin.setPassword(passwordEncoder.encode("password"));
            superAdmin.setRoles(Set.of(superAdminRole));
            superAdmin.setCompanies(Set.of(defaultCompany));
            userRepository.save(superAdmin);
        }

        if (userRepository.findByUsername("system_admin").isEmpty()) {
            User systemAdmin = new User();
            systemAdmin.setUsername("system_admin");
            systemAdmin.setEmail("system.admin@norbiz.com");
            systemAdmin.setPassword(passwordEncoder.encode("password"));
            systemAdmin.setRoles(Set.of(systemAdminRole));
            userRepository.save(systemAdmin);
        }
    }

    private static Set<Permission> withAll(List<Permission> extra, Permission... permissions) {
        Set<Permission> all = new HashSet<>(Arrays.asList(permissions));
        all.addAll(extra);
        return all;
    }

    private Permission findOrCreate(String name, String description) {
        Permission p = permissionRepository.findByName(name).orElseGet(() -> {
            Permission newP = new Permission();
            newP.setName(name);
            return newP;
        });
        p.setDescription(description);
        return permissionRepository.save(p);
    }
}