package com.agent00code.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Path;

@Configuration
@EnableConfigurationProperties(AgentConfigProperties.class)
public class AgentConfigAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AgentConfigAutoConfiguration.class);

    @Bean
    public AgentConfig agentConfig(AgentConfigProperties properties) throws IOException {
        Path path = Path.of(properties.agentsMdPath());
        log.info("Loading agent config from {}", path.toAbsolutePath());
        AgentConfig config = AgentConfigParser.parse(path);
        log.info("Agent config loaded: {} MCP server(s), {} skill(s), loop_interval={}s",
                config.mcpServers().size(),
                config.skills().size(),
                config.loop().loopIntervalSeconds());
        return config;
    }
}
