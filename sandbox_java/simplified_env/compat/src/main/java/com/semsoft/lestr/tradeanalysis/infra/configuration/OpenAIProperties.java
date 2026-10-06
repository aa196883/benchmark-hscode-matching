package com.semsoft.lestr.tradeanalysis.infra.configuration;

import dev.langchain4j.model.openai.OpenAiChatModelName;

public record OpenAIProperties(String apiKey) {

    public String modelName() {
        // Modèle 4o déconseillé car omni (pour le texte/vidéo/image/son)
        //return OpenAiChatModelName.GPT_4_O_MINI.toString();
        //return OpenAiChatModelName.GPT_4_O.toString();
        //return OpenAiChatModelName.GPT_4_1.toString();
        return OpenAiChatModelName.GPT_4_1_MINI.toString();
        //return OpenAiChatModelName.GPT_5_MINI.toString();
    }
}
