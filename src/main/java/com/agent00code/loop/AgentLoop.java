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

    public AgentLoop(ChatClient.Builder chatClientBuilder,
                     ChatModel chatModel,
                     ToolCallbackProvider mcpToolCallbackProvider,
                     List<ToolCallback> toolCallbacks,
                     ToolSearcher toolSearcher) {
        this.chatClientBuilder = chatClientBuilder;
        this.chatModel = chatModel;
        this.mcpToolCallbackProvider = mcpToolCallbackProvider;
        this.additionalTools = toolCallbacks != null ? toolCallbacks.toArray(new ToolCallback[0]) : new ToolCallback[0];
        this.toolSearcher = toolSearcher;
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
                .defaultAdvisors(new EventEmittingAdvisor(eventQueue));

        if (allTools.length > 0) {
            clientBuilder.defaultToolCallbacks(allTools)
                    .defaultAdvisors(advisor);
        }

        ChatClient chatClient = clientBuilder.build();

        int consecutiveErrors = 0;
        for (int i = 1; i <= maxIterations; i++) {
            final int iteration = i;
            emit(events, eventQueue, new LoopEvent(
                    EventType.ITERATION,
                    Map.of("iteration", iteration),
                    iteration));

            log.info("Iteration {}/{}", iteration, maxIterations);

            try {
                log.info("Sending prompt to LLM (advisor will handle tool search + execution)...");
                ChatResponse response;
                try {
                    response = chatClient.prompt()
                            .user(iteration == 1 ? userPrompt :
                                    "Continue immediately. Call the next tool now. Do not ask for confirmation.")
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
                    String finalText = content != null ? content : "";
                    emit(events, eventQueue, new LoopEvent(
                            EventType.FINAL_ANSWER,
                            Map.of("text", finalText),
                            iteration));
                    return new LoopResult(finalText, iteration, events);
                }

                generation.getOutput().getToolCalls().forEach(tc -> {
                    emit(events, eventQueue, new LoopEvent(
                            EventType.TOOL_CALL,
                            Map.of("name", tc.name(), "arguments", tc.arguments()),
                            iteration));
                });

            } catch (Exception e) {
                consecutiveErrors++;
                log.error("Error in iteration {} (consecutive: {}): {}", iteration, consecutiveErrors, e.getMessage(), e);
                emit(events, eventQueue, new LoopEvent(
                        EventType.ERROR,
                        Map.of("error", e.getMessage()),
                        iteration));
                if (consecutiveErrors >= 3) {
                    log.error("Aborting after {} consecutive errors", consecutiveErrors);
                    emit(events, eventQueue, new LoopEvent(
                            EventType.FINAL_ANSWER,
                            Map.of("text", "Aborted after " + consecutiveErrors + " consecutive errors: " + e.getMessage()),
                            iteration));
                    return new LoopResult("Aborted: " + e.getMessage(), iteration, events);
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
