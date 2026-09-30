package com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia;

import com.semsoft.lestr.tradeanalysis.domain.model.HSCodeWithDescription;
import com.semsoft.lestr.tradeanalysis.domain.spi.HSCodeService;
import com.semsoft.lestr.tradeanalysis.infra.configuration.OpenAIProperties;
import com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia.model.EmbeddingModelType;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

@Slf4j
public class EmbeddingService {
    public static final String HS_CODE_METADATA_KEY = "hsCode";

    public OpenAiEmbeddingModel createEmbeddingModel(OpenAIProperties openAIProperties, EmbeddingModelType embeddingModelType) {
        return OpenAiEmbeddingModel.builder()
                .apiKey(openAIProperties.apiKey())
                .modelName(toModelName(embeddingModelType))
                .build();
    }

    private String toModelName(EmbeddingModelType embeddingModelType) {
        return switch (embeddingModelType) {
            case small -> "text-embedding-3-small";
            case large -> "text-embedding-3-large";
        };
    }

    public InMemoryEmbeddingStore<TextSegment> indexStore(HSCodeService hsCodeService, OpenAiEmbeddingModel embeddingModel) {
        InMemoryEmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();
        Instant start = Instant.now();
        int i = 0;
        List<HSCodeWithDescription> allHsCodes = hsCodeService.getAllHsCodes();
        for (HSCodeWithDescription hsCodeWithDescription : allHsCodes) {
            log.info("{}/{}", i++, allHsCodes.size());
            Embedding embedding = embeddingModel.embed(hsCodeWithDescription.description()).content();
            store.add(embedding, TextSegment.from(hsCodeWithDescription.description(),
                    new Metadata().put(HS_CODE_METADATA_KEY, hsCodeWithDescription.hsCode().toString())));
        }
        log.info("Indexing in {}s.", Duration.between(start, Instant.now()).toSeconds());
        return store;
    }

    public void saveStore(InMemoryEmbeddingStore<TextSegment> store, EmbeddingModelType embeddingModelType) throws IOException {
        try (PrintWriter pw = new PrintWriter(new FileOutputStream("/tmp/store-" + embeddingModelType.name() + ".ser"))) {
            pw.write(store.serializeToJson());
        }
    }

    public InMemoryEmbeddingStore<TextSegment> loadStore(EmbeddingModelType embeddingModelType) throws IOException {
        try (InputStream resourceAsStream = this.getClass().getResourceAsStream("store-" + embeddingModelType.name() + ".ser")) {
            return InMemoryEmbeddingStore.fromJson(IOUtils.toString(Objects.requireNonNull(resourceAsStream), StandardCharsets.UTF_8));
        }
    }
}
