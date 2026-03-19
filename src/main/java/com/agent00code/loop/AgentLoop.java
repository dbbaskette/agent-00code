package com.agent00code.loop;

import com.agent00code.loop.LoopEvent.EventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springaicommunity.tool.search.ToolSearchToolCallAdvisor;
import org.springaicommunity.tool.search.ToolSearcher;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;

/**
 * ReAct-style agentic loop with dynamic tool search.
 * <p>
 * Uses {@link ToolSearchToolCallAdvisor} so the LLM discovers tools via search
 * rather than receiving all definitions up front.
 */
@Component
public class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);

    private final ChatClient.Builder chatClientBuilder;
    private final ChatModel chatModel;
    private final ToolCallbackProvider mcpToolCallbackProvider;
    private final ToolCallback[] additionalTools;
    private final ToolSearcher toolSearcher;
    private final com.agent00code.tools.ScanProgressTool scanProgressTool;

    public AgentLoop(ChatClient.Builder chatClientBuilder,
                     ChatModel chatModel,
                     ToolCallbackProvider mcpToolCallbackProvider,
                     List<ToolCallback> toolCallbacks,
                     ToolSearcher toolSearcher,
                     com.agent00code.tools.ScanProgressTool scanProgressTool) {
        this.chatClientBuilder = chatClientBuilder;
        this.chatModel = chatModel;
        this.mcpToolCallbackProvider = mcpToolCallbackProvider;
        this.additionalTools = toolCallbacks != null ? toolCallbacks.toArray(new ToolCallback[0]) : new ToolCallback[0];
        this.toolSearcher = toolSearcher;
        this.scanProgressTool = scanProgressTool;
    }

    public LoopResult run(String systemPrompt,
                          String userPrompt,
                          int maxIterations,
                          BlockingQueue<LoopEvent> eventQueue) {

        List<LoopEvent> events = new ArrayList<>();
        ToolCallback[] mcpTools = mcpToolCallbackProvider.getToolCallbacks();

        int totalTools = mcpTools.length + additionalTools.length;
        log.info("Starting loop with {} tool(s) ({} MCP + {} additional), max_iterations={}",
                totalTools, mcpTools.length, additionalTools.length, maxIterations);

        // Combine all tools
        ToolCallback[] allTools = new ToolCallback[mcpTools.length + additionalTools.length];
        System.arraycopy(mcpTools, 0, allTools, 0, mcpTools.length);
        System.arraycopy(additionalTools, 0, allTools, mcpTools.length, additionalTools.length);

        // Build fresh ChatClient with tools + ToolSearchToolCallAdvisor + event emitting
        var advisor = ToolSearchToolCallAdvisor.builder()
                .toolSearcher(toolSearcher)
                .referenceToolNameAccumulation(true)
                .maxResults(5)
                .build();

        var clientBuilder = ChatClient.builder(chatModel)
                .defaultSystem(systemPrompt)
                .defaultTools(scanProgressTool)
                .defaultAdvisors(new EventEmittingAdvisor(eventQueue));

        if (allTools.length > 0) {
            clientBuilder.defaultToolCallbacks(allTools)
                    .defaultAdvisors(advisor);
        }

        ChatClient chatClient = clientBuilder.build();

        int consecutiveErrors = 0;
        StringBuilder completedWork = new StringBuilder();

        for (int i = 1; i <= maxIterations; i++) {
            final int iteration = i;
            emit(events, eventQueue, new LoopEvent(
                    EventType.ITERATION,
                    Map.of("iteration", iteration),
                    iteration));

            log.info("Iteration {}/{}", iteration, maxIterations);

            try {
                String prompt;
                if (iteration == 1) {
                    // Reset progress tracker for a fresh run
                    scanProgressTool.resetProgress();
                    prompt = userPrompt;
                } else {
                    prompt = "Continue scanning orgs. Call getProgress to see which orgs " +
                            "you already finished in this run so you can pick up where you left off. " +
                            "After scanning an org and writing to Sheets, call markOrgComplete.";
                }

                log.info("Sending prompt to LLM (iteration {})...", iteration);
                ChatResponse response;
                try {
                    response = chatClient.prompt()
                            .user(prompt)
                            .call()
                            .chatResponse();
                } catch (Exception inner) {
                    log.error("Inner call failed: {} — {}", inner.getClass().getSimpleName(), inner.getMessage());
                    throw inner;
                }
                log.info("LLM call returned");

                if (response == null || response.getResults().isEmpty()) {
                    emit(events, eventQueue, new LoopEvent(
                            EventType.ERROR,
                            Map.of("error", "Empty response from LLM"),
                            iteration));
                    return new LoopResult("", iteration, events);
                }

                Generation generation = response.getResults().getFirst();
                String content = generation.getOutput().getText();

                if (content != null && !content.isBlank()) {
                    emit(events, eventQueue, new LoopEvent(
                            EventType.THOUGHT,
                            Map.of("text", content),
                            iteration));
                }

                boolean hasToolCalls = generation.getOutput().getToolCalls() != null
                        && !generation.getOutput().getToolCalls().isEmpty();

                if (!hasToolCalls) {
                    // Not done — the LLM just finished one batch (e.g. one org)
                    // Record what was done and continue to next iteration
                    if (content != null && !content.isBlank()) {
                        completedWork.append("- Iteration ").append(iteration).append(": ")
                                .append(content.length() > 200 ? content.substring(0, 200) + "..." : content)
                                .append("\n");
                    }
                    emit(events, eventQueue, new LoopEvent(
                            EventType.THOUGHT,
                            Map.of("text", "Iteration " + iteration + " complete. Continuing..."),
                            iteration));
                    // Continue to next iteration — don't return
                    continue;
                }

                generation.getOutput().getToolCalls().forEach(tc -> {
                    emit(events, eventQueue, new LoopEvent(
                            EventType.TOOL_CALL,
                            Map.of("name", tc.name(), "arguments", tc.arguments()),
                            iteration));
                });

            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : "";
                boolean isContextLimit = msg.contains("400") && msg.contains("runtime_error");
                boolean isMcpError = msg.contains("Tool execution failed") || msg.contains("chunked transfer");

                if (isContextLimit) {
                    // Context window full — rebuild ChatClient for a fresh conversation
                    log.warn("Context limit hit in iteration {}. Rebuilding client for fresh conversation.", iteration);
                    emit(events, eventQueue, new LoopEvent(
                            EventType.THOUGHT,
                            Map.of("text", "Context limit reached. Starting fresh conversation and resuming from progress checkpoint."),
                            iteration));

                    // Rebuild the ChatClient (fresh advisor state, clean context)
                    advisor = ToolSearchToolCallAdvisor.builder()
                            .toolSearcher(toolSearcher)
                            .referenceToolNameAccumulation(true)
                            .maxResults(5)
                            .build();
                    clientBuilder = ChatClient.builder(chatModel)
                            .defaultSystem(systemPrompt)
                            .defaultTools(scanProgressTool)
                            .defaultAdvisors(new EventEmittingAdvisor(eventQueue));
                    if (allTools.length > 0) {
                        clientBuilder.defaultToolCallbacks(allTools)
                                .defaultAdvisors(advisor);
                    }
                    chatClient = clientBuilder.build();

                    consecutiveErrors = 0; // reset — this is a fresh start, not a repeated failure
                    continue;
                }

                consecutiveErrors++;
                log.error("Error in iteration {} (consecutive: {}): {}", iteration, consecutiveErrors, msg, e);
                emit(events, eventQueue, new LoopEvent(
                        EventType.ERROR,
                        Map.of("error", msg),
                        iteration));
                if (consecutiveErrors >= 3) {
                    log.error("Aborting after {} consecutive errors", consecutiveErrors);
                    emit(events, eventQueue, new LoopEvent(
                            EventType.FINAL_ANSWER,
                            Map.of("text", "Aborted after " + consecutiveErrors + " consecutive errors: " + msg),
                            iteration));
                    return new LoopResult("Aborted: " + msg, iteration, events);
                }
                log.info("Retrying on next iteration...");
                continue;
            }
            consecutiveErrors = 0; // reset on success
        }

        log.warn("Reached max iterations ({}), requesting summary", maxIterations);
        try {
            String summary = chatClient.prompt()
                    .user("You have reached the maximum number of steps. " +
                            "Summarise what you have done and what remains.")
                    .call()
                    .content();

            emit(events, eventQueue, new LoopEvent(
                    EventType.FINAL_ANSWER,
                    Map.of("text", summary != null ? summary : ""),
                    maxIterations));
            return new LoopResult(summary != null ? summary : "", maxIterations, events);
        } catch (Exception e) {
            return new LoopResult("Reached max iterations", maxIterations, events);
        }
    }

    private void emit(List<LoopEvent> events,
                      BlockingQueue<LoopEvent> queue,
                      LoopEvent event) {
        events.add(event);
        if (queue != null) {
            queue.offer(event);
        }
    }
}
