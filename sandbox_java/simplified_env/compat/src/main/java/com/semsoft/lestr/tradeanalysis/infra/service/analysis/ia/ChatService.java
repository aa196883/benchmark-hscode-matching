package com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia;

import com.semsoft.lestr.tradeanalysis.infra.configuration.OpenAIProperties;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.*;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.output.JsonSchemas;

import org.jspecify.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class ChatService {
    public OpenAiChatModel buildChatModel(OpenAIProperties openAIProperties) {
        return OpenAiChatModel.builder()
                .apiKey(openAIProperties.apiKey())
                .modelName(openAIProperties.modelName())
                .seed(0)
                .temperature(0.2)
                //.maxTokens(1500) // GPT4
                .maxCompletionTokens(1500) // GPT5
                .build();
    }

    public ResponseFormat getResponseFormat(Class<?> clazz) {
        Optional<JsonSchema> jsonSchema = JsonSchemas.jsonSchemaFrom(clazz);
        return ResponseFormat.builder()
                .type(ResponseFormatType.JSON)
                .jsonSchema(jsonSchema.orElseThrow())
                .build();
    }

    public String doChat(ChatModel chatModel, String modelName,
                         @Nullable String systemMessage,
                         String userMessage,
                         @Nullable ResponseFormat responseFormat) {
        DefaultChatRequestParameters.Builder<?> builder = ChatRequestParameters.builder();
        builder = builder.modelName(modelName);
        if (responseFormat != null) {
            builder = builder.responseFormat(responseFormat);
        }

        List<ChatMessage> messages = new ArrayList<>();
        if (systemMessage != null) {
            messages.add(SystemMessage.from(systemMessage));
        }
        messages.add(UserMessage.from(userMessage));

        ChatRequest chatRequest = ChatRequest.builder()
                .parameters(builder.build())
                .messages(messages)
                .build();

        ChatResponse chatResponse = chatModel.chat(chatRequest);

        return chatResponse.aiMessage().text();
    }
}
