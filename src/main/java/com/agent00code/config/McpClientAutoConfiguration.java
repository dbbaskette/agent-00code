package com.agent00code.config;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Creates MCP clients programmatically from the AGENTS.md configuration.
 * Each MCP server entry becomes an {@link McpSyncClient} connected via
 * Streamable HTTP transport.
 */
@Configuration
public class McpClientAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(McpClientAutoConfiguration.class);

    @Bean
    public List<McpSyncClient> mcpSyncClients(AgentConfig agentConfig) {
        List<McpServerConfig> servers = agentConfig.mcpServers();
        if (servers.isEmpty()) {
            log.info("No MCP servers configured in AGENTS.md");
            return List.of();
        }

        List<McpSyncClient> clients = new ArrayList<>();
        for (McpServerConfig server : servers) {
            try {
                McpSyncClient client = createClient(server);
                clients.add(client);
                log.info("MCP client created for server '{}' at {}", server.name(), server.url());
            } catch (Exception e) {
                log.warn("Failed to create MCP client for '{}': {}", server.name(), e.getMessage());
            }
        }
        return Collections.unmodifiableList(clients);
    }

    @Bean
    public ToolCallbackProvider mcpToolCallbackProvider(List<McpSyncClient> mcpSyncClients) {
        if (mcpSyncClients.isEmpty()) {
            return () -> new ToolCallback[0];
        }
        return new SyncMcpToolCallbackProvider(mcpSyncClients);
    }

    private McpSyncClient createClient(McpServerConfig server) {
        var transportBuilder = HttpClientStreamableHttpTransport.builder(server.url())
                .clientBuilder(HttpClient.newBuilder());

        if ("token".equalsIgnoreCase(server.auth()) && server.token() != null) {
            String resolvedToken = resolveToken(server.token());
            String authHeader = resolvedToken.regionMatches(true, 0, "Bearer ", 0, 7)
                    ? resolvedToken : "Bearer " + resolvedToken;
            transportBuilder.customizeRequest(req ->
                    req.header("Authorization", authHeader));
        }

        var transport = transportBuilder.build();
        var clientInfo = new McpSchema.Implementation("agent-00code", "0.1.0");

        McpSyncClient client = McpClient.sync(transport)
                .clientInfo(clientInfo)
                .requestTimeout(Duration.ofSeconds(30))
                .build();

        client.initialize();
        return client;
    }

    /**
     * Resolves a token value. If the value starts with {@code ${} and ends with {@code }},
     * it is treated as an environment variable reference (e.g. {@code ${MY_TOKEN}}).
     */
    private String resolveToken(String token) {
        if (token.startsWith("${") && token.endsWith("}")) {
            String envVar = token.substring(2, token.length() - 1);
            String value = System.getenv(envVar);
            if (value == null || value.isBlank()) {
                throw new IllegalStateException("Environment variable '" + envVar + "' is not set");
            }
            return value;
        }
        return token;
    }
}
