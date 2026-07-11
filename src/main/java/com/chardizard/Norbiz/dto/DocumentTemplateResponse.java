package com.chardizard.Norbiz.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
public class DocumentTemplateResponse {
    private Long id;
    private Long companyId;
    private String companyName;
    private String documentType;
    private String name;
    private String layout;
    private boolean defaultTemplate;
    private boolean active;
    private Instant createdAt;
    private Instant updatedAt;
    private String createdBy;
    private String updatedBy;
}
