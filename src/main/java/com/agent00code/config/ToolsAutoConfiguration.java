package com.agent00code.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agent.tools.TodoWriteTool;
import org.springaicommunity.agent.tools.AskUserQuestionTool;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configures the spring-ai-agent-utils toolkit: TodoWrite and AskUserQuestion.
 */
@Configuration
public class ToolsAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ToolsAutoConfiguration.class);

    @Bean
    public TodoWriteTool todoWriteTool() {
        return TodoWriteTool.builder().build();
    }

    @Bean
    public AskUserQuestionTool askUserQuestionTool() {
        return AskUserQuestionTool.builder()
                .questionHandler(question -> java.util.Map.of("answer", "No interactive user available. Proceed with your best judgment."))
                .build();
    }
}
