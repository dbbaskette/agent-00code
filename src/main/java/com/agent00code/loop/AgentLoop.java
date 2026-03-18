package com.agent00code.loop;

import com.agent00code.loop.LoopEvent.EventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;

/**
 * ReAct-style agentic loop: think -> act -> observe -> repeat.
 * <p>
 * Uses Spring AI's {@link ChatClient} with tool callbacks. Events are emitted
 * onto an optional {@link BlockingQueue} so both the background runner and the
 * web chat UI can consume them in real time.
 * <p>
 * Accepts both the MCP {@link ToolCallbackProvider} and individual tool beans
 * (SkillsTool, TodoWriteTool), combining them in the ChatClient.
 */
@Component
public class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);

    private final ChatClient.Builder chatClientBuilder;
    private final ToolCallbackProvider mcpToolCallbackProvider;
    private final ToolCallback[] additionalTools;

    public AgentLoop(ChatClient.Builder chatClientBuilder,
                     ToolCallbackProvider mcpToolCallbackProvider,
                     List<ToolCallback> toolCallbacks) {
        this.chatClientBuilder = chatClientBuilder;
        this.mcpToolCallbackProvider = mcpToolCallbackProvider;
        this.additionalTools = toolCallbacks != null ? toolCallbacks.toArray(new ToolCallback[0]) : new ToolCallback[0];
    }

    /**
     * Run the agentic loop for a single user prompt.
     * <p>
     * Spring AI's ChatClient handles tool call execution internally when
     * tool callbacks are registered. We run in a simple request-response loop
     * to support the max_iterations cap and event emission.
     */
    public LoopResult run(String systemPrompt,
                          String userPrompt,
                          int maxIterations,
                          BlockingQueue<LoopEvent> eventQueue) {

        List<LoopEvent> events = new ArrayList<>();
        ToolCallback[] mcpTools = mcpToolCallbackProvider.getToolCallbacks();

        int totalTools = mcpTools.length + additionalTools.length;
        log.info("Starting loop with {} tool(s) ({} MCP + {} additional), max_iterations={}",
                totalTools, mcpTools.length, additionalTools.length, maxIterations);

        ChatClient chatClient = chatClientBuilder
                .defaultSystem(systemPrompt)
                .defaultToolCallbacks(mcpToolCallbackProvider)
                .defaultToolCallbacks(additionalTools)
                .build();

        for (int i = 1; i <= maxIterations; i++) {
            final int iteration = i;
            emit(events, eventQueue, new LoopEvent(
                    EventType.ITERATION,
                    Map.of("iteration", iteration),
                    iteration));

            log.info("Iteration {}/{}", iteration, maxIterations);

            try {
                ChatResponse response = chatClient.prompt()
                        .user(iteration == 1 ? userPrompt :
                                "Continue working on the task. If done, provide your final answer.")
                        .call()
                        .chatResponse();

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
                log.error("Error in iteration {}: {}", iteration, e.getMessage(), e);
                emit(events, eventQueue, new LoopEvent(
                        EventType.ERROR,
                        Map.of("error", e.getMessage()),
                        iteration));
                return new LoopResult("Error: " + e.getMessage(), iteration, events);
            }
        }

        // Reached max iterations — ask for summary
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
