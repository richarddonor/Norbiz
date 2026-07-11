package com.chardizard.Norbiz.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class DocumentRepeatingGroupSchema {
    private String path;
    private String label;
    private List<DocumentFieldSchema> fields;

    public DocumentRepeatingGroupSchema(String path, String label, List<DocumentFieldSchema> fields) {
        this.path = path;
        this.label = label;
        this.fields = fields;
    }
}
