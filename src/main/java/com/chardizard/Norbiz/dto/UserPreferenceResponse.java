package com.chardizard.Norbiz.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.Instant;

@Getter
@AllArgsConstructor
public class UserPreferenceResponse {
    private String key;
    private String value;
    private Instant updatedAt;
}
