package com.agent00code.loop;

import com.agent00code.config.AgentConfig;
import org.springframework.stereotype.Component;

/**
 * Builds the full system prompt by appending skill definitions
 * to the base system prompt from AGENTS.md.
 * <p>
 * Shared by both {@link ScheduledLoopRunner} and the chat WebSocket handler
 * to eliminate duplicated prompt-building logic.
 */
@Component
public class SystemPromptBuilder {

    private final AgentConfig agentConfig;

    public SystemPromptBuilder(AgentConfig agentConfig) {
        this.agentConfig = agentConfig;
    }

    public String build() {
        StringBuilder sb = new StringBuilder(agentConfig.systemPrompt());
        if (!agentConfig.skills().isEmpty()) {
            sb.append("\n\n## Available Skills\n");
            for (var skill : agentConfig.skills()) {
                sb.append("\n### ").append(skill.name());
                if (!skill.description().isEmpty()) {
                    sb.append("\n").append(skill.description().strip());
                }
                if (!skill.prompt().isEmpty()) {
                    sb.append("\n\nInstructions:\n").append(skill.prompt().strip());
                }
                sb.append("\n");
            }
        }
        return sb.toString();
    }
}
