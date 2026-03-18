package com.agent00code.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AgentConfigParserTest {

    private static final String SAMPLE_MD = """
            # Test Agent

            You are a test agent. Be concise.

            ## MCP Servers

            ```yaml mcp-servers
            - name: my-server
              url: https://mcp.example.com
              auth: oauth
              scopes:
                - read
                - write
            ```

            ## Skills

            ```yaml skills
            - name: do-thing
              description: Does a thing
              prompt: |
                Do the thing carefully.
            ```

            ## Loop Config

            ```yaml loop-config
            max_iterations: 10
            initial_prompt: Do something useful.
            loop_interval_seconds: 60
            ```
            """;

    @TempDir
    Path tempDir;

    private Path writeAgentsMd(String content) throws IOException {
        Path path = tempDir.resolve("AGENTS.md");
        Files.writeString(path, content);
        return path;
    }

    @Test
    void parseSystemPrompt() throws IOException {
        AgentConfig config = AgentConfigParser.parse(writeAgentsMd(SAMPLE_MD));
        assertTrue(config.systemPrompt().contains("You are a test agent"));
        assertFalse(config.systemPrompt().contains("mcp-servers"));
        assertFalse(config.systemPrompt().contains("loop-config"));
    }

    @Test
    void parseMcpServers() throws IOException {
        AgentConfig config = AgentConfigParser.parse(writeAgentsMd(SAMPLE_MD));
        assertEquals(1, config.mcpServers().size());
        McpServerConfig srv = config.mcpServers().getFirst();
        assertEquals("my-server", srv.name());
        assertEquals("https://mcp.example.com", srv.url());
        assertEquals("oauth", srv.auth());
        assertTrue(srv.scopes().contains("read"));
        assertTrue(srv.scopes().contains("write"));
    }

    @Test
    void parseSkills() throws IOException {
        AgentConfig config = AgentConfigParser.parse(writeAgentsMd(SAMPLE_MD));
        assertEquals(1, config.skills().size());
        SkillConfig skill = config.skills().getFirst();
        assertEquals("do-thing", skill.name());
        assertEquals("Does a thing", skill.description());
        assertTrue(skill.prompt().contains("carefully"));
    }

    @Test
    void parseLoopConfig() throws IOException {
        AgentConfig config = AgentConfigParser.parse(writeAgentsMd(SAMPLE_MD));
        assertEquals(10, config.loop().maxIterations());
        assertEquals("Do something useful.", config.loop().initialPrompt());
        assertEquals(60, config.loop().loopIntervalSeconds());
    }

    @Test
    void emptyMcpServers() throws IOException {
        AgentConfig config = AgentConfigParser.parse(
                writeAgentsMd("# Agent\nJust do stuff.\n"));
        assertTrue(config.mcpServers().isEmpty());
        assertTrue(config.skills().isEmpty());
        assertEquals(50, config.loop().maxIterations());
    }

    @Test
    void missingOptionalFields() throws IOException {
        String md = """
                # Agent
                Do work.

                ```yaml mcp-servers
                - name: simple
                  url: https://simple.example.com
                ```
                """;
        AgentConfig config = AgentConfigParser.parse(writeAgentsMd(md));
        McpServerConfig srv = config.mcpServers().getFirst();
        assertEquals("none", srv.auth());
        assertTrue(srv.scopes().isEmpty());
    }

    @Test
    void returnsAgentConfigType() throws IOException {
        AgentConfig config = AgentConfigParser.parse(writeAgentsMd(SAMPLE_MD));
        assertNotNull(config);
        assertInstanceOf(AgentConfig.class, config);
    }
}
