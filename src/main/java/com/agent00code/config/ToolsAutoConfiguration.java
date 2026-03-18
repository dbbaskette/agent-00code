package com.agent00code.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agent.tools.SkillsTool;
import org.springaicommunity.agent.tools.TodoWriteTool;
import org.springaicommunity.agent.tools.AskUserQuestionTool;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Configures the spring-ai-agent-utils toolkit: Skills, TodoWrite,
 * AskUserQuestion, and Subagent orchestration tools.
 */
@Configuration
public class ToolsAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ToolsAutoConfiguration.class);
    private static final String SKILLS_DIR = ".claude/skills";

    @Bean
    public ToolCallback skillsToolCallback() {
        SkillsTool.Builder builder = SkillsTool.builder();

        Path skillsPath = Path.of(SKILLS_DIR);
        if (Files.isDirectory(skillsPath)) {
            builder.addSkillsDirectory(SKILLS_DIR);
            log.info("Skills directory '{}' registered", SKILLS_DIR);
        } else {
            log.info("No skills directory found at '{}'; skills tool available but empty", SKILLS_DIR);
        }

        return builder.build();
    }

    @Bean
    public TodoWriteTool todoWriteTool() {
        return TodoWriteTool.builder().build();
    }

    @Bean
    public AskUserQuestionTool askUserQuestionTool() {
        return AskUserQuestionTool.builder().build();
    }
}
