package com.chardizard.Norbiz.dto;

// One entry of the dashboard widget catalog — only widgets the caller holds the permission for are listed.
public record DashboardWidgetResponse(String key, String name, String category, String description) {
}
