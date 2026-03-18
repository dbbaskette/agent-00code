package com.agent00code.config;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses an AGENTS.md file into a structured {@link AgentConfig}.
 * <p>
 * Machine-readable configuration lives in specially named fenced code blocks
 * ({@code ```yaml mcp-servers}, {@code ```yaml skills}, {@code ```yaml loop-config}).
 * Everything else becomes the agent's system prompt.
 */
public final class AgentConfigParser {

    private static final Pattern FENCED_BLOCK = Pattern.compile(
            "```yaml\\s+([\\w-]+)\\n(.*?)```", Pattern.DOTALL);

    private AgentConfigParser() {}

    public static AgentConfig parse(Path path) throws IOException {
        String content = Files.readString(path);
        return parseContent(content);
    }

    public static AgentConfig parseContent(String content) {
        String systemPrompt = stripFencedBlocks(content);
        List<McpServerConfig> mcpServers = parseMcpServers(content);
        List<SkillConfig> skills = parseSkills(content);
        LoopConfig loop = parseLoopConfig(content);

        return new AgentConfig(systemPrompt, mcpServers, skills, loop);
    }

    static String stripFencedBlocks(String text) {
        String cleaned = FENCED_BLOCK.matcher(text).replaceAll("");
        cleaned = cleaned.replaceAll("\\n{3,}", "\n\n");
        return cleaned.strip();
    }

    @SuppressWarnings("unchecked")
    static Object extractBlock(String text, String blockName) {
        Matcher matcher = FENCED_BLOCK.matcher(text);
        while (matcher.find()) {
            if (matcher.group(1).equals(blockName)) {
                Yaml yaml = new Yaml();
                return yaml.load(matcher.group(2));
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<McpServerConfig> parseMcpServers(String content) {
        Object raw = extractBlock(content, "mcp-servers");
        if (!(raw instanceof List<?> list)) return List.of();

        List<McpServerConfig> servers = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> m = (Map<String, Object>) map;
                List<String> scopes = m.containsKey("scopes") && m.get("scopes") instanceof List<?>
                        ? ((List<?>) m.get("scopes")).stream().map(Object::toString).toList()
                        : List.of();
                servers.add(new McpServerConfig(
                        (String) m.get("name"),
                        (String) m.get("url"),
                        (String) m.getOrDefault("auth", "none"),
                        (String) m.getOrDefault("token", null),
                        scopes
                ));
            }
        }
        return Collections.unmodifiableList(servers);
    }

    /**
     * Parses a standalone YAML file containing a list of MCP server definitions.
     */
    public static List<McpServerConfig> parseMcpServersYaml(Path path) throws IOException {
        return parseMcpServersYaml(Files.readString(path));
    }

    /**
     * Parses raw YAML content containing a list of MCP server definitions.
     */
    @SuppressWarnings("unchecked")
    public static List<McpServerConfig> parseMcpServersYaml(String yamlContent) {
        Object raw = new Yaml().load(yamlContent);
        if (!(raw instanceof List<?> list)) return List.of();

        List<McpServerConfig> servers = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> m = (Map<String, Object>) map;
                List<String> scopes = m.containsKey("scopes") && m.get("scopes") instanceof List<?>
                        ? ((List<?>) m.get("scopes")).stream().map(Object::toString).toList()
                        : List.of();
                servers.add(new McpServerConfig(
                        (String) m.get("name"),
                        (String) m.get("url"),
                        (String) m.getOrDefault("auth", "none"),
                        (String) m.getOrDefault("token", null),
                        scopes
                ));
            }
        }
        return Collections.unmodifiableList(servers);
    }

    @SuppressWarnings("unchecked")
    private static List<SkillConfig> parseSkills(String content) {
        Object raw = extractBlock(content, "skills");
        if (!(raw instanceof List<?> list)) return List.of();

        List<SkillConfig> skills = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> m = (Map<String, Object>) map;
                skills.add(new SkillConfig(
                        (String) m.get("name"),
                        (String) m.getOrDefault("description", ""),
                        (String) m.getOrDefault("prompt", "")
                ));
            }
        }
        return Collections.unmodifiableList(skills);
    }

    @SuppressWarnings("unchecked")
    private static LoopConfig parseLoopConfig(String content) {
        Object raw = extractBlock(content, "loop-config");
        if (!(raw instanceof Map<?, ?> map)) return LoopConfig.DEFAULTS;

        Map<String, Object> m = (Map<String, Object>) map;
        return new LoopConfig(
                m.containsKey("max_iterations") ? ((Number) m.get("max_iterations")).intValue() : 50,
                (String) m.getOrDefault("initial_prompt", "Begin working on your task."),
                m.containsKey("loop_interval_seconds") ? ((Number) m.get("loop_interval_seconds")).intValue() : 0
        );
    }
}
