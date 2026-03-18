package com.agent00code.config;

import java.util.List;

public record McpServerConfig(
        String name,
        String url,
        String auth,
        String token,
        List<String> scopes
) {
    public McpServerConfig {
        if (auth == null) auth = "none";
        if (scopes == null) scopes = List.of();
    }
}
