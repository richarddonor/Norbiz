package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.dto.DocumentFieldSchema;
import com.chardizard.Norbiz.dto.DocumentRepeatingGroupSchema;
import com.chardizard.Norbiz.dto.DocumentSchemaResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the layout JSON of a document type's default template from the canonical
 * "Standard transaction document layout" (docs/TRANSACTIONS.md), re-bound to that type's
 * schema paths via its DefaultDocumentLayout hints. With a single table the output matches
 * the canonical coordinates exactly; extra tables (e.g. Purchase Invoice fees) split the
 * table area by weight.
 */
@Component
@RequiredArgsConstructor
public class DefaultDocumentTemplateFactory {

    private static final int LEFT = 20;
    private static final int CONTENT_WIDTH = 754;
    private static final int TABLES_TOP = 200;
    private static final int TABLES_BOTTOM = 1040;
    private static final int SECTION_GAP = 12;
    private static final String ROW_SEPARATOR_COLOR = "#E5E7EB";

    private final DocumentSchemaRegistry schemaRegistry;
    private final ObjectMapper objectMapper;

    public String templateName(String documentType) {
        return schemaRegistry.getEntry(documentType).defaultLayout().displayName() + " - Default";
    }

    public String buildLayoutJson(String documentType) {
        DocumentSchemaRegistry.Entry entry = schemaRegistry.getEntry(documentType);
        return objectMapper.writeValueAsString(buildLayout(entry.schema(), entry.defaultLayout()));
    }

    private Map<String, Object> buildLayout(DocumentSchemaResponse schema, DefaultDocumentLayout hints) {
        List<Map<String, Object>> elements = new ArrayList<>();

        elements.add(text("company-name", 20, 20, 400, 28, field(schema, "companyName"), style(18, true, null)));
        elements.add(label("doc-title", 20, 52, 400, 20, hints.displayName().toUpperCase(), style(12, true, null)));
        elements.add(line("sep-1", 85));

        elements.add(label("ref-label", 20, 100, 90, 18, "Reference #:", bold()));
        elements.add(text("ref-value", 115, 100, 150, 18, field(schema, "referenceNumber"), null));
        elements.add(label("sheet-label", 290, 100, 70, 18, "Sheet #:", bold()));
        elements.add(text("sheet-value", 365, 100, 150, 18, field(schema, "sheetNumber"), null));
        elements.add(label("date-label", 540, 100, 50, 18, "Date:", bold()));
        elements.add(text("date-value", 595, 100, 150, 18, field(schema, hints.dateField()), null));

        elements.add(label("counterparty-label", 20, 128, 90, 18, hints.counterpartyLabel(), bold()));
        elements.add(text("counterparty-value", 115, 128, 400, 18, field(schema, hints.counterpartyField()), null));

        elements.add(label("remarks-label", 20, 156, 90, 18, "Remarks:", bold()));
        elements.add(text("remarks-value", 115, 156, 659, 18, field(schema, hints.remarksField()), null));

        elements.add(line("sep-2", 190));

        int totalWeight = hints.tables().stream().mapToInt(DefaultDocumentLayout.Table::weight).sum();
        int available = TABLES_BOTTOM - TABLES_TOP - SECTION_GAP * (hints.tables().size() - 1);
        int y = TABLES_TOP;
        for (int i = 0; i < hints.tables().size(); i++) {
            DefaultDocumentLayout.Table table = hints.tables().get(i);
            // First table keeps the canonical "lines-*" ids; later ones are suffixed by group path.
            String prefix = i == 0 ? "lines" : table.groupPath();
            int sectionHeight = available * table.weight() / totalWeight;
            addTableSection(elements, schema, table, prefix, y, sectionHeight);
            y += sectionHeight + SECTION_GAP;
        }

        elements.add(line("sep-4", 1050));
        elements.add(label("posted-label", 20, 1060, 80, 18, "Posted by:", style(10, false, null)));
        elements.add(text("posted-value", 105, 1060, 300, 18, field(schema, "createdBy"), style(10, false, null)));

        Map<String, Object> layout = new LinkedHashMap<>();
        layout.put("pageSize", "A4");
        layout.put("orientation", "portrait");
        layout.put("elements", elements);
        return layout;
    }

