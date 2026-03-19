package com.agent00code.cloud;

import tools.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

/**
 * Detects Cloud Foundry VCAP_SERVICES environment and configures Spring AI OpenAI
 * properties from a bound genai service. Supports both single-model and multi-model
 * plans by calling the config_url to discover chat-capable models.
 */
@Configuration
public class VcapGenaiAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(VcapGenaiAutoConfiguration.class);
    private static final String GENAI_TAG = "genai";

    @PostConstruct
    public void configureFromVcap() {
        // Skip VCAP auto-config if LLM_API_KEY is explicitly set (e.g. direct OpenAI)
        String explicitKey = System.getenv("LLM_API_KEY");
        if (explicitKey != null && !explicitKey.isBlank()) {
            log.info("LLM_API_KEY env var is set — skipping VCAP_SERVICES auto-config");
            return;
        }

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
            Map<String, Object> endpoint = credentials.containsKey("endpoint")
                    ? (Map<String, Object>) credentials.get("endpoint")
                    : credentials;

            String instanceName = (String) binding.getOrDefault("instance_name",
                    binding.getOrDefault("name", "<unknown>"));
            String apiBase = (String) endpoint.get("api_base");
            String apiKey = (String) endpoint.get("api_key");
            String configUrl = (String) endpoint.get("config_url");
            String baseUrl = apiBase.replaceAll("/+$", "") + "/openai";

            System.setProperty("spring.ai.openai.base-url", baseUrl);
            System.setProperty("spring.ai.openai.api-key", apiKey);

            // Discover the chat model from the config endpoint
            String modelName = discoverChatModel(configUrl, apiKey);

            if (modelName != null && !modelName.isBlank()) {
                System.setProperty("spring.ai.openai.chat.options.model", modelName);
                log.info("LLM configured from CF service '{}' — base_url={}, model={}",
                        instanceName, baseUrl, modelName);
            } else {
                log.warn("LLM configured from CF service '{}' — base_url={}, NO CHAT MODEL FOUND. " +
                        "Set LLM_MODEL env var.", instanceName, baseUrl);
            }
        } catch (Exception e) {
            log.warn("Failed to parse VCAP_SERVICES for genai binding: {}", e.getMessage());
        }
    }

    /**
     * Calls the genai config_url to discover advertised models and picks
     * the first one with CHAT capability.
     */
    @SuppressWarnings("unchecked")
    private String discoverChatModel(String configUrl, String apiKey) {
        if (configUrl == null || configUrl.isBlank()) return null;

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(configUrl))
                    .header("Authorization", "Bearer " + apiKey)
                    .GET()
                    .build();

            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("Config endpoint returned {}: {}", response.statusCode(), response.body());
                return null;
            }

            JsonMapper mapper = JsonMapper.builder().build();
            Map<String, Object> config = mapper.readValue(response.body(), Map.class);
            List<Map<String, Object>> models = (List<Map<String, Object>>) config.get("advertisedModels");

            if (models == null || models.isEmpty()) {
                log.warn("No advertisedModels in config response");
                return null;
            }

            // Pick the first model with CHAT capability
            for (Map<String, Object> model : models) {
                List<String> capabilities = (List<String>) model.get("capabilities");
                if (capabilities != null && capabilities.contains("CHAT")) {
                    String name = (String) model.get("name");
                    log.info("Discovered chat model '{}' from config endpoint", name);
                    return name;
                }
            }

            // Fallback: first model regardless of capability
            String fallback = (String) models.getFirst().get("name");
            log.info("No CHAT model found, falling back to first model: '{}'", fallback);
            return fallback;
        } catch (Exception e) {
            log.warn("Failed to discover model from config endpoint: {}", e.getMessage());
        }
        return null;
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
