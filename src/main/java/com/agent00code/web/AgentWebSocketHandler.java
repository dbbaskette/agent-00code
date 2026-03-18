package com.agent00code.web;

import com.agent00code.loop.LoopEvent;
import com.agent00code.loop.ScheduledLoopRunner;
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
 * WebSocket handler for observing scheduled background loop events at /ws/agent.
 * Each connected client subscribes to the ScheduledLoopRunner's event broadcast.
 */
public class AgentWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(AgentWebSocketHandler.class);

    private final ScheduledLoopRunner scheduledLoopRunner;
    private final JsonMapper objectMapper;

    public AgentWebSocketHandler(ScheduledLoopRunner scheduledLoopRunner,
                                 JsonMapper objectMapper) {
        this.scheduledLoopRunner = scheduledLoopRunner;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        LinkedBlockingQueue<LoopEvent> queue = new LinkedBlockingQueue<>();
        session.getAttributes().put("queue", queue);
        scheduledLoopRunner.addSubscriber(queue);

        // Pump events to the WebSocket client in a virtual thread
        Thread.ofVirtual().name("agent-ws-" + session.getId()).start(() -> {
            while (session.isOpen() && !Thread.currentThread().isInterrupted()) {
                try {
                    LoopEvent event = queue.poll(500, TimeUnit.MILLISECONDS);
                    if (event == null) continue;
                    sendEvent(session, event);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        @SuppressWarnings("unchecked")
        LinkedBlockingQueue<LoopEvent> queue =
                (LinkedBlockingQueue<LoopEvent>) session.getAttributes().get("queue");
        if (queue != null) {
            scheduledLoopRunner.removeSubscriber(queue);
        }
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
            log.warn("Failed to send agent WebSocket event: {}", e.getMessage());
        }
    }
}
