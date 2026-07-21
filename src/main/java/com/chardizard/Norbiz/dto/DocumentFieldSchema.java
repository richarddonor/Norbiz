package com.chardizard.Norbiz.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DocumentFieldSchema {
    private String path;
    private String label;
    private String type; // "string" | "number" | "date" | "currency" | "user"

    public DocumentFieldSchema(String path, String label, String type) {
        this.path = path;
        this.label = label;
        this.type = type;
    }
}
