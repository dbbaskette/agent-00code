package com.agent00code.cloud;

import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VcapGenaiAutoConfigurationTest {

    private static final Map<String, Object> MARKETPLACE_VCAP = Map.of(
            "genai", java.util.List.of(Map.of(
                    "label", "genai",
                    "tags", java.util.List.of("genai", "llm"),
                    "instance_name", "gpt-oss",
                    "name", "gpt-oss",
                    "credentials", Map.of(
                            "endpoint", Map.of(
                                    "api_base", "https://genai-proxy.example.com/my-model",
                                    "api_key", "test-api-key",
                                    "config_url", "https://genai-proxy.example.com/my-model/config/v1/endpoint"
                            )
                    )
            ))
    );

    private static final Map<String, Object> USER_PROVIDED_VCAP = Map.of(
            "user-provided", java.util.List.of(Map.of(
                    "label", "user-provided",
                    "tags", java.util.List.of("genai", "llm"),
                    "instance_name", "my-llm",
                    "name", "my-llm",
                    "credentials", Map.of(
                            "endpoint", Map.of(
                                    "api_base", "https://my-llm.example.com/v1",
                                    "api_key", "cups-api-key",
                                    "config_url", "https://my-llm.example.com/v1/config"
                            )
                    )
            ))
    );

    private String toJson(Map<String, Object> map) {
        try {
            return JsonMapper.builder().build().writeValueAsString(map);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void findGenaiBindingByLabel() {
        Map<String, Object> binding = VcapGenaiAutoConfiguration.findGenaiBinding(toJson(MARKETPLACE_VCAP));
        assertNotNull(binding);
        assertEquals("genai", binding.get("label"));
    }

    @Test
    void findGenaiBindingUserProvidedByTag() {
        Map<String, Object> binding = VcapGenaiAutoConfiguration.findGenaiBinding(toJson(USER_PROVIDED_VCAP));
        assertNotNull(binding);
        assertEquals("user-provided", binding.get("label"));
    }

    @Test
    void findGenaiBindingNoMatch() {
        String vcap = toJson(Map.of(
                "p-redis", java.util.List.of(Map.of(
                        "label", "p-redis",
                        "tags", java.util.List.of("redis"),
                        "credentials", Map.of()
                ))
        ));
        assertNull(VcapGenaiAutoConfiguration.findGenaiBinding(vcap));
    }

    @Test
    void findGenaiBindingInvalidJson() {
        assertNull(VcapGenaiAutoConfiguration.findGenaiBinding("not-json"));
    }

    @Test
    void findGenaiBindingEmpty() {
        assertNull(VcapGenaiAutoConfiguration.findGenaiBinding("{}"));
    }
}
