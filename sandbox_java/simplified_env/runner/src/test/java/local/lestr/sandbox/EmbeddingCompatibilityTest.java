package local.lestr.sandbox;

import com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia.EmbeddingService;
import com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia.model.EmbeddingModelType;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EmbeddingCompatibilityTest {
    @Test void inMemoryStoreRoundTripPreservesMetadataAndNearestNeighbor() {
        var store = new InMemoryEmbeddingStore<TextSegment>();
        store.add(Embedding.from(new float[]{1, 0}), TextSegment.from("Breeding horses", new Metadata().put(EmbeddingService.HS_CODE_METADATA_KEY, "0101.21")));
        store.add(Embedding.from(new float[]{0, 1}), TextSegment.from("Other horses", new Metadata().put(EmbeddingService.HS_CODE_METADATA_KEY, "0101.29")));
        var loaded = InMemoryEmbeddingStore.<TextSegment>fromJson(store.serializeToJson());
        var hits = loaded.search(EmbeddingSearchRequest.builder().queryEmbedding(Embedding.from(new float[]{1, 0})).maxResults(1).build()).matches();
        assertEquals(1, hits.size());
        assertEquals("0101.21", hits.getFirst().embedded().metadata().getString("hsCode"));
        assertEquals("Breeding horses", hits.getFirst().embedded().text());
    }
    @Test void missingIndustrialClasspathStoreFailsRatherThanRebuilding() {
        assertThrows(NullPointerException.class, () -> new EmbeddingService().loadStore(EmbeddingModelType.small));
    }
}
