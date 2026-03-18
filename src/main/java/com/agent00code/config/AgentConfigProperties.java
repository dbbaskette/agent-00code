package com.agent00code.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent")
public record AgentConfigProperties(
        String agentsMdPath
) {
    public AgentConfigProperties {
        if (agentsMdPath == null) agentsMdPath = "./AGENTS.md";
    }
}
