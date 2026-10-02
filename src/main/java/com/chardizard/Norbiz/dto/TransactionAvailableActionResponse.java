package com.chardizard.Norbiz.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

// One configured action as seen by the caller on a specific transaction — drives the frontend's action buttons.
@Getter
@Setter
public class TransactionAvailableActionResponse {
    private Long actionDefinitionId;
    private String code;
    private String name;
    private int sortOrder;
    private boolean takenByMe;
    private boolean allowedForMe;
    private boolean prerequisitesMet;
    // True only when every condition holds: not voided, allowed, prerequisites met, not already taken by me.
    private boolean canTake;
    private List<String> missingPrerequisites;
    private List<String> takenBy;
}
