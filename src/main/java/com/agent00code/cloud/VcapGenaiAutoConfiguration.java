package com.agent00code.cloud;

import tools.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Map;

/**
 * Detects Cloud Foundry VCAP_SERVICES environment and configures Spring AI OpenAI
 * properties from a bound genai service. When no CF binding exists, Spring AI's
 * standard auto-configuration uses {@code spring.ai.openai.*} properties instead.
 * <p>
 * Matches bindings where {@code label == "genai"} or {@code "genai" in tags},
 * covering both marketplace services and user-provided services created with
 * {@code cf cups -t "genai"}.
 */
@Configuration
@ConditionalOnProperty(name = "VCAP_SERVICES")
public class VcapGenaiAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(VcapGenaiAutoConfiguration.class);
    private static final String GENAI_TAG = "genai";

    @PostConstruct
    public void configureFromVcap() {
        String vcapRaw = System.getenv("VCAP_SERVICES");
        if (vcapRaw == null || vcapRaw.isBlank()) return;

        try {
            Map<String, Object> binding = findGenaiBinding(vcapRaw);
            if (binding == null) {
                log.debug("No genai service binding found in VCAP_SERVICES");
                return;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> credentials = (Map<String, Object>) binding.get("credentials");
            @SuppressWarnings("unchecked")
            Map<String, Object> endpoint = (Map<String, Object>) credentials.get("endpoint");

            String apiBase = (String) endpoint.get("api_base");
            String apiKey = (String) endpoint.get("api_key");
            String instanceName = (String) binding.getOrDefault("instance_name",
                    binding.getOrDefault("name", "<unknown>"));

            String baseUrl = apiBase.replaceAll("/+$", "") + "/openai";

            // Set Spring AI OpenAI properties as system properties so they take precedence
            System.setProperty("spring.ai.openai.base-url", baseUrl);
            System.setProperty("spring.ai.openai.api-key", apiKey);

            log.info("LLM configured from CF service '{}' — base_url={}", instanceName, baseUrl);
        } catch (Exception e) {
            log.warn("Failed to parse VCAP_SERVICES for genai binding: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> findGenaiBinding(String vcapJson) {
        try {
            JsonMapper mapper = JsonMapper.builder().build();
            Map<String, List<Map<String, Object>>> services = mapper.readValue(vcapJson, Map.class);

            for (List<Map<String, Object>> bindings : services.values()) {
                for (Object item : bindings) {
                    if (!(item instanceof Map<?, ?> bindingRaw)) continue;
                    Map<String, Object> binding = (Map<String, Object>) bindingRaw;
                    String label = (String) binding.getOrDefault("label", "");
                    List<String> tags = binding.containsKey("tags") && binding.get("tags") instanceof List<?>
                            ? ((List<?>) binding.get("tags")).stream().map(Object::toString).toList()
                            : List.of();

                    if (GENAI_TAG.equals(label) || tags.contains(GENAI_TAG)) {
                        return binding;
                    }
                }
            }
        } catch (Exception e) {
            // Swallow — caller handles null
        }
        return null;
    }
}
