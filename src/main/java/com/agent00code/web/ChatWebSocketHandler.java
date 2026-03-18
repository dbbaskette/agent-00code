package com.agent00code.web;

import com.agent00code.config.AgentConfig;
import com.agent00code.loop.AgentLoop;
import com.agent00code.loop.LoopEvent;
import com.agent00code.loop.LoopEvent.EventType;
import com.agent00code.loop.SystemPromptBuilder;
import tools.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * WebSocket handler for interactive chat at /ws/chat.
 * Preserves the same JSON protocol as the original implementation:
 * incoming: {"message": "..."}, outgoing: {"type": "...", "data": {...}, "iteration": N}
 */
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);

    private final AgentLoop agentLoop;
    private final AgentConfig agentConfig;
    private final SystemPromptBuilder systemPromptBuilder;
    private final JsonMapper objectMapper;

    public ChatWebSocketHandler(AgentLoop agentLoop,
                                AgentConfig agentConfig,
                                SystemPromptBuilder systemPromptBuilder,
                                JsonMapper objectMapper) {
        this.agentLoop = agentLoop;
        this.agentConfig = agentConfig;
        this.systemPromptBuilder = systemPromptBuilder;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("Chat WebSocket connected: {}", session.getId());
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload().trim();
        String userMessage;

        try {
            Map<String, Object> parsed = objectMapper.readValue(payload, Map.class);
            userMessage = ((String) parsed.getOrDefault("message", "")).trim();
        } catch (Exception e) {
            userMessage = payload;
        }

        if (userMessage.isEmpty()) return;

        LinkedBlockingQueue<LoopEvent> eventQueue = new LinkedBlockingQueue<>();

        // Forward events to the WebSocket client in a virtual thread
        Thread sender = Thread.ofVirtual().name("ws-sender-" + session.getId()).start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    LoopEvent event = eventQueue.poll(100, TimeUnit.MILLISECONDS);
                    if (event == null) continue;

                    sendEvent(session, event);

                    if (event.type() == EventType.FINAL_ANSWER || event.type() == EventType.ERROR) {
                        break;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });

        try {
            String systemPrompt = systemPromptBuilder.build();
            agentLoop.run(
                    systemPrompt,
                    userMessage,
                    agentConfig.loop().maxIterations(),
                    eventQueue);
        } catch (Exception e) {
            log.error("Error in chat loop", e);
            eventQueue.offer(new LoopEvent(
                    EventType.ERROR,
                    Map.of("error", e.getMessage()),
                    0));
        } finally {
            sender.join(5000);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("Chat WebSocket disconnected: {}", session.getId());
    }

    private void sendEvent(WebSocketSession session, LoopEvent event) {
        try {
            if (session.isOpen()) {
                Map<String, Object> msg = Map.of(
                        "type", event.type().value(),
                        "data", event.data(),
                        "iteration", event.iteration()
                );
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(msg)));
            }
        } catch (Exception e) {
            log.warn("Failed to send WebSocket event: {}", e.getMessage());
        }
    }
}
