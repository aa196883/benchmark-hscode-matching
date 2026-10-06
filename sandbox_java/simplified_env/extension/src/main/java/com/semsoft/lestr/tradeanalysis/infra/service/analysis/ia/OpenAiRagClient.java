package com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import static com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia.RagJson.*;

/** Responses and embeddings HTTP client; no implicit retries. */
public final class OpenAiRagClient implements RagGenerationClient, RagEmbeddingClient {
    public record Config(String model, int maxOutputTokens, Double temperature, String reasoningEffort, Duration timeout) {
        public Config {
            require(model != null && !model.isBlank() && maxOutputTokens > 0 && timeout != null && !timeout.isNegative() && !timeout.isZero(), "Invalid generation config");
            require(temperature == null || (Double.isFinite(temperature) && temperature >= 0 && temperature <= 2), "Invalid temperature");
        }
        public static Config defaults() { return new Config("gpt-4.1-mini", 2048, null, null, Duration.ofSeconds(60)); }
    }
    private final String apiKey;
    private final Config config;
    private final Integer dimensions;
    private final HttpClient http;
    private final URI baseUri;
    public OpenAiRagClient(String apiKey, Integer dimensions, Config config) {
        this(apiKey, dimensions, config, HttpClient.newBuilder().connectTimeout(config.timeout()).build(), URI.create("https://api.openai.com/v1/"));
    }
    /** Injectable transport/base URI for the industrial network configuration and local HTTP tests. */
    public OpenAiRagClient(String apiKey, Integer dimensions, Config config, HttpClient http, URI baseUri) {
        require(dimensions == null || dimensions > 0, "Invalid embedding dimensions");
        require(baseUri != null && baseUri.isAbsolute() && baseUri.toString().endsWith("/"), "Base URI must be absolute and end with /");
        this.apiKey = apiKey; this.dimensions = dimensions; this.config = java.util.Objects.requireNonNull(config);
        this.http = java.util.Objects.requireNonNull(http); this.baseUri = baseUri;
    }
    @Override public Integer dimensions() { return dimensions; }
    @Override public JsonNode configuration() {
        var node = MAPPER.createObjectNode().put("model", config.model()).put("max_output_tokens", config.maxOutputTokens())
                .put("timeout", config.timeout().toMillis() / 1000d);
        if (config.temperature() == null) node.putNull("temperature"); else node.put("temperature", config.temperature());
        if (config.reasoningEffort() == null) node.putNull("reasoning_effort"); else node.put("reasoning_effort", config.reasoningEffort());
        return node;
    }
    @Override public JsonNode generate(String instructions, String input, JsonNode schema) {
        var body = MAPPER.createObjectNode().put("model", config.model()).put("instructions", instructions)
                .put("input", input).put("max_output_tokens", config.maxOutputTokens()).put("store", false);
        body.putObject("text").putObject("format").put("type", "json_schema").put("name", "hs_prediction").put("strict", true).set("schema", schema);
        if (config.temperature() != null) body.put("temperature", config.temperature());
        if (config.reasoningEffort() != null) body.putObject("reasoning").put("effort", config.reasoningEffort());
        return post("responses", body);
    }
    @Override public Response embed(String description) {
        require(description != null && !description.isBlank(), "Non-empty query required");
        var body = MAPPER.createObjectNode().put("model", model()).put("encoding_format", "float");
        body.putArray("input").add(description);
        if (dimensions != null) body.put("dimensions", dimensions);
        JsonNode raw = post("embeddings", body);
        try {
            JsonNode data = raw.path("data");
            require(data.isArray() && data.size() == 1 && data.get(0).path("index").isInt() && data.get(0).get("index").intValue() == 0, "Invalid embedding result index");
            JsonNode array = data.get(0).path("embedding");
            double[] vector = PrecomputedEmbeddingIndex.vector(array, dimensions == null ? array.size() : dimensions);
            String model = text(raw, "model"); require(!model.isBlank(), "Missing response model");
            return new Response(vector, model, raw.has("usage") ? raw.get("usage") : MAPPER.createObjectNode());
        } catch (IllegalArgumentException e) {
            throw new RagProviderException("invalid_response", "Invalid embedding response", null, null);
        }
    }
    private JsonNode post(String endpoint, JsonNode body) {
        if (apiKey == null || apiKey.isBlank() || apiKey.strip().equals("YOUR_OPENAI_API_KEY_HERE"))
            throw new RagProviderException("configuration", "OPENAI_API_KEY required", null, null);
        var request = HttpRequest.newBuilder(baseUri.resolve(endpoint)).timeout(config.timeout())
                .header("Authorization", "Bearer " + apiKey).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(encode(body))).build();
        try {
            var response = http.send(request, HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new RagProviderException("http_error", "OpenAI request failed; check model, parameters and access", response.statusCode(), response.headers().firstValue("x-request-id").orElse(null));
            try {
                JsonNode result = parse(response.body());
                require(result != null && result.isObject(), "Expected JSON object");
                return result;
            } catch (IllegalArgumentException e) { throw new RagProviderException("invalid_response", "HTTP response is not a JSON object", null, null); }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RagProviderException("connection_error", "OpenAI request interrupted", null, null);
        } catch (IOException e) {
            throw new RagProviderException("connection_error", "OpenAI connection failed or timed out", null, null);
        }
    }
}
