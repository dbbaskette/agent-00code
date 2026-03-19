package com.agent00code.config;

import org.springaicommunity.tool.searcher.LuceneToolSearcher;
import org.springaicommunity.tool.search.ToolSearcher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configures the Lucene-based tool searcher for dynamic tool discovery.
 * The LLM uses a "search" tool to find relevant tools by keyword,
 * avoiding the need to send all tool definitions in every request.
 */
@Configuration
public class ToolSearchConfiguration {

    @Bean
    public ToolSearcher toolSearcher() {
        return new LuceneToolSearcher();
    }
}
