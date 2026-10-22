package com.chardizard.Norbiz.dto;

import com.chardizard.Norbiz.models.AccountSide;
import com.chardizard.Norbiz.models.AgingBasis;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.List;

@Getter
@Setter
public class AgingReportResponse {
    private AccountSide side;
    private Long companyId;
    private Instant asOf;
    private AgingBasis basis;
    private List<AgingRowResponse> rows;
    private AgingRowResponse totals;
}
