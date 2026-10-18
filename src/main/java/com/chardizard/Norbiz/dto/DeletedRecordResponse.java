package com.chardizard.Norbiz.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** A master-data record that has been deleted, as captured by its DELETE audit log. */
@Getter
@Setter
public class DeletedRecordResponse {
    /** The deleted record's id — pass it to /master-data/{type}/{id}/history for its full history. */
    private Long id;
    /** Null for Users (not owned by one company). */
    private Long companyId;
    private String companyName;
    /** Code/SKU code/employee code/username, whichever the type has; null if none. */
    private String code;
    private String name;
    private String deletedBy;
    private Instant deletedAt;
}