    private void addTableSection(List<Map<String, Object>> elements, DocumentSchemaResponse schema,
                                 DefaultDocumentLayout.Table table, String prefix, int top, int height) {
        DocumentRepeatingGroupSchema group = schema.getRepeatingGroups().stream()
                .filter(g -> g.getPath().equals(table.groupPath()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        schema.getDocumentType() + " has no repeating group '" + table.groupPath() + "'"));

        elements.add(label(prefix + "-title", LEFT, top, 200, 18, table.title(), bold()));

        List<Map<String, Object>> columns = new ArrayList<>();
        int x = LEFT;
        for (int c = 0; c < table.columns().size(); c++) {
            DefaultDocumentLayout.Column column = table.columns().get(c);
            DocumentFieldSchema f = groupField(group, column.field());
            boolean numeric = "number".equals(f.getType()) || "currency".equals(f.getType());
            elements.add(label(prefix + "-header-col" + (c + 1), x, top + 24, column.width() - 10, 18, f.getLabel(),
                    style(11, true, numeric ? "right" : null)));

            Map<String, Object> col = new LinkedHashMap<>();
            col.put("binding", f.getPath());
            col.put("label", f.getLabel());
            col.put("width", column.width());
            columns.add(col);
            x += column.width();
        }
        if (x - LEFT > CONTENT_WIDTH) {
            throw new IllegalStateException(schema.getDocumentType() + "/" + table.groupPath()
                    + " columns are wider than the page content area");
        }

        elements.add(line(prefix.equals("lines") ? "sep-3" : prefix + "-sep", top + 46));

        Map<String, Object> el = element(prefix + "-table", "table", LEFT, top + 52, CONTENT_WIDTH, height - 60);
        el.put("binding", group.getPath());
        el.put("columns", columns);
        // On a table, borderWidth/borderColor draw a separator under each row.
        Map<String, Object> tableStyle = style(11, false, null);
        tableStyle.put("borderWidth", 1);
        tableStyle.put("borderColor", ROW_SEPARATOR_COLOR);
        el.put("style", tableStyle);
        elements.add(el);
    }

    // Fails fast if a hint names a field the schema doesn't expose, so a typo can't ship a
    // template bound to nothing.
    private static String field(DocumentSchemaResponse schema, String path) {
        return schema.getFields().stream()
                .map(DocumentFieldSchema::getPath)
                .filter(path::equals)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        schema.getDocumentType() + " schema has no field '" + path + "'"));
    }

    private static DocumentFieldSchema groupField(DocumentRepeatingGroupSchema group, String path) {
        return group.getFields().stream()
                .filter(f -> f.getPath().equals(path))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Repeating group '" + group.getPath() + "' has no field '" + path + "'"));
    }

    private static Map<String, Object> element(String id, String type, int x, int y, int width, int height) {
        Map<String, Object> el = new LinkedHashMap<>();
        el.put("id", id);
        el.put("type", type);
        el.put("x", x);
        el.put("y", y);
        el.put("width", width);
        el.put("height", height);
        return el;
    }

    private static Map<String, Object> text(String id, int x, int y, int width, int height, String binding,
                                            Map<String, Object> style) {
        Map<String, Object> el = element(id, "text", x, y, width, height);
        el.put("binding", binding);
        if (style != null) el.put("style", style);
        return el;
    }

    private static Map<String, Object> label(String id, int x, int y, int width, int height, String text,
                                             Map<String, Object> style) {
        Map<String, Object> el = element(id, "static", x, y, width, height);
        el.put("text", text);
        if (style != null) el.put("style", style);
        return el;
    }

    private static Map<String, Object> line(String id, int y) {
        Map<String, Object> el = new LinkedHashMap<>();
        el.put("id", id);
        el.put("type", "line");
        el.put("orientation", "horizontal");
        el.put("x", LEFT);
        el.put("y", y);
        el.put("width", CONTENT_WIDTH);
        el.put("height", 1);
        Map<String, Object> style = new LinkedHashMap<>();
        style.put("borderWidth", 1);
        style.put("borderColor", "#000000");
        el.put("style", style);
        return el;
    }

    private static Map<String, Object> bold() {
        return style(null, true, null);
    }

    private static Map<String, Object> style(Integer fontSize, boolean bold, String align) {
        Map<String, Object> style = new LinkedHashMap<>();
        if (fontSize != null) style.put("fontSize", fontSize);
        if (bold) style.put("bold", true);
        if (align != null) style.put("align", align);
        return style;
    }
}
