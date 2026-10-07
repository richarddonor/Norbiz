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
            Map.entry("PURCHASE_RECEIVE", new Entry(
                    new DocumentSchemaResponse(
                            "PURCHASE_RECEIVE",
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
                            "Purchase Receive", "receiptDate", "Supplier:", "supplierName", "remarks",
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
            Map.entry("OUTLET_RECEIVE", new Entry(
                    new DocumentSchemaResponse(
                            "OUTLET_RECEIVE",
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
                            "Outlet Receive", "receiptDate", "Outlet:", "customerName", "remarks",
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
                                    new DocumentFieldSchema("pullOutDate", "Pull Out Date", "date"),
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
                            "Outlet Pull Out", "pullOutDate", "Outlet:", "customerName", "remarks",
                            List.of(new DefaultDocumentLayout.Table("lines", "Line Items", 1, List.of(
                                    new DefaultDocumentLayout.Column("itemCode", 120),
                                    new DefaultDocumentLayout.Column("itemName", 300),
                                    new DefaultDocumentLayout.Column("quantity", 90),
                                    new DefaultDocumentLayout.Column("unitPrice", 110),
                                    new DefaultDocumentLayout.Column("amount", 120))))
                    )
            )),
            Map.entry("PULL_OUT_RECEIVE", new Entry(
                    new DocumentSchemaResponse(
                            "PULL_OUT_RECEIVE",
                            List.of(
                                    new DocumentFieldSchema("referenceNumber", "Reference Number", "string"),
                                    new DocumentFieldSchema("sheetNumber", "Sheet Number", "string"),
                                    new DocumentFieldSchema("companyName", "Company", "string"),
                                    new DocumentFieldSchema("customerName", "Outlet", "string"),
                                    new DocumentFieldSchema("warehouseName", "Warehouse", "string"),
                                    new DocumentFieldSchema("outletPullOutReferenceNumber", "Outlet Pull Out #", "string"),
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
                            "Pull Out Receive", "receiptDate", "Outlet:", "customerName", "remarks",
                            List.of(new DefaultDocumentLayout.Table("lines", "Line Items", 1, List.of(
                                    new DefaultDocumentLayout.Column("itemCode", 130),
                                    new DefaultDocumentLayout.Column("itemName", 410),
                                    new DefaultDocumentLayout.Column("quantity", 100))))
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
