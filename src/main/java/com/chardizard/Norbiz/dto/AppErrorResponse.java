package com.chardizard.Norbiz.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class AppErrorResponse {
    private final String message;

    public static AppErrorResponse of(String message) {
        return new AppErrorResponse(message);
    }
}
