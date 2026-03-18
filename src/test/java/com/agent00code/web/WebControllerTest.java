package com.agent00code.web;

import com.agent00code.config.AgentConfig;
import com.agent00code.config.LoopConfig;
import com.agent00code.config.McpServerConfig;
import io.modelcontextprotocol.client.McpSyncClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {ToolsController.class, AuthController.class})
class WebControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private List<McpSyncClient> mcpSyncClients;

    @MockitoBean
    private AgentConfig agentConfig;

    @Test
    void toolsEndpointEmpty() throws Exception {
        when(mcpSyncClients.isEmpty()).thenReturn(true);
        when(mcpSyncClients.iterator()).thenReturn(java.util.Collections.emptyIterator());

        mockMvc.perform(get("/tools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tools").isArray())
                .andExpect(jsonPath("$.auth_required").isArray());
    }

    @Test
    void authLoginUnknownServer() throws Exception {
        when(agentConfig.mcpServers()).thenReturn(List.of());

        mockMvc.perform(get("/auth/login/nonexistent"))
                .andExpect(status().isNotFound());
    }

    @Test
    void authLoginRedirects() throws Exception {
        McpServerConfig server = new McpServerConfig(
                "jira", "https://mcp.example.com/jira/mcp", "oauth", null, List.of("read"));
        when(agentConfig.mcpServers()).thenReturn(List.of(server));

        mockMvc.perform(get("/auth/login/jira"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/oauth2/authorization/jira"));
    }

    @Test
    void authCallbackMissingParams() throws Exception {
        mockMvc.perform(get("/auth/callback"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Missing code or state")));
    }

    @Test
    void authCallbackErrorParam() throws Exception {
        mockMvc.perform(get("/auth/callback")
                        .param("error", "access_denied")
                        .param("error_description", "User denied"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("User denied")));
    }
}
