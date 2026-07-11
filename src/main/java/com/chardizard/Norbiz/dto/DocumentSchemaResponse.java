package com.chardizard.Norbiz.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class DocumentSchemaResponse {
    private String documentType;
    private List<DocumentFieldSchema> fields;
    private List<DocumentRepeatingGroupSchema> repeatingGroups;

    public DocumentSchemaResponse(String documentType, List<DocumentFieldSchema> fields, List<DocumentRepeatingGroupSchema> repeatingGroups) {
        this.documentType = documentType;
        this.fields = fields;
        this.repeatingGroups = repeatingGroups;
    }
}
