package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.dto.DocumentFieldSchema;
import com.chardizard.Norbiz.dto.DocumentRepeatingGroupSchema;
import com.chardizard.Norbiz.dto.DocumentSchemaResponse;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Hand-maintained registry of bindable fields per printable document type, backing
 * GET /document-templates/schema. Deliberately not reflection-based over the response
 * DTOs — keeps explicit control over labels/grouping and which fields are safe to expose
 * to the template designer. Add one entry here per new document type — its default
 * template is then generated for every company on startup (DefaultDocumentTemplateProvisioner).
 */
@Component
public class DocumentSchemaRegistry {

    // Every printable document type has exactly one entry: its bindable-field schema plus the
    // role hints DefaultDocumentTemplateFactory needs to generate its default template. Keeping
    // both in one Entry means a new document type can't get a schema without a default printout.
    private static final Map<String, Entry> ENTRIES = Map.ofEntries(
            Map.entry("INVENTORY_ADJUSTMENT", new Entry(
                    new DocumentSchemaResponse(
                            "INVENTORY_ADJUSTMENT",
                            List.of(
                                    new DocumentFieldSchema("referenceNumber", "Reference Number", "string"),
                                    new DocumentFieldSchema("sheetNumber", "Sheet Number", "string"),
                                    new DocumentFieldSchema("companyName", "Company", "string"),
                                    new DocumentFieldSchema("warehouseName", "Warehouse", "string"),
                                    new DocumentFieldSchema("adjustmentDate", "Adjustment Date", "date"),
                                    new DocumentFieldSchema("reason", "Remarks", "string"),
                                    new DocumentFieldSchema("createdBy", "Posted By", "user")
                            ),
                            List.of(
                                    new DocumentRepeatingGroupSchema("lines", "Line Items", List.of(
                                            new DocumentFieldSchema("itemCode", "Item Code", "string"),
                                            new DocumentFieldSchema("itemName", "Item Name", "string"),
                                            new DocumentFieldSchema("quantity", "Quantity", "number")
                                    ))
                            )
                    ),
                    new DefaultDocumentLayout(
                            "Inventory Adjustment", "adjustmentDate", "Warehouse:", "warehouseName", "reason",
                            List.of(new DefaultDocumentLayout.Table("lines", "Line Items", 1, List.of(
                                    new DefaultDocumentLayout.Column("itemCode", 130),
                                    new DefaultDocumentLayout.Column("itemName", 410),
                                    new DefaultDocumentLayout.Column("quantity", 100))))
                    )
            )),
            Map.entry("PURCHASE_ORDER", new Entry(
                    new DocumentSchemaResponse(
                            "PURCHASE_ORDER",
                            List.of(
                                    new DocumentFieldSchema("referenceNumber", "Reference Number", "string"),
                                    new DocumentFieldSchema("sheetNumber", "Sheet Number", "string"),
                                    new DocumentFieldSchema("companyName", "Company", "string"),
                                    new DocumentFieldSchema("warehouseName", "Warehouse", "string"),
                                    new DocumentFieldSchema("supplierName", "Supplier", "string"),
                                    new DocumentFieldSchema("orderDate", "Order Date", "date"),
                                    new DocumentFieldSchema("remarks", "Remarks", "string"),
                                    new DocumentFieldSchema("createdBy", "Posted By", "user")
                            ),
                            List.of(
                                    new DocumentRepeatingGroupSchema("lines", "Line Items", List.of(
                                            new DocumentFieldSchema("itemCode", "Item Code", "string"),
                                            new DocumentFieldSchema("itemName", "Item Name", "string"),
                                            new DocumentFieldSchema("quantity", "Quantity", "number"),
                                            new DocumentFieldSchema("costPrice", "Cost Price", "currency")
                                    ))
                            )
                    ),
                    new DefaultDocumentLayout(
                            "Purchase Order", "orderDate", "Supplier:", "supplierName", "remarks",
                            List.of(new DefaultDocumentLayout.Table("lines", "Line Items", 1, List.of(
                                    new DefaultDocumentLayout.Column("itemCode", 130),
                                    new DefaultDocumentLayout.Column("itemName", 330),
                                    new DefaultDocumentLayout.Column("quantity", 100),
                                    new DefaultDocumentLayout.Column("costPrice", 120))))
                    )
            )),
            Map.entry("PURCHASE_INVOICE", new Entry(
                    new DocumentSchemaResponse(
                            "PURCHASE_INVOICE",
                            List.of(
                                    new DocumentFieldSchema("referenceNumber", "Reference Number", "string"),
                                    new DocumentFieldSchema("sheetNumber", "Sheet Number", "string"),
                                    new DocumentFieldSchema("companyName", "Company", "string"),
                                    new DocumentFieldSchema("warehouseName", "Warehouse", "string"),
                                    new DocumentFieldSchema("supplierName", "Supplier", "string"),
                                    new DocumentFieldSchema("invoiceDate", "Invoice Date", "date"),
                                    new DocumentFieldSchema("remarks", "Remarks", "string"),
                                    new DocumentFieldSchema("paymentStatus", "Payment Status", "string"),
                                    new DocumentFieldSchema("createdBy", "Posted By", "user")
                            ),
                            List.of(
                                    new DocumentRepeatingGroupSchema("lines", "Line Items", List.of(
                                            new DocumentFieldSchema("itemCode", "Item Code", "string"),
                                            new DocumentFieldSchema("itemName", "Item Name", "string"),
                                            new DocumentFieldSchema("quantity", "Quantity", "number"),
                                            new DocumentFieldSchema("costPrice", "Cost Price", "currency")
                                    )),
                                    new DocumentRepeatingGroupSchema("fees", "Additional Fees", List.of(
                                            new DocumentFieldSchema("description", "Description", "string"),
                                            new DocumentFieldSchema("amount", "Amount", "currency")
                                    ))
                            )
                    ),
                    new DefaultDocumentLayout(
                            "Purchase Invoice", "invoiceDate", "Supplier:", "supplierName", "remarks",
                            List.of(
                                    new DefaultDocumentLayout.Table("lines", "Line Items", 3, List.of(
                                            new DefaultDocumentLayout.Column("itemCode", 130),
                                            new DefaultDocumentLayout.Column("itemName", 330),
                                            new DefaultDocumentLayout.Column("quantity", 100),
                                            new DefaultDocumentLayout.Column("costPrice", 120))),
                                    new DefaultDocumentLayout.Table("fees", "Additional Fees", 1, List.of(
                                            new DefaultDocumentLayout.Column("description", 540),
                                            new DefaultDocumentLayout.Column("amount", 120))))
                    )
            )),
            Map.entry("PURCHASE_RECEIPT", new Entry(
                    new DocumentSchemaResponse(
                            "PURCHASE_RECEIPT",
                            List.of(
                                    new DocumentFieldSchema("referenceNumber", "Reference Number", "string"),
                                    new DocumentFieldSchema("sheetNumber", "Sheet Number", "string"),
                                    new DocumentFieldSchema("companyName", "Company", "string"),
                                    new DocumentFieldSchema("warehouseName", "Warehouse", "string"),
                                    new DocumentFieldSchema("supplierName", "Supplier", "string"),
                                    new DocumentFieldSchema("receiptDate", "Receipt Date", "date"),
                                    new DocumentFieldSchema("remarks", "Remarks", "string"),
                                    new DocumentFieldSchema("createdBy", "Posted By", "user")
                            ),
                            List.of(
                                    new DocumentRepeatingGroupSchema("lines", "Line Items", List.of(
                                            new DocumentFieldSchema("itemCode", "Item Code", "string"),
                                            new DocumentFieldSchema("itemName", "Item Name", "string"),
                                            new DocumentFieldSchema("quantity", "Quantity", "number")
                                    ))
                            )
                    ),
                    new DefaultDocumentLayout(
                            "Purchase Receipt", "receiptDate", "Supplier:", "supplierName", "remarks",
                            List.of(new DefaultDocumentLayout.Table("lines", "Line Items", 1, List.of(
                                    new DefaultDocumentLayout.Column("itemCode", 130),
                                    new DefaultDocumentLayout.Column("itemName", 410),
                                    new DefaultDocumentLayout.Column("quantity", 100))))
                    )
            )),
            Map.entry("DELIVERY_RECEIPT", new Entry(
                    new DocumentSchemaResponse(
                            "DELIVERY_RECEIPT",
                            List.of(
                                    new DocumentFieldSchema("referenceNumber", "Reference Number", "string"),
                                    new DocumentFieldSchema("sheetNumber", "Sheet Number", "string"),
                                    new DocumentFieldSchema("companyName", "Company", "string"),
                                    new DocumentFieldSchema("customerName", "Customer", "string"),
                                    new DocumentFieldSchema("stockTransferReferenceNumber", "Stock Transfer #", "string"),
                                    new DocumentFieldSchema("warehouseName", "Warehouse", "string"),
                                    new DocumentFieldSchema("destinationWarehouseName", "Outlet Warehouse", "string"),
                                    new DocumentFieldSchema("deliveryDate", "Delivery Date", "date"),
                                    new DocumentFieldSchema("totalAmount", "Total Amount", "currency"),
                                    new DocumentFieldSchema("remarks", "Remarks", "string"),
                                    new DocumentFieldSchema("createdBy", "Posted By", "user")
                            ),
                            List.of(
                                    new DocumentRepeatingGroupSchema("lines", "Line Items", List.of(
                                            new DocumentFieldSchema("itemCode", "Item Code", "string"),
                                            new DocumentFieldSchema("itemName", "Item Name", "string"),
                                            new DocumentFieldSchema("quantity", "Quantity", "number"),
                                            new DocumentFieldSchema("unitPrice", "Unit Price", "currency"),
                                            new DocumentFieldSchema("amount", "Amount", "currency")
                                    ))
                            )
                    ),
                    new DefaultDocumentLayout(
                            "Delivery Receipt", "deliveryDate", "Customer:", "customerName", "remarks",
                            List.of(new DefaultDocumentLayout.Table("lines", "Line Items", 1, List.of(
                                    new DefaultDocumentLayout.Column("itemCode", 120),
                                    new DefaultDocumentLayout.Column("itemName", 300),
                                    new DefaultDocumentLayout.Column("quantity", 90),
                                    new DefaultDocumentLayout.Column("unitPrice", 110),
                                    new DefaultDocumentLayout.Column("amount", 120))))
                    )
            )),
            Map.entry("OUTLET_RECEIPT", new Entry(
                    new DocumentSchemaResponse(
                            "OUTLET_RECEIPT",
                            List.of(
                                    new DocumentFieldSchema("referenceNumber", "Reference Number", "string"),
                                    new DocumentFieldSchema("sheetNumber", "Sheet Number", "string"),
                                    new DocumentFieldSchema("companyName", "Company", "string"),
                                    new DocumentFieldSchema("customerName", "Outlet", "string"),
                                    new DocumentFieldSchema("warehouseName", "Warehouse", "string"),
                                    new DocumentFieldSchema("deliveryReceiptReferenceNumber", "Delivery Receipt #", "string"),
                                    new DocumentFieldSchema("receiptDate", "Receipt Date", "date"),
                                    new DocumentFieldSchema("remarks", "Remarks", "string"),
                                    new DocumentFieldSchema("createdBy", "Posted By", "user")
                            ),
                            List.of(
                                    new DocumentRepeatingGroupSchema("lines", "Line Items", List.of(
                                            new DocumentFieldSchema("itemCode", "Item Code", "string"),
                                            new DocumentFieldSchema("itemName", "Item Name", "string"),
                                            new DocumentFieldSchema("quantity", "Quantity", "number")
                                    ))
                            )
                    ),
                    new DefaultDocumentLayout(
                            "Outlet Receipt", "receiptDate", "Outlet:", "customerName", "remarks",
                            List.of(new DefaultDocumentLayout.Table("lines", "Line Items", 1, List.of(
                                    new DefaultDocumentLayout.Column("itemCode", 130),
                                    new DefaultDocumentLayout.Column("itemName", 410),
                                    new DefaultDocumentLayout.Column("quantity", 100))))
                    )
            )),
            Map.entry("OUTLET_DELIVERY_RECEIPT", new Entry(
                    new DocumentSchemaResponse(
                            "OUTLET_DELIVERY_RECEIPT",
                            List.of(
                                    new DocumentFieldSchema("referenceNumber", "Reference Number", "string"),
                                    new DocumentFieldSchema("sheetNumber", "Sheet Number", "string"),
                                    new DocumentFieldSchema("companyName", "Company", "string"),
                                    new DocumentFieldSchema("customerName", "Outlet", "string"),
                                    new DocumentFieldSchema("warehouseName", "Warehouse", "string"),
                                    new DocumentFieldSchema("agentCode", "Agent Code", "string"),
                                    new DocumentFieldSchema("agentName", "Agent", "string"),
                                    new DocumentFieldSchema("deliveryDate", "Delivery Date", "date"),
                                    new DocumentFieldSchema("totalAmount", "Total Amount", "currency"),
                                    new DocumentFieldSchema("remarks", "Remarks", "string"),
                                    new DocumentFieldSchema("createdBy", "Posted By", "user")
                            ),
                            List.of(
                                    new DocumentRepeatingGroupSchema("lines", "Line Items", List.of(
                                            new DocumentFieldSchema("itemCode", "Item Code", "string"),
                                            new DocumentFieldSchema("itemName", "Item Name", "string"),
                                            new DocumentFieldSchema("quantity", "Quantity", "number"),
                                            new DocumentFieldSchema("unitPrice", "Unit Price", "currency"),
                                            new DocumentFieldSchema("amount", "Amount", "currency")
                                    ))
                            )
                    ),
                    new DefaultDocumentLayout(
                            "Outlet Delivery Receipt", "deliveryDate", "Outlet:", "customerName", "remarks",
                            List.of(new DefaultDocumentLayout.Table("lines", "Line Items", 1, List.of(
                                    new DefaultDocumentLayout.Column("itemCode", 120),
                                    new DefaultDocumentLayout.Column("itemName", 300),
                                    new DefaultDocumentLayout.Column("quantity", 90),
                                    new DefaultDocumentLayout.Column("unitPrice", 110),
                                    new DefaultDocumentLayout.Column("amount", 120))))
                    )
            )),
            Map.entry("OUTLET_DELIVERY_RETURN", new Entry(
                    new DocumentSchemaResponse(
                            "OUTLET_DELIVERY_RETURN",
                            List.of(
                                    new DocumentFieldSchema("referenceNumber", "Reference Number", "string"),
                                    new DocumentFieldSchema("sheetNumber", "Sheet Number", "string"),
                                    new DocumentFieldSchema("companyName", "Company", "string"),
                                    new DocumentFieldSchema("customerName", "Outlet", "string"),
                                    new DocumentFieldSchema("warehouseName", "Warehouse", "string"),
                                    new DocumentFieldSchema("agentCode", "Agent Code", "string"),
                                    new DocumentFieldSchema("agentName", "Agent", "string"),
                                    new DocumentFieldSchema("outletDeliveryReceiptReferenceNumber", "Outlet Delivery Receipt #", "string"),
                                    new DocumentFieldSchema("returnDate", "Return Date", "date"),
                                    new DocumentFieldSchema("totalAmount", "Total Amount", "currency"),
                                    new DocumentFieldSchema("remarks", "Remarks", "string"),
                                    new DocumentFieldSchema("createdBy", "Posted By", "user")
                            ),
                            List.of(
                                    new DocumentRepeatingGroupSchema("lines", "Line Items", List.of(
                                            new DocumentFieldSchema("itemCode", "Item Code", "string"),
                                            new DocumentFieldSchema("itemName", "Item Name", "string"),
                                            new DocumentFieldSchema("quantity", "Quantity", "number"),
                                            new DocumentFieldSchema("unitPrice", "Unit Price", "currency"),
                                            new DocumentFieldSchema("amount", "Amount", "currency")
                                    ))
                            )
                    ),
                    new DefaultDocumentLayout(
                            "Outlet Delivery Return", "returnDate", "Outlet:", "customerName", "remarks",
                            List.of(new DefaultDocumentLayout.Table("lines", "Line Items", 1, List.of(
                                    new DefaultDocumentLayout.Column("itemCode", 120),
                                    new DefaultDocumentLayout.Column("itemName", 300),
                                    new DefaultDocumentLayout.Column("quantity", 90),
                                    new DefaultDocumentLayout.Column("unitPrice", 110),
                                    new DefaultDocumentLayout.Column("amount", 120))))
                    )
            )),
            Map.entry("STOCK_TRANSFER", new Entry(
                    new DocumentSchemaResponse(
                            "STOCK_TRANSFER",
                            List.of(
                                    new DocumentFieldSchema("referenceNumber", "Reference Number", "string"),
                                    new DocumentFieldSchema("sheetNumber", "Sheet Number", "string"),
                                    new DocumentFieldSchema("companyName", "Company", "string"),
                                    new DocumentFieldSchema("customerName", "Customer", "string"),
                                    new DocumentFieldSchema("warehouseName", "Warehouse", "string"),
                                    new DocumentFieldSchema("transferDate", "Transfer Date", "date"),
                                    new DocumentFieldSchema("totalAmount", "Total Amount", "currency"),
                                    new DocumentFieldSchema("remarks", "Remarks", "string"),
                                    new DocumentFieldSchema("createdBy", "Posted By", "user")
                            ),
                            List.of(
                                    new DocumentRepeatingGroupSchema("lines", "Line Items", List.of(
                                        new DocumentFieldSchema("itemCode", "Item Code", "string"),
                                        new DocumentFieldSchema("itemName", "Item Name", "string"),
                                        new DocumentFieldSchema("quantity", "Quantity", "number"),
                                        new DocumentFieldSchema("unitPrice", "Unit Price", "currency"),
                                        new DocumentFieldSchema("amount", "Amount", "currency")
                                    ))
                            )
                    ),
                    new DefaultDocumentLayout(
                            "Stock Transfer", "transferDate", "Customer:", "customerName", "remarks",
                            List.of(new DefaultDocumentLayout.Table("lines", "Line Items", 1, List.of(
                                    new DefaultDocumentLayout.Column("itemCode", 120),
                                    new DefaultDocumentLayout.Column("itemName", 300),
                                    new DefaultDocumentLayout.Column("quantity", 90),
                                    new DefaultDocumentLayout.Column("unitPrice", 110),
                                    new DefaultDocumentLayout.Column("amount", 120))))
                    )
            )),
            Map.entry("OUTLET_PULL_OUT", new Entry(
                    new DocumentSchemaResponse(
                            "OUTLET_PULL_OUT",
                            List.of(
                                    new DocumentFieldSchema("referenceNumber", "Reference Number", "string"),
                                    new DocumentFieldSchema("sheetNumber", "Sheet Number", "string"),
                                    new DocumentFieldSchema("companyName", "Company", "string"),
                                    new DocumentFieldSchema("customerName", "Outlet", "string"),
                                    new DocumentFieldSchema("warehouseName", "Warehouse", "string"),
                                    new DocumentFieldSchema("destinationWarehouseName", "Destination Warehouse", "string"),
                                    new DocumentFieldSchema("pullOutReasonName", "Reason", "string"),
                                    new DocumentFieldSchema("pullOutDate", "Pull-out Date", "date"),
                                    new DocumentFieldSchema("totalAmount", "Total Amount", "currency"),
                                    new DocumentFieldSchema("remarks", "Remarks", "string"),
                                    new DocumentFieldSchema("createdBy", "Posted By", "user")
                            ),
                            List.of(
                                    new DocumentRepeatingGroupSchema("lines", "Line Items", List.of(
                                        new DocumentFieldSchema("itemCode", "Item Code", "string"),
                                        new DocumentFieldSchema("itemName", "Item Name", "string"),
                                        new DocumentFieldSchema("quantity", "Quantity", "number"),
                                        new DocumentFieldSchema("unitPrice", "Unit Price", "currency"),
                                        new DocumentFieldSchema("amount", "Amount", "currency")
                                    ))
                            )
                    ),
                    new DefaultDocumentLayout(
                            "Outlet Pull-out", "pullOutDate", "Outlet:", "customerName", "remarks",
                            List.of(new DefaultDocumentLayout.Table("lines", "Line Items", 1, List.of(
                                    new DefaultDocumentLayout.Column("itemCode", 120),
                                    new DefaultDocumentLayout.Column("itemName", 300),
                                    new DefaultDocumentLayout.Column("quantity", 90),
                                    new DefaultDocumentLayout.Column("unitPrice", 110),
                                    new DefaultDocumentLayout.Column("amount", 120))))
                    )
            )),
            Map.entry("PULL_OUT_RECEIPT", new Entry(
                    new DocumentSchemaResponse(
                            "PULL_OUT_RECEIPT",
                            List.of(
                                    new DocumentFieldSchema("referenceNumber", "Reference Number", "string"),
                                    new DocumentFieldSchema("sheetNumber", "Sheet Number", "string"),
                                    new DocumentFieldSchema("companyName", "Company", "string"),
                                    new DocumentFieldSchema("customerName", "Outlet", "string"),
                                    new DocumentFieldSchema("warehouseName", "Warehouse", "string"),
                                    new DocumentFieldSchema("outletPullOutReferenceNumber", "Outlet Pull-out #", "string"),
                                    new DocumentFieldSchema("receiptDate", "Receipt Date", "date"),
                                    new DocumentFieldSchema("remarks", "Remarks", "string"),
                                    new DocumentFieldSchema("createdBy", "Posted By", "user")
                            ),
                            List.of(
                                    new DocumentRepeatingGroupSchema("lines", "Line Items", List.of(
                                        new DocumentFieldSchema("itemCode", "Item Code", "string"),
                                        new DocumentFieldSchema("itemName", "Item Name", "string"),
                                        new DocumentFieldSchema("quantity", "Quantity", "number")
                                    ))
                            )
                    ),
                    new DefaultDocumentLayout(
                            "Pull-out Receipt", "receiptDate", "Outlet:", "customerName", "remarks",
                            List.of(new DefaultDocumentLayout.Table("lines", "Line Items", 1, List.of(
                                    new DefaultDocumentLayout.Column("itemCode", 130),
                                    new DefaultDocumentLayout.Column("itemName", 410),
                                    new DefaultDocumentLayout.Column("quantity", 100))))
                    )
            )),
            Map.entry("SALES_INVOICE", new Entry(
                    new DocumentSchemaResponse(
                            "SALES_INVOICE",
                            List.of(
                                    new DocumentFieldSchema("referenceNumber", "Reference Number", "string"),
                                    new DocumentFieldSchema("sheetNumber", "Sheet Number", "string"),
                                    new DocumentFieldSchema("companyName", "Company", "string"),
                                    new DocumentFieldSchema("customerName", "Customer", "string"),
                                    new DocumentFieldSchema("warehouseName", "Warehouse", "string"),
                                    new DocumentFieldSchema("deliveryReceiptReferenceNumber", "Delivery Receipt", "string"),
                                    new DocumentFieldSchema("invoiceDate", "Invoice Date", "date"),
                                    new DocumentFieldSchema("dueDate", "Due Date", "date"),
                                    new DocumentFieldSchema("termDays", "Terms (days)", "number"),
                                    new DocumentFieldSchema("itemsSubtotal", "Subtotal", "currency"),
                                    new DocumentFieldSchema("discountPercentage", "Discount %", "number"),
                                    new DocumentFieldSchema("discountAmount", "Discount", "currency"),
                                    new DocumentFieldSchema("netAmount", "Net Amount", "currency"),
                                    new DocumentFieldSchema("balance", "Balance", "currency"),
                                    new DocumentFieldSchema("paymentStatus", "Payment Status", "string"),
                                    new DocumentFieldSchema("remarks", "Remarks", "string"),
                                    new DocumentFieldSchema("createdBy", "Posted By", "user")
                            ),
                            List.of(
                                    new DocumentRepeatingGroupSchema("lines", "Line Items", List.of(
                                            new DocumentFieldSchema("itemCode", "Item Code", "string"),
                                            new DocumentFieldSchema("itemName", "Item Name", "string"),
                                            new DocumentFieldSchema("quantity", "Quantity", "number"),
                                            new DocumentFieldSchema("unitPrice", "Unit Price", "currency"),
                                            new DocumentFieldSchema("discountPercentage", "Discount %", "number"),
                                            new DocumentFieldSchema("lineNet", "Amount", "currency")
                                    ))
                            )
                    ),
                    new DefaultDocumentLayout(
                            "Sales Invoice", "invoiceDate", "Customer:", "customerName", "remarks",
                            List.of(
                                    new DefaultDocumentLayout.Table("lines", "Line Items", 3, List.of(
                                            new DefaultDocumentLayout.Column("itemCode", 110),
                                            new DefaultDocumentLayout.Column("itemName", 280),
                                            new DefaultDocumentLayout.Column("quantity", 80),
                                            new DefaultDocumentLayout.Column("unitPrice", 100),
                                            new DefaultDocumentLayout.Column("discountPercentage", 70),
                                            new DefaultDocumentLayout.Column("lineNet", 110))))
                    )
            )),
            Map.entry("CUSTOMER_MEMO", new Entry(
                    new DocumentSchemaResponse(
                            "CUSTOMER_MEMO",
                            List.of(
                                    new DocumentFieldSchema("referenceNumber", "Reference Number", "string"),
                                    new DocumentFieldSchema("sheetNumber", "Sheet Number", "string"),
                                    new DocumentFieldSchema("companyName", "Company", "string"),
                                    new DocumentFieldSchema("customerName", "Customer", "string"),
                                    new DocumentFieldSchema("memoType", "Memo Type", "string"),
                                    new DocumentFieldSchema("reason", "Reason", "string"),
                                    new DocumentFieldSchema("memoDate", "Memo Date", "date"),
                                    new DocumentFieldSchema("dueDate", "Due Date", "date"),
                                    new DocumentFieldSchema("originalReference", "Original Reference", "string"),
                                    new DocumentFieldSchema("amount", "Amount", "currency"),
                                    new DocumentFieldSchema("remarks", "Remarks", "string"),
                                    new DocumentFieldSchema("createdBy", "Posted By", "user")
                            ),
                            List.of(
                                    new DocumentRepeatingGroupSchema("lines", "Lines", List.of(
                                            new DocumentFieldSchema("description", "Description", "string"),
                                            new DocumentFieldSchema("amount", "Amount", "currency")
                                    )),
                                    new DocumentRepeatingGroupSchema("applications", "Applied To", List.of(
                                            new DocumentFieldSchema("chargeReferenceNumber", "Applied To", "string"),
                                            new DocumentFieldSchema("amount", "Amount", "currency")
                                    ))
                            )
                    ),
                    new DefaultDocumentLayout(
                            "Customer Memo", "memoDate", "Customer:", "customerName", "remarks",
                            List.of(
                                    new DefaultDocumentLayout.Table("lines", "Lines", 3, List.of(
                                            new DefaultDocumentLayout.Column("description", 590),
                                            new DefaultDocumentLayout.Column("amount", 130))),
                                    new DefaultDocumentLayout.Table("applications", "Applied To", 1, List.of(
                                            new DefaultDocumentLayout.Column("chargeReferenceNumber", 590),
                                            new DefaultDocumentLayout.Column("amount", 130))))
                    )
            )),
            Map.entry("SUPPLIER_MEMO", new Entry(
                    new DocumentSchemaResponse(
                            "SUPPLIER_MEMO",
                            List.of(
                                    new DocumentFieldSchema("referenceNumber", "Reference Number", "string"),
                                    new DocumentFieldSchema("sheetNumber", "Sheet Number", "string"),
                                    new DocumentFieldSchema("companyName", "Company", "string"),
                                    new DocumentFieldSchema("supplierName", "Supplier", "string"),
                                    new DocumentFieldSchema("memoType", "Memo Type", "string"),
                                    new DocumentFieldSchema("reason", "Reason", "string"),
                                    new DocumentFieldSchema("memoDate", "Memo Date", "date"),
                                    new DocumentFieldSchema("dueDate", "Due Date", "date"),
                                    new DocumentFieldSchema("originalReference", "Original Reference", "string"),
                                    new DocumentFieldSchema("amount", "Amount", "currency"),
                                    new DocumentFieldSchema("remarks", "Remarks", "string"),
                                    new DocumentFieldSchema("createdBy", "Posted By", "user")
                            ),
                            List.of(
                                    new DocumentRepeatingGroupSchema("lines", "Lines", List.of(
                                            new DocumentFieldSchema("description", "Description", "string"),
                                            new DocumentFieldSchema("amount", "Amount", "currency")
                                    )),
                                    new DocumentRepeatingGroupSchema("applications", "Applied To", List.of(
                                            new DocumentFieldSchema("chargeReferenceNumber", "Applied To", "string"),
                                            new DocumentFieldSchema("amount", "Amount", "currency")
                                    ))
                            )
                    ),
                    new DefaultDocumentLayout(
                            "Supplier Memo", "memoDate", "Supplier:", "supplierName", "remarks",
                            List.of(
                                    new DefaultDocumentLayout.Table("lines", "Lines", 3, List.of(
                                            new DefaultDocumentLayout.Column("description", 590),
                                            new DefaultDocumentLayout.Column("amount", 130))),
                                    new DefaultDocumentLayout.Table("applications", "Applied To", 1, List.of(
                                            new DefaultDocumentLayout.Column("chargeReferenceNumber", 590),
                                            new DefaultDocumentLayout.Column("amount", 130))))
                    )
            )),
            Map.entry("CUSTOMER_PAYMENT", new Entry(
                    new DocumentSchemaResponse(
                            "CUSTOMER_PAYMENT",
                            List.of(
                                    new DocumentFieldSchema("referenceNumber", "Reference Number", "string"),
                                    new DocumentFieldSchema("sheetNumber", "Sheet Number", "string"),
                                    new DocumentFieldSchema("companyName", "Company", "string"),
                                    new DocumentFieldSchema("customerName", "Customer", "string"),
                                    new DocumentFieldSchema("paymentDate", "Payment Date", "date"),
                                    new DocumentFieldSchema("totalAmount", "Total Amount", "currency"),
                                    new DocumentFieldSchema("appliedAmount", "Applied", "currency"),
                                    new DocumentFieldSchema("unappliedAmount", "Unapplied (Advance)", "currency"),
                                    new DocumentFieldSchema("creditsUsedAmount", "Credits Used", "currency"),
                                    new DocumentFieldSchema("remarks", "Remarks", "string"),
                                    new DocumentFieldSchema("createdBy", "Posted By", "user")
                            ),
                            List.of(
                                    new DocumentRepeatingGroupSchema("tenders", "Payment", List.of(
                                            new DocumentFieldSchema("mode", "Mode", "string"),
                                            new DocumentFieldSchema("checkNumber", "Check / Ref No.", "string"),
                                            new DocumentFieldSchema("bankName", "Bank", "string"),
                                            new DocumentFieldSchema("checkDate", "Check Date", "date"),
                                            new DocumentFieldSchema("amount", "Amount", "currency")
                                    )),
                                    new DocumentRepeatingGroupSchema("applications", "Applied To", List.of(
                                            new DocumentFieldSchema("chargeReferenceNumber", "Applied To", "string"),
                                            new DocumentFieldSchema("amount", "Amount", "currency")
                                    )),
                                    new DocumentRepeatingGroupSchema("paidDocuments", "Paid", List.of(
                                            new DocumentFieldSchema("documentType", "Document Type", "string"),
                                            new DocumentFieldSchema("referenceNumber", "Reference No.", "string"),
                                            new DocumentFieldSchema("paidAmount", "Paid by Payment", "currency"),
                                            new DocumentFieldSchema("creditAmount", "Paid by Credits", "currency"),
                                            new DocumentFieldSchema("amount", "Total Paid", "currency")
                                    )),
                                    new DocumentRepeatingGroupSchema("creditsUsed", "Credits Used", List.of(
                                            new DocumentFieldSchema("documentType", "Document Type", "string"),
                                            new DocumentFieldSchema("referenceNumber", "Reference No.", "string"),
                                            new DocumentFieldSchema("amount", "Amount", "currency")
                                    ))
                            )
                    ),
                    new DefaultDocumentLayout(
                            "Customer Payment", "paymentDate", "Customer:", "customerName", "remarks",
                            List.of(
                                    new DefaultDocumentLayout.Table("tenders", "Payment", 2, List.of(
                                            new DefaultDocumentLayout.Column("mode", 110),
                                            new DefaultDocumentLayout.Column("checkNumber", 200),
                                            new DefaultDocumentLayout.Column("bankName", 200),
                                            new DefaultDocumentLayout.Column("checkDate", 100),
                                            new DefaultDocumentLayout.Column("amount", 120))),
                                    new DefaultDocumentLayout.Table("paidDocuments", "Paid", 2, List.of(
                                            new DefaultDocumentLayout.Column("documentType", 160),
                                            new DefaultDocumentLayout.Column("referenceNumber", 200),
                                            new DefaultDocumentLayout.Column("paidAmount", 120),
                                            new DefaultDocumentLayout.Column("creditAmount", 120),
                                            new DefaultDocumentLayout.Column("amount", 120))),
                                    new DefaultDocumentLayout.Table("creditsUsed", "Credits Used", 1, List.of(
                                            new DefaultDocumentLayout.Column("documentType", 200),
                                            new DefaultDocumentLayout.Column("referenceNumber", 390),
                                            new DefaultDocumentLayout.Column("amount", 130))))
                    )
            )),
            Map.entry("SUPPLIER_PAYMENT", new Entry(
                    new DocumentSchemaResponse(
                            "SUPPLIER_PAYMENT",
                            List.of(
                                    new DocumentFieldSchema("referenceNumber", "Reference Number", "string"),
                                    new DocumentFieldSchema("sheetNumber", "Sheet Number", "string"),
                                    new DocumentFieldSchema("companyName", "Company", "string"),
                                    new DocumentFieldSchema("supplierName", "Supplier", "string"),
                                    new DocumentFieldSchema("paymentDate", "Payment Date", "date"),
                                    new DocumentFieldSchema("voucherNumber", "Voucher Number", "string"),
                                    new DocumentFieldSchema("totalAmount", "Total Amount", "currency"),
                                    new DocumentFieldSchema("appliedAmount", "Applied", "currency"),
                                    new DocumentFieldSchema("unappliedAmount", "Unapplied (Advance)", "currency"),
                                    new DocumentFieldSchema("creditsUsedAmount", "Credits Used", "currency"),
                                    new DocumentFieldSchema("remarks", "Remarks", "string"),
                                    new DocumentFieldSchema("createdBy", "Posted By", "user")
                            ),
                            List.of(
                                    new DocumentRepeatingGroupSchema("tenders", "Payment", List.of(
                                            new DocumentFieldSchema("mode", "Mode", "string"),
                                            new DocumentFieldSchema("checkNumber", "Check / Ref No.", "string"),
                                            new DocumentFieldSchema("bankAccountName", "Bank Account", "string"),
                                            new DocumentFieldSchema("checkDate", "Check Date", "date"),
                                            new DocumentFieldSchema("amount", "Amount", "currency")
                                    )),
                                    new DocumentRepeatingGroupSchema("applications", "Applied To", List.of(
                                            new DocumentFieldSchema("chargeReferenceNumber", "Applied To", "string"),
                                            new DocumentFieldSchema("amount", "Amount", "currency")
                                    )),
                                    new DocumentRepeatingGroupSchema("paidDocuments", "Paid", List.of(
                                            new DocumentFieldSchema("documentType", "Document Type", "string"),
                                            new DocumentFieldSchema("referenceNumber", "Reference No.", "string"),
                                            new DocumentFieldSchema("paidAmount", "Paid by Payment", "currency"),
                                            new DocumentFieldSchema("creditAmount", "Paid by Credits", "currency"),
                                            new DocumentFieldSchema("amount", "Total Paid", "currency")
                                    )),
                                    new DocumentRepeatingGroupSchema("creditsUsed", "Credits Used", List.of(
                                            new DocumentFieldSchema("documentType", "Document Type", "string"),
                                            new DocumentFieldSchema("referenceNumber", "Reference No.", "string"),
                                            new DocumentFieldSchema("amount", "Amount", "currency")
                                    ))
                            )
                    ),
                    new DefaultDocumentLayout(
                            "Supplier Payment", "paymentDate", "Supplier:", "supplierName", "remarks",
                            List.of(
                                    new DefaultDocumentLayout.Table("tenders", "Payment", 2, List.of(
                                            new DefaultDocumentLayout.Column("mode", 110),
                                            new DefaultDocumentLayout.Column("checkNumber", 200),
                                            new DefaultDocumentLayout.Column("bankAccountName", 200),
                                            new DefaultDocumentLayout.Column("checkDate", 100),
                                            new DefaultDocumentLayout.Column("amount", 120))),
                                    new DefaultDocumentLayout.Table("paidDocuments", "Paid", 2, List.of(
                                            new DefaultDocumentLayout.Column("documentType", 160),
                                            new DefaultDocumentLayout.Column("referenceNumber", 200),
                                            new DefaultDocumentLayout.Column("paidAmount", 120),
                                            new DefaultDocumentLayout.Column("creditAmount", 120),
                                            new DefaultDocumentLayout.Column("amount", 120))),
                                    new DefaultDocumentLayout.Table("creditsUsed", "Credits Used", 1, List.of(
                                            new DefaultDocumentLayout.Column("documentType", 200),
                                            new DefaultDocumentLayout.Column("referenceNumber", 390),
                                            new DefaultDocumentLayout.Column("amount", 130))))
                    )
            )),
            Map.entry("CUSTOMER_REFUND", new Entry(
                    new DocumentSchemaResponse(
                            "CUSTOMER_REFUND",
                            List.of(
                                    new DocumentFieldSchema("referenceNumber", "Reference Number", "string"),
                                    new DocumentFieldSchema("sheetNumber", "Sheet Number", "string"),
                                    new DocumentFieldSchema("companyName", "Company", "string"),
                                    new DocumentFieldSchema("customerName", "Customer", "string"),
                                    new DocumentFieldSchema("refundDate", "Refund Date", "date"),
                                    new DocumentFieldSchema("mode", "Mode", "string"),
                                    new DocumentFieldSchema("bankAccountName", "Bank Account", "string"),
                                    new DocumentFieldSchema("checkNumber", "Check / Ref No.", "string"),
                                    new DocumentFieldSchema("checkDate", "Check Date", "date"),
                                    new DocumentFieldSchema("amount", "Amount", "currency"),
                                    new DocumentFieldSchema("remarks", "Remarks", "string"),
                                    new DocumentFieldSchema("createdBy", "Posted By", "user")
                            ),
                            List.of(
                                    new DocumentRepeatingGroupSchema("lines", "Refunded Credits", List.of(
                                            new DocumentFieldSchema("creditReferenceNumber", "Credit", "string"),
                                            new DocumentFieldSchema("amount", "Amount", "currency")
                                    ))
                            )
                    ),
                    new DefaultDocumentLayout(
                            "Customer Refund", "refundDate", "Customer:", "customerName", "remarks",
                            List.of(
                                    new DefaultDocumentLayout.Table("lines", "Refunded Credits", 1, List.of(
                                            new DefaultDocumentLayout.Column("creditReferenceNumber", 590),
                                            new DefaultDocumentLayout.Column("amount", 130))))
                    )
            )),
            Map.entry("SUPPLIER_REFUND", new Entry(
                    new DocumentSchemaResponse(
                            "SUPPLIER_REFUND",
                            List.of(
                                    new DocumentFieldSchema("referenceNumber", "Reference Number", "string"),
                                    new DocumentFieldSchema("sheetNumber", "Sheet Number", "string"),
                                    new DocumentFieldSchema("companyName", "Company", "string"),
                                    new DocumentFieldSchema("supplierName", "Supplier", "string"),
                                    new DocumentFieldSchema("refundDate", "Refund Date", "date"),
                                    new DocumentFieldSchema("mode", "Mode", "string"),
                                    new DocumentFieldSchema("bankAccountName", "Bank Account", "string"),
                                    new DocumentFieldSchema("checkNumber", "Check / Ref No.", "string"),
                                    new DocumentFieldSchema("checkDate", "Check Date", "date"),
                                    new DocumentFieldSchema("amount", "Amount", "currency"),
                                    new DocumentFieldSchema("remarks", "Remarks", "string"),
                                    new DocumentFieldSchema("createdBy", "Posted By", "user")
                            ),
                            List.of(
                                    new DocumentRepeatingGroupSchema("lines", "Refunded Credits", List.of(
                                            new DocumentFieldSchema("creditReferenceNumber", "Credit", "string"),
                                            new DocumentFieldSchema("amount", "Amount", "currency")
                                    ))
                            )
                    ),
                    new DefaultDocumentLayout(
                            "Supplier Refund", "refundDate", "Supplier:", "supplierName", "remarks",
                            List.of(
                                    new DefaultDocumentLayout.Table("lines", "Refunded Credits", 1, List.of(
                                            new DefaultDocumentLayout.Column("creditReferenceNumber", 590),
                                            new DefaultDocumentLayout.Column("amount", 130))))
                    )
            )),
            Map.entry("DEPOSIT", new Entry(
                    new DocumentSchemaResponse(
                            "DEPOSIT",
                            List.of(
                                    new DocumentFieldSchema("referenceNumber", "Reference Number", "string"),
                                    new DocumentFieldSchema("sheetNumber", "Deposit Slip No.", "string"),
                                    new DocumentFieldSchema("companyName", "Company", "string"),
                                    new DocumentFieldSchema("bankAccountName", "Bank Account", "string"),
                                    new DocumentFieldSchema("bankAccountNumber", "Account Number", "string"),
                                    new DocumentFieldSchema("depositDate", "Deposit Date", "date"),
                                    new DocumentFieldSchema("totalAmount", "Total Amount", "currency"),
                                    new DocumentFieldSchema("remarks", "Remarks", "string"),
                                    new DocumentFieldSchema("createdBy", "Posted By", "user")
                            ),
                            List.of(
                                    new DocumentRepeatingGroupSchema("lines", "Deposited Items", List.of(
                                            new DocumentFieldSchema("customerName", "Customer", "string"),
                                            new DocumentFieldSchema("paymentReferenceNumber", "Payment", "string"),
                                            new DocumentFieldSchema("mode", "Mode", "string"),
                                            new DocumentFieldSchema("checkNumber", "Check No.", "string"),
                                            new DocumentFieldSchema("bankName", "Bank", "string"),
                                            new DocumentFieldSchema("checkDate", "Check Date", "date"),
                                            new DocumentFieldSchema("amount", "Amount", "currency")
                                    ))
                            )
                    ),
                    new DefaultDocumentLayout(
                            "Deposit", "depositDate", "Bank Account:", "bankAccountName", "remarks",
                            List.of(
                                    new DefaultDocumentLayout.Table("lines", "Deposited Items", 1, List.of(
                                            new DefaultDocumentLayout.Column("customerName", 170),
                                            new DefaultDocumentLayout.Column("paymentReferenceNumber", 110),
                                            new DefaultDocumentLayout.Column("mode", 70),
                                            new DefaultDocumentLayout.Column("checkNumber", 100),
                                            new DefaultDocumentLayout.Column("bankName", 110),
                                            new DefaultDocumentLayout.Column("checkDate", 80),
                                            new DefaultDocumentLayout.Column("amount", 100))))
                    )
            )),
            Map.entry("BANK_RECONCILIATION", new Entry(
                    new DocumentSchemaResponse(
                            "BANK_RECONCILIATION",
                            List.of(
                                    new DocumentFieldSchema("referenceNumber", "Reference Number", "string"),
                                    new DocumentFieldSchema("sheetNumber", "Sheet Number", "string"),
                                    new DocumentFieldSchema("companyName", "Company", "string"),
                                    new DocumentFieldSchema("bankAccountName", "Bank Account", "string"),
                                    new DocumentFieldSchema("bankAccountNumber", "Account Number", "string"),
                                    new DocumentFieldSchema("statementDate", "Statement Date", "date"),
                                    new DocumentFieldSchema("previousBalance", "Previous Balance", "currency"),
                                    new DocumentFieldSchema("clearedTotal", "Cleared", "currency"),
                                    new DocumentFieldSchema("adjustmentsTotal", "Adjustments", "currency"),
                                    new DocumentFieldSchema("statementEndingBalance", "Statement Ending Balance", "currency"),
                                    new DocumentFieldSchema("bookBalance", "Book Balance", "currency"),
                                    new DocumentFieldSchema("outstandingTotal", "Outstanding", "currency"),
                                    new DocumentFieldSchema("remarks", "Remarks", "string"),
                                    new DocumentFieldSchema("createdBy", "Posted By", "user")
                            ),
                            List.of(
                                    new DocumentRepeatingGroupSchema("lines", "Cleared Items and Adjustments", List.of(
                                            new DocumentFieldSchema("entryDate", "Date", "date"),
                                            new DocumentFieldSchema("referenceNumber", "Reference", "string"),
                                            new DocumentFieldSchema("checkNumber", "Check No.", "string"),
                                            new DocumentFieldSchema("description", "Description", "string"),
                                            new DocumentFieldSchema("amount", "Amount", "currency")
                                    ))
                            )
                    ),
                    new DefaultDocumentLayout(
                            "Bank Reconciliation", "statementDate", "Bank Account:", "bankAccountName", "remarks",
                            List.of(
                                    new DefaultDocumentLayout.Table("lines", "Cleared Items and Adjustments", 1, List.of(
                                            new DefaultDocumentLayout.Column("entryDate", 90),
                                            new DefaultDocumentLayout.Column("referenceNumber", 130),
                                            new DefaultDocumentLayout.Column("checkNumber", 100),
                                            new DefaultDocumentLayout.Column("description", 280),
                                            new DefaultDocumentLayout.Column("amount", 120))))
                    )
            )),
            Map.entry("STATEMENT_OF_ACCOUNT", new Entry(
                    new DocumentSchemaResponse(
                            "STATEMENT_OF_ACCOUNT",
                            List.of(
                                    new DocumentFieldSchema("referenceNumber", "Reference Number", "string"),
                                    new DocumentFieldSchema("sheetNumber", "Sheet Number", "string"),
                                    new DocumentFieldSchema("companyName", "Company", "string"),
                                    new DocumentFieldSchema("customerName", "Customer", "string"),
                                    new DocumentFieldSchema("statementDate", "Statement Date", "date"),
                                    new DocumentFieldSchema("currentAmount", "Current", "currency"),
                                    new DocumentFieldSchema("days1To30", "1-30 Days", "currency"),
                                    new DocumentFieldSchema("days31To60", "31-60 Days", "currency"),
                                    new DocumentFieldSchema("days61To90", "61-90 Days", "currency"),
                                    new DocumentFieldSchema("days91To120", "91-120 Days", "currency"),
                                    new DocumentFieldSchema("over120", "Over 120 Days", "currency"),
                                    new DocumentFieldSchema("unappliedCredits", "Unapplied Credits", "currency"),
                                    new DocumentFieldSchema("totalBalance", "Total Balance", "currency"),
                                    new DocumentFieldSchema("remarks", "Remarks", "string"),
                                    new DocumentFieldSchema("createdBy", "Posted By", "user")
                            ),
                            List.of(
                                    new DocumentRepeatingGroupSchema("lines", "Open Items", List.of(
                                            new DocumentFieldSchema("documentDate", "Date", "date"),
                                            new DocumentFieldSchema("referenceNumber", "Reference", "string"),
                                            new DocumentFieldSchema("dueDate", "Due Date", "date"),
                                            new DocumentFieldSchema("amount", "Amount", "currency"),
                                            new DocumentFieldSchema("paidAmount", "Paid", "currency"),
                                            new DocumentFieldSchema("balance", "Balance", "currency"),
                                            new DocumentFieldSchema("daysOverdue", "Days Overdue", "number")
                                    ))
                            )
                    ),
                    new DefaultDocumentLayout(
                            "Statement of Account", "statementDate", "Customer:", "customerName", "remarks",
                            List.of(
                                    new DefaultDocumentLayout.Table("lines", "Open Items", 1, List.of(
                                            new DefaultDocumentLayout.Column("documentDate", 80),
                                            new DefaultDocumentLayout.Column("referenceNumber", 170),
                                            new DefaultDocumentLayout.Column("dueDate", 80),
                                            new DefaultDocumentLayout.Column("amount", 100),
                                            new DefaultDocumentLayout.Column("paidAmount", 100),
                                            new DefaultDocumentLayout.Column("balance", 100),
                                            new DefaultDocumentLayout.Column("daysOverdue", 90))))
                    )
            )),
            Map.entry("ASSEMBLY", new Entry(
                    new DocumentSchemaResponse(
                            "ASSEMBLY",
                            List.of(
                                    new DocumentFieldSchema("referenceNumber", "Reference Number", "string"),
                                    new DocumentFieldSchema("sheetNumber", "Sheet Number", "string"),
                                    new DocumentFieldSchema("companyName", "Company", "string"),
                                    new DocumentFieldSchema("warehouseName", "Warehouse", "string"),
                                    new DocumentFieldSchema("assemblyDate", "Assembly Date", "date"),
                                    new DocumentFieldSchema("remarks", "Remarks", "string"),
                                    new DocumentFieldSchema("createdBy", "Posted By", "user")
                            ),
                            List.of(
                                    new DocumentRepeatingGroupSchema("outputs", "Outputs", List.of(
                                        new DocumentFieldSchema("itemCode", "Item Code", "string"),
                                        new DocumentFieldSchema("itemName", "Item Name", "string"),
                                        new DocumentFieldSchema("quantity", "Quantity", "number"),
                                        new DocumentFieldSchema("billOfMaterialCode", "Bill of Materials", "string")
                                    )),
                                    new DocumentRepeatingGroupSchema("materials", "Raw Materials", List.of(
                                        new DocumentFieldSchema("itemCode", "Item Code", "string"),
                                        new DocumentFieldSchema("itemName", "Item Name", "string"),
                                        new DocumentFieldSchema("quantity", "Quantity", "number")
                                    ))
                            )
                    ),
                    new DefaultDocumentLayout(
                            "Assembly", "assemblyDate", "Warehouse:", "warehouseName", "remarks",
                            List.of(new DefaultDocumentLayout.Table("outputs", "Outputs", 1, List.of(
                                    new DefaultDocumentLayout.Column("itemCode", 120),
                                    new DefaultDocumentLayout.Column("itemName", 320),
                                    new DefaultDocumentLayout.Column("quantity", 90),
                                    new DefaultDocumentLayout.Column("billOfMaterialCode", 110))),
                                    new DefaultDocumentLayout.Table("materials", "Raw Materials", 1, List.of(
                                    new DefaultDocumentLayout.Column("itemCode", 130),
                                    new DefaultDocumentLayout.Column("itemName", 410),
                                    new DefaultDocumentLayout.Column("quantity", 100))))
                    )
            ))
    );

    public record Entry(DocumentSchemaResponse schema, DefaultDocumentLayout defaultLayout) {}

    public DocumentSchemaResponse getSchema(String documentType) {
        return getEntry(documentType).schema();
    }

    public Entry getEntry(String documentType) {
        Entry entry = ENTRIES.get(documentType);
        if (entry == null) {
            throw new IllegalArgumentException("Unknown document type: " + documentType);
        }
        return entry;
    }

    public Set<String> documentTypes() {
        return ENTRIES.keySet();
    }
}
