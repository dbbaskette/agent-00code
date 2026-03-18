package com.agent00code.config;

import java.util.List;

public record AgentConfig(
        String systemPrompt,
        List<McpServerConfig> mcpServers,
        List<SkillConfig> skills,
        LoopConfig loop
) {}
