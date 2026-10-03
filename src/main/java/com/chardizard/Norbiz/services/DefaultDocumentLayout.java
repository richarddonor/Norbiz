package com.chardizard.Norbiz.services;

import java.util.List;

/**
 * Role hints for generating a document type's default print template from the standard
 * transaction layout (docs/TRANSACTIONS.md). All field/column names are schema paths from the
 * same DocumentSchemaRegistry entry; labels come from the schema.
 *
 * @param displayName       e.g. "Purchase Order" — used for the template name and (uppercased) the document title
 * @param dateField         the transaction-date header field
 * @param counterpartyLabel e.g. "Supplier:" / "Warehouse:" / "Customer:"
 * @param counterpartyField the counterparty header field
 * @param remarksField      the notes field (labeled "Remarks" in the schema)
 * @param tables            repeating groups to print, top to bottom; the first is the line items
 */
public record DefaultDocumentLayout(
        String displayName,
        String dateField,
        String counterpartyLabel,
        String counterpartyField,
        String remarksField,
        List<Table> tables
) {
    /**
     * @param weight share of the table area this table gets relative to the others
     */
    public record Table(String groupPath, String title, int weight, List<Column> columns) {}

    public record Column(String field, int width) {}
}
