package com.agent00code.loop;

import com.agent00code.loop.LoopEvent.EventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Advisor that emits events to a {@link BlockingQueue} for each LLM call,
 * giving the UI real-time visibility into Spring AI's internal tool execution.
 */
public class EventEmittingAdvisor implements BaseAdvisor {

    private static final Logger log = LoggerFactory.getLogger(EventEmittingAdvisor.class);

    private final BlockingQueue<LoopEvent> eventQueue;
    private final AtomicInteger step = new AtomicInteger(0);

    public EventEmittingAdvisor(BlockingQueue<LoopEvent> eventQueue) {
        this.eventQueue = eventQueue;
    }

    @Override
    public int getOrder() {
        return 0;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        int currentStep = step.incrementAndGet();

        // Log which tools are being sent
        String toolNames = "none";
        if (request.prompt().getOptions() instanceof ToolCallingChatOptions toolOptions
                && toolOptions.getToolCallbacks() != null) {
            toolNames = toolOptions.getToolCallbacks().stream()
                    .map(tc -> tc.getToolDefinition().name())
                    .collect(Collectors.joining(", "));
        }

        log.info("Step {} — sending to LLM with tools: [{}]", currentStep, toolNames);

        // Count messages by type
        String messageInfo = request.prompt().getInstructions().stream()
                .collect(Collectors.groupingBy(m -> m.getMessageType().name(), Collectors.counting()))
                .entrySet().stream()
                .map(e -> e.getKey() + ":" + e.getValue())
                .collect(Collectors.joining(", "));

        if (eventQueue != null) {
            eventQueue.offer(new LoopEvent(
                    EventType.THOUGHT,
                    Map.of("text", "Step " + currentStep + " — calling LLM (" + messageInfo + ")"),
                    currentStep));
        }

        return request;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        int currentStep = step.get();

        if (response.chatResponse() != null && !response.chatResponse().getResults().isEmpty()) {
            var generation = response.chatResponse().getResults().getFirst();
            var toolCalls = generation.getOutput().getToolCalls();
            String text = generation.getOutput().getText();

            if (toolCalls != null && !toolCalls.isEmpty()) {
                for (var tc : toolCalls) {
                    log.info("Step {} — LLM called tool: {} args: {}", currentStep, tc.name(),
                            tc.arguments() != null ? tc.arguments().substring(0, Math.min(100, tc.arguments().length())) : "{}");
                    if (eventQueue != null) {
                        eventQueue.offer(new LoopEvent(
                                EventType.TOOL_CALL,
                                Map.of("name", tc.name(), "arguments", tc.arguments() != null ? tc.arguments() : "{}"),
                                currentStep));
                    }
                }
            }

            if (text != null && !text.isBlank()) {
                log.info("Step {} — LLM response: {}...", currentStep,
                        text.substring(0, Math.min(100, text.length())));
            }
        }

        return response;
    }
}
