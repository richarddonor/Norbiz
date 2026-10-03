package com.chardizard.Norbiz.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

// Slim dropdown option for master data — deliberately carries only what a select needs, so the
// lookup endpoints can be opened to form users who lack the entity's full VIEW_ permission.
// companyId is null for system-wide entities (Role) and multi-company ones (User).
@Getter
@AllArgsConstructor
@NoArgsConstructor // for reading cached entries back from Redis
public class LookupResponse {
    private Long id;
    private Long companyId;
    private String code;
    private String name;
    private boolean active;
}
