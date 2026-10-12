package com.recon.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** any endpoint that speaks the OpenAI embeddings format and supports the dimensions field */
@Component
@ConditionalOnProperty(name = "recon.embedding.mode", havingValue = "openai")
public class RemoteEmbedder implements Embedder {
    private final RestClient http;
    private final String url;
    private final String apiKey;
    private final String model;

    public RemoteEmbedder(@Value("${recon.embedding.api-url}") String url,
                          @Value("${recon.embedding.api-key}") String apiKey,
                          @Value("${recon.embedding.model}") String model) {
        this.url = url;
        this.apiKey = apiKey;
        this.model = model;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(30000);
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        List<float[]> out = new ArrayList<>();
        for (int i = 0; i < texts.size(); i += 64) {
            List<String> chunk = texts.subList(i, Math.min(texts.size(), i + 64));
            JsonNode res = http.post()
                    .uri(url)
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("model", model, "input", chunk, "dimensions", DIMS))
                    .retrieve()
                    .body(JsonNode.class);
            if (res == null) {
                throw new IllegalStateException("Embedding service returned nothing");
            }
            for (JsonNode item : res.path("data")) {
                JsonNode arr = item.path("embedding");
                if (arr.size() != DIMS) {
                    throw new IllegalStateException("Expected " + DIMS + " dimensions but got " + arr.size());
                }
                float[] v = new float[DIMS];
                for (int j = 0; j < DIMS; j++) v[j] = (float) arr.get(j).asDouble();
                out.add(VectorUtil.unitLength(v));
            }
        }
        return out;
    }
}
