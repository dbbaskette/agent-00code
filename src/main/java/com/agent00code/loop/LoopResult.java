package com.agent00code.loop;

import java.util.List;

public record LoopResult(
        String answer,
        int iterations,
        List<LoopEvent> events
) {}
