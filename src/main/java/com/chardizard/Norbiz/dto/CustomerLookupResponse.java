package com.chardizard.Norbiz.dto;

import com.chardizard.Norbiz.models.CustomerType;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

// Customer dropdown option. type tells customers from outlets; warehouseId/warehouseName are the
// outlet's own warehouse (null for a plain customer), which outlet forms post to and read stock from.
@Getter
@AllArgsConstructor
@NoArgsConstructor // for reading cached entries back from Redis
public class CustomerLookupResponse {
    private Long id;
    private Long companyId;
    private String code;
    private String name;
    private boolean active;
    private CustomerType type;
    private Long warehouseId;
    private String warehouseName;
}
