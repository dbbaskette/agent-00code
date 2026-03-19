package com.agent00code.web;

import com.agent00code.config.AgentConfig;
import com.agent00code.loop.ScheduledLoopRunner;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.chat.model.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class ToolsController {

    private static final Logger log = LoggerFactory.getLogger(ToolsController.class);

    private final List<McpSyncClient> mcpSyncClients;
    private final AgentConfig agentConfig;
    private final ScheduledLoopRunner loopRunner;
    private final ChatClient.Builder chatClientBuilder;
    private final ToolCallbackProvider mcpToolCallbackProvider;
    private final ChatModel chatModel;

    public ToolsController(List<McpSyncClient> mcpSyncClients, AgentConfig agentConfig,
                           ScheduledLoopRunner loopRunner, ChatClient.Builder chatClientBuilder,
                           ToolCallbackProvider mcpToolCallbackProvider, ChatModel chatModel) {
        this.mcpSyncClients = mcpSyncClients;
        this.agentConfig = agentConfig;
        this.loopRunner = loopRunner;
        this.chatClientBuilder = chatClientBuilder;
        this.mcpToolCallbackProvider = mcpToolCallbackProvider;
        this.chatModel = chatModel;
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

    @GetMapping("/config")
    public Map<String, Object> config() {
        Map<String, Object> result = new LinkedHashMap<>();

        List<Map<String, Object>> servers = new ArrayList<>();
        for (var server : agentConfig.mcpServers()) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("name", server.name());
            s.put("url", server.url());
            s.put("auth", server.auth());
            s.put("scopes", server.scopes());
            servers.add(s);
        }
        result.put("mcp_servers", servers);

        var loop = agentConfig.loop();
        Map<String, Object> loopMap = new LinkedHashMap<>();
        loopMap.put("max_iterations", loop.maxIterations());
        loopMap.put("initial_prompt", loop.initialPrompt());
        loopMap.put("loop_interval_seconds", loop.loopIntervalSeconds());
        result.put("loop", loopMap);

        // Model info
        Map<String, Object> modelMap = new LinkedHashMap<>();
        modelMap.put("class", chatModel.getClass().getSimpleName());
        String modelName = System.getProperty("spring.ai.openai.chat.options.model", "");
        if (modelName.isEmpty()) {
            modelName = System.getenv("LLM_MODEL") != null ? System.getenv("LLM_MODEL") : "default";
        }
        modelMap.put("model", modelName);
        String baseUrl = System.getProperty("spring.ai.openai.base-url", "");
        if (!baseUrl.isEmpty()) {
            modelMap.put("base_url", baseUrl);
        }
        result.put("model", modelMap);

        return result;
    }

    @PostMapping("/run")
    public Map<String, String> triggerRun() {
        loopRunner.triggerRun();
        return Map.of("status", "triggered", "message", "Agent run triggered.");
    }

    @GetMapping("/schedule")
    public Map<String, Object> getSchedule() {
        return Map.of("enabled", loopRunner.isScheduledEnabled());
    }

    @PostMapping("/schedule")
    public Map<String, Object> setSchedule(@org.springframework.web.bind.annotation.RequestBody Map<String, Boolean> body) {
        boolean enabled = body.getOrDefault("enabled", false);
        loopRunner.setScheduledEnabled(enabled);
        return Map.of("enabled", loopRunner.isScheduledEnabled());
    }

    /** Temporary diagnostic: test LLM with and without tools */
    @GetMapping("/diag/llm")
    public Map<String, Object> diagLlm() {
        Map<String, Object> result = new LinkedHashMap<>();
        ChatClient chatClient = chatClientBuilder.build();

        // Test 1: no tools
        try {
            String noToolsResponse = chatClient.prompt()
                    .user("Say 'hello' and nothing else.")
                    .call()
                    .content();
            result.put("no_tools", noToolsResponse);
        } catch (Exception e) {
            result.put("no_tools_error", e.getMessage());
        }

        // Test 2: with 1 tool
        try {
            ToolCallback[] tools = mcpToolCallbackProvider.getToolCallbacks();
            result.put("total_tools_available", tools.length);
            if (tools.length > 0) {
                String oneToolResponse = chatClient.prompt()
                        .user("Say 'hello' and nothing else.")
                        .toolCallbacks(tools[0])
                        .call()
                        .content();
                result.put("one_tool", oneToolResponse);
            }
        } catch (Exception e) {
            result.put("one_tool_error", e.getMessage());
        }

        // Test 3: find max tools the model can handle
        try {
            ToolCallback[] tools = mcpToolCallbackProvider.getToolCallbacks();
            int maxWorking = 1;
            int[] testSizes = {5, 10, 11, 12, 13, 14};
            for (int size : testSizes) {
                if (size > tools.length) size = tools.length;
                try {
                    ToolCallback[] subset = java.util.Arrays.copyOf(tools, size);
                    chatClient.prompt()
                            .user("Say 'ok' and nothing else.")
                            .toolCallbacks(subset)
                            .call()
                            .content();
                    maxWorking = size;
                    result.put("tools_" + size, "OK");
                } catch (Exception e) {
                    result.put("tools_" + size, "FAIL: " + e.getMessage().substring(0, Math.min(80, e.getMessage().length())));
                    break;
                }
            }
            result.put("max_working_tools", maxWorking);
        } catch (Exception e) {
            result.put("tool_test_error", e.getMessage());
        }

        return result;
    }
}
