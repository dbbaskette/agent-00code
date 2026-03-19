package com.agent00code.config;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Wraps a {@link ToolCallback} to sanitize its tool definition's input schema.
 * Removes JSON Schema fields (like "default") that are unsupported by some LLM
 * providers (e.g. Google Gemini SDK).
 */
public class SanitizedToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final ToolDefinition sanitizedDefinition;

    public SanitizedToolCallback(ToolCallback delegate) {
        this.delegate = delegate;
        ToolDefinition original = delegate.getToolDefinition();
        String cleanSchema = stripDefaults(original.inputSchema());
        this.sanitizedDefinition = ToolDefinition.builder()
                .name(original.name())
                .description(original.description())
                .inputSchema(cleanSchema)
                .build();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return sanitizedDefinition;
    }

    @Override
    public String call(String toolInput) {
        return delegate.call(toolInput);
    }

    @Override
    public String call(String toolInput, org.springframework.ai.chat.model.ToolContext toolContext) {
        return delegate.call(toolInput, toolContext);
    }

    /**
     * Strips "default" keys from a JSON Schema string.
     * Uses simple string replacement to avoid Jackson version conflicts.
     */
    static String stripDefaults(String schema) {
        if (schema == null) return null;
        // Remove "default": null, "default": "value", "default": 123, "default": true/false
        // This regex handles: "default": null | "string" | number | true | false
        return schema.replaceAll(",?\\s*\"default\"\\s*:\\s*(null|\"[^\"]*\"|\\d+|true|false)", "")
                     .replaceAll("\\{\\s*,", "{");  // clean up leading commas
    }
}
