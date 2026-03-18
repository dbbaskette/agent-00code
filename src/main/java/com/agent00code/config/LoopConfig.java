package com.agent00code.config;

public record LoopConfig(
        int maxIterations,
        String initialPrompt,
        int loopIntervalSeconds
) {
    public static final LoopConfig DEFAULTS = new LoopConfig(50, "Begin working on your task.", 0);
}
