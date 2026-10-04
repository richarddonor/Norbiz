package com.chardizard.Norbiz.dto;

import com.chardizard.Norbiz.audit.AuditAction;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.List;

/** One save of a record: its own field changes plus those of the parts saved with it (an item's SKUs/prices). */
@Getter
@Setter
public class ChangeHistoryEntryResponse {
    private Long id;
    private AuditAction action;
    private String changedBy;
    private Instant changedAt;
    /** CREATE: every initial value (kind ADDED). UPDATE: changed fields only. */
    private List<FieldChange> changes;
    /** False for logs written before associations/collections/parts were recorded — an empty
     *  UPDATE there means something unrecorded changed, not that nothing did. */
    private boolean detailed;

    public enum Kind { ADDED, REMOVED, CHANGED }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FieldChange {
        /** Entity field name, e.g. "itemCategory"; for parts, "<part>.<field>" e.g. "skus.unitPrice". */
        private String field;
        /** Ready-made caption for parts (e.g. "SKU ABC-1 · Unit Price"); null → derive it from {@code field}. */
        private String label;
        private Kind kind;
        private String oldValue;
        private String newValue;
    }
}
