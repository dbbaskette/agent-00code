package com.agent00code.loop;

import java.util.Map;

public record LoopEvent(
        EventType type,
        Map<String, Object> data,
        int iteration
) {

    public enum EventType {
        THOUGHT("thought"),
        TOOL_CALL("tool_call"),
        TOOL_RESULT("tool_result"),
        FINAL_ANSWER("final_answer"),
        ERROR("error"),
        ITERATION("iteration");

        private final String value;

        EventType(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }
}
