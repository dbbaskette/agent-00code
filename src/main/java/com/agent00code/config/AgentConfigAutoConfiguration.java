package com.agent00code.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Configuration
@EnableConfigurationProperties(AgentConfigProperties.class)
public class AgentConfigAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AgentConfigAutoConfiguration.class);
    private static final String LOCAL_MCP_SERVERS = "mcp-servers.local.yml";

    @Bean
    public AgentConfig agentConfig(AgentConfigProperties properties) throws IOException {
        String agentsMdContent = loadContent(properties.agentsMdPath());
        log.info("Loading agent config from {}", properties.agentsMdPath());
        AgentConfig config = AgentConfigParser.parseContent(agentsMdContent);

        // Merge local MCP servers: check filesystem first, then classpath
        List<McpServerConfig> localServers = loadLocalMcpServers(properties.agentsMdPath());
        if (!localServers.isEmpty()) {
            List<McpServerConfig> merged = new ArrayList<>(config.mcpServers());
            merged.addAll(localServers);
            config = new AgentConfig(config.systemPrompt(), Collections.unmodifiableList(merged),
                    config.skills(), config.loop());
            log.info("Merged {} MCP server(s) from {}", localServers.size(), LOCAL_MCP_SERVERS);
        }

        log.info("Agent config loaded: {} MCP server(s), {} skill(s), loop_interval={}s",
                config.mcpServers().size(),
                config.skills().size(),
                config.loop().loopIntervalSeconds());
        return config;
    }

    private String loadContent(String location) throws IOException {
        // Try filesystem first
        Path fsPath = Path.of(location);
        if (Files.exists(fsPath)) {
            return Files.readString(fsPath);
        }
        // Fall back to classpath
        String cpLocation = location.startsWith("classpath:") ? location.substring(10) : location;
        Resource resource = new ClassPathResource(cpLocation);
        if (resource.exists()) {
            try (InputStream in = resource.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        throw new IOException("Could not find " + location + " on filesystem or classpath");
    }

    private List<McpServerConfig> loadLocalMcpServers(String agentsMdPath) {
        // Check filesystem next to AGENTS.md
        Path fsPath = Path.of(agentsMdPath);
        Path localFs = fsPath.getParent() != null
                ? fsPath.getParent().resolve(LOCAL_MCP_SERVERS)
                : Path.of(LOCAL_MCP_SERVERS);
        if (Files.exists(localFs)) {
            try {
                return AgentConfigParser.parseMcpServersYaml(localFs);
            } catch (IOException e) {
                log.warn("Failed to parse {}: {}", localFs, e.getMessage());
            }
        }
        // Check classpath
        Resource cpResource = new ClassPathResource(LOCAL_MCP_SERVERS);
        if (cpResource.exists()) {
            try (InputStream in = cpResource.getInputStream()) {
                String yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                return AgentConfigParser.parseMcpServersYaml(yaml);
            } catch (IOException e) {
                log.warn("Failed to parse {} from classpath: {}", LOCAL_MCP_SERVERS, e.getMessage());
            }
        }
        return List.of();
    }
}
