package com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.semsoft.lestr.tradeanalysis.infra.service.HSCodeServiceImpl;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import java.util.List;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.*;

class VectorizerTest {
    @TempDir Path temporary;

    @Test void writesOrderedJsonLinesAndMatchingManifest() throws Exception {
        EmbeddingModel model = new EmbeddingModel() {
            @Override public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
                assertEquals(2, segments.size());
                assertEquals("Animals; live > Horses, asses, mules and hinnies; live > Horses; live, pure-bred breeding animals",
                        segments.getFirst().text());
                return Response.from(segments.stream().map(segment -> {
                    float[] vector = new float[1536];
                    vector[0] = 1;
                    return Embedding.from(vector);
                }).toList());
            }
        };
        Path output = temporary.resolve("index");
        Vectorizer.generate(new HSCodeServiceImpl(), model, output, 2);
        var json = new ObjectMapper();
        var lines = Files.readAllLines(output.resolve("vectors.jsonl"));
        assertEquals(2, lines.size());
        assertEquals("010121", json.readTree(lines.get(0)).path("code").asText());
        assertEquals("010129", json.readTree(lines.get(1)).path("code").asText());
        assertEquals(1536, json.readTree(lines.get(0)).path("vector").size());
        assertEquals(1.0, json.readTree(lines.get(0)).path("vector").get(0).asDouble());
        var manifest = json.readTree(output.resolve("manifest.json").toFile());
        assertEquals(2, manifest.path("count").asInt());
        assertEquals("text-embedding-3-small", manifest.path("config").path("model").asText());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(output.resolve("vectors.jsonl")))), manifest.path("vectors_sha256").asText());
        assertThrows(FileAlreadyExistsException.class,
                () -> Vectorizer.generate(new HSCodeServiceImpl(), model, output, 2));
    }
}
