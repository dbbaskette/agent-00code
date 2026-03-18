package com.agent00code.config;

public record SkillConfig(
        String name,
        String description,
        String prompt
) {
    public SkillConfig {
        if (description == null) description = "";
        if (prompt == null) prompt = "";
    }
}
