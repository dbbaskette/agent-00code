package com.agent00code.web;

import com.agent00code.config.AgentConfig;
import com.agent00code.loop.AgentLoop;
import com.agent00code.loop.ScheduledLoopRunner;
import com.agent00code.loop.SystemPromptBuilder;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final AgentLoop agentLoop;
    private final AgentConfig agentConfig;
    private final SystemPromptBuilder systemPromptBuilder;
    private final ScheduledLoopRunner scheduledLoopRunner;
    private final JsonMapper objectMapper;

    public WebSocketConfig(AgentLoop agentLoop,
                           AgentConfig agentConfig,
                           SystemPromptBuilder systemPromptBuilder,
                           ScheduledLoopRunner scheduledLoopRunner,
                           JsonMapper objectMapper) {
        this.agentLoop = agentLoop;
        this.agentConfig = agentConfig;
        this.systemPromptBuilder = systemPromptBuilder;
        this.scheduledLoopRunner = scheduledLoopRunner;
        this.objectMapper = objectMapper;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(
                new ChatWebSocketHandler(agentLoop, agentConfig, systemPromptBuilder, objectMapper),
                "/ws/chat"
        ).setAllowedOrigins("*");

        registry.addHandler(
                new AgentWebSocketHandler(scheduledLoopRunner, objectMapper),
                "/ws/agent"
        ).setAllowedOrigins("*");
    }
}
