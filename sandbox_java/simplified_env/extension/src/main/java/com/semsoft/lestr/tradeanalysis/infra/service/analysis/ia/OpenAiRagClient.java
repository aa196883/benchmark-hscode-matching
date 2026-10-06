package com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia;

import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.*;
import dev.langchain4j.model.chat.request.json.JsonRawSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiResponsesChatModel;

/** Thin LangChain4j adapter: generated JSON text and query vector only. */
public final class OpenAiRagClient implements RagGenerationClient, RagEmbeddingClient {
    public record Config(String model, int maxOutputTokens, Double temperature, String reasoningEffort) {
        public static Config defaults() { return new Config("gpt-4.1-mini", 2048, null, null); }
    }
    private final ChatModel generation;
    private final EmbeddingModel embeddings;
    private final Integer dimensions;

    public OpenAiRagClient(String apiKey, Integer dimensions, Config config) {
        this(OpenAiResponsesChatModel.builder()
                        .apiKey(apiKey).modelName(config.model()).maxOutputTokens(config.maxOutputTokens())
                        .temperature(config.temperature()).reasoningEffort(config.reasoningEffort())
                        .store(false).strictJsonSchema(true).build(),
                OpenAiEmbeddingModel.builder().apiKey(apiKey).modelName(PrecomputedEmbeddingIndex.MODEL)
                        .dimensions(dimensions).maxRetries(0).build(), dimensions);
    }
    /** Models can be configured externally or replaced by test doubles. */
    public OpenAiRagClient(ChatModel generation, EmbeddingModel embeddings, Integer dimensions) {
        this.generation = generation;
        this.embeddings = embeddings;
        this.dimensions = dimensions;
    }
    @Override public Integer dimensions() { return dimensions; }

    @Override public String generate(String instructions, String input, JsonNode schema) {
        var format = ResponseFormat.builder().type(ResponseFormatType.JSON)
                .jsonSchema(JsonSchema.builder().name("hs_prediction")
                        .rootElement(JsonRawSchema.from(schema.toString())).build()).build();
        var request = ChatRequest.builder()
                .messages(SystemMessage.from(instructions), UserMessage.from(input))
                .parameters(ChatRequestParameters.builder().responseFormat(format).build()).build();
        return generation.chat(request).aiMessage().text();
    }
    @Override public double[] embed(String description) {
        float[] vector = embeddings.embed(description).content().vector();
        double[] result = new double[vector.length];
        for (int i = 0; i < vector.length; i++) result[i] = vector[i];
        return result;
    }
}
