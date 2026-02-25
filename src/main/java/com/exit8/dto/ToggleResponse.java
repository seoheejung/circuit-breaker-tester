package com.exit8.dto;

public record ToggleResponse(
        boolean toggleEnabled,
        String statusMessage
) {}
