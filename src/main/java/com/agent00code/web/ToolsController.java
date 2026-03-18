package com.agent00code.web;

import com.agent00code.config.AgentConfig;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
public class ToolsController {

    private static final Logger log = LoggerFactory.getLogger(ToolsController.class);

    private final List<McpSyncClient> mcpSyncClients;
    private final AgentConfig agentConfig;

    public ToolsController(List<McpSyncClient> mcpSyncClients, AgentConfig agentConfig) {
        this.mcpSyncClients = mcpSyncClients;
        this.agentConfig = agentConfig;
    }

    @GetMapping("/personality")
    public Map<String, String> personality() {
        return Map.of("system_prompt", agentConfig.systemPrompt());
    }

    @GetMapping("/tools")
    public Map<String, Object> listTools() {
        List<Map<String, Object>> tools = new ArrayList<>();
        List<String> authRequired = new ArrayList<>();

        for (McpSyncClient client : mcpSyncClients) {
            try {
                var result = client.listTools();
                String serverName = client.getServerInfo() != null
                        ? client.getServerInfo().name()
                        : "unknown";

                for (McpSchema.Tool tool : result.tools()) {
                    tools.add(Map.of(
                            "server", serverName,
                            "name", tool.name(),
                            "qualified_name", serverName + "__" + tool.name(),
                            "description", tool.description() != null ? tool.description() : "",
                            "schema", tool.inputSchema() != null ? tool.inputSchema() : Map.of()
                    ));
                }
            } catch (Exception e) {
                log.warn("Failed to list tools from MCP client: {}", e.getMessage());
            }
        }

        return Map.of("tools", tools, "auth_required", authRequired);
    }
}
