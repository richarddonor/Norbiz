package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.dto.DocumentFieldSchema;
import com.chardizard.Norbiz.dto.DocumentRepeatingGroupSchema;
import com.chardizard.Norbiz.dto.DocumentSchemaResponse;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Hand-maintained registry of bindable fields per printable document type, backing
 * GET /document-templates/schema. Deliberately not reflection-based over the response
 * DTOs — keeps explicit control over labels/grouping and which fields are safe to expose
 * to the template designer. Add one entry here per new document type.
 */
@Component
public class DocumentSchemaRegistry {

    private static final Map<String, DocumentSchemaResponse> SCHEMAS = Map.of(
            "INVENTORY_ADJUSTMENT", new DocumentSchemaResponse(
                    "INVENTORY_ADJUSTMENT",
                    List.of(
                            new DocumentFieldSchema("referenceNumber", "Reference Number", "string"),
                            new DocumentFieldSchema("sheetNumber", "Sheet Number", "string"),
                            new DocumentFieldSchema("companyName", "Company", "string"),
                            new DocumentFieldSchema("warehouseName", "Warehouse", "string"),
                            new DocumentFieldSchema("adjustmentDate", "Adjustment Date", "date"),
                            new DocumentFieldSchema("reason", "Remarks", "string"),
                            new DocumentFieldSchema("createdBy", "Posted By", "string")
                    ),
                    List.of(
                            new DocumentRepeatingGroupSchema("lines", "Line Items", List.of(
                                    new DocumentFieldSchema("itemCode", "Item Code", "string"),
                                    new DocumentFieldSchema("itemName", "Item Name", "string"),
                                    new DocumentFieldSchema("quantity", "Quantity", "number")
                            ))
                    )
            )
    );

    public DocumentSchemaResponse getSchema(String documentType) {
        DocumentSchemaResponse schema = SCHEMAS.get(documentType);
        if (schema == null) {
            throw new IllegalArgumentException("Unknown document type: " + documentType);
        }
        return schema;
    }
}
