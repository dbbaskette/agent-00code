package com.agent00code.loop;

import com.agent00code.loop.LoopEvent.EventType;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springaicommunity.tool.search.ToolSearcher;

import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AgentLoopTest {

    private ToolCallbackProvider emptyProvider() {
        return () -> new ToolCallback[0];
    }

    @Test
    void simpleResponseNoTools() {
        ChatModel chatModel = mock(ChatModel.class);
        AssistantMessage assistantMessage = new AssistantMessage("Hello, world!");
        Generation generation = new Generation(assistantMessage);
        ChatResponse response = new ChatResponse(List.of(generation));

        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenReturn(response);

        AgentLoop loop = new AgentLoop(ChatClient.builder(chatModel), chatModel, emptyProvider(), List.of(), mock(ToolSearcher.class));


        LoopResult result = loop.run(
                "You are helpful.",
                "Say hello.",
                50,
                null);

        assertEquals("Hello, world!", result.answer());
        assertEquals(1, result.iterations());
    }

    @Test
    void eventsEmitted() {
        ChatModel chatModel = mock(ChatModel.class);
        AssistantMessage assistantMessage = new AssistantMessage("done");
        Generation generation = new Generation(assistantMessage);
        ChatResponse response = new ChatResponse(List.of(generation));

        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenReturn(response);

        AgentLoop loop = new AgentLoop(ChatClient.builder(chatModel), chatModel, emptyProvider(), List.of(), mock(ToolSearcher.class));


        LinkedBlockingQueue<LoopEvent> queue = new LinkedBlockingQueue<>();
        LoopResult result = loop.run("sys", "go", 50, queue);

        assertFalse(queue.isEmpty());
        List<EventType> types = queue.stream().map(LoopEvent::type).toList();
        assertTrue(types.contains(EventType.ITERATION));
        assertTrue(types.contains(EventType.FINAL_ANSWER));
    }
}
