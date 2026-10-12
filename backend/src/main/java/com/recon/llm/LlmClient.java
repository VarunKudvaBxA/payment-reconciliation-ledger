package com.recon.llm;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class LlmClient {
    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    private final RestClient http;
    private final String url;
    private final String apiKey;
    private final String model;

    public LlmClient(@Value("${recon.llm.url}") String url,
                     @Value("${recon.llm.api-key}") String apiKey,
                     @Value("${recon.llm.model}") String model) {
        this.url = url;
        this.apiKey = apiKey;
        this.model = model;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(25000);
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    public boolean enabled() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** empty when there is no key or the call fails, callers fall back to plain rules */
    public Optional<String> complete(String system, String user, int maxTokens) {
        if (!enabled()) {
            return Optional.empty();
        }
        try {
            JsonNode res = http.post()
                    .uri(url)
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "model", model,
                            "max_tokens", maxTokens,
                            "system", system,
                            "messages", List.of(Map.of("role", "user", "content", user))))
                    .retrieve()
                    .body(JsonNode.class);
            String text = res == null ? "" : res.path("content").path(0).path("text").asText("").trim();
            return text.isEmpty() ? Optional.empty() : Optional.of(text);
        } catch (Exception e) {
            log.warn("LLM call failed: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
