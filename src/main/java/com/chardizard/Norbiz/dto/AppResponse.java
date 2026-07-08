package com.chardizard.Norbiz.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class AppResponse<T> {
    private final T data;

    public static <T> AppResponse<T> of(T data) {
        return new AppResponse<>(data);
    }
}