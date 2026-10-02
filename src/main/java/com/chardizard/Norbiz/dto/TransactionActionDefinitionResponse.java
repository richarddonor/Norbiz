package com.chardizard.Norbiz.dto;

import com.chardizard.Norbiz.models.TransactionType;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.List;

@Getter
@Setter
public class TransactionActionDefinitionResponse {
    private Long id;
    private Long companyId;
    private String companyName;
    private TransactionType transactionType;
    private String code;
    private String name;
    private int sortOrder;
    private boolean active;
    private List<Ref> allowedRoles;
    private List<Ref> prerequisites;
    private Instant createdAt;
    private Instant updatedAt;
    private String createdBy;
    private String updatedBy;

    @Getter
    @Setter
    public static class Ref {
        private Long id;
        private String code;
        private String name;
    }
}
