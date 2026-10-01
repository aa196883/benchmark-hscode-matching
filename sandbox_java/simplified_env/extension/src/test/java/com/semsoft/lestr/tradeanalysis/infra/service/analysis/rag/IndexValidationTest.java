package com.semsoft.lestr.tradeanalysis.infra.service.analysis.rag;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.semsoft.lestr.tradeanalysis.infra.service.analysis.rag.RagJson.*;

class IndexValidationTest {
    @TempDir Path directory;
    @BeforeEach void setup() throws Exception { RagTestSupport.fixtures(directory); }
    @Test void loadsRanksAndLimitsWithoutChangingFiles() throws Exception {
        var before = Files.readAllBytes(directory.resolve("vectors.jsonl"));
        var index = RagTestSupport.index(directory);
        assertEquals(3, index.size()); assertEquals(2, index.dimensions());
        assertNull(index.configuredDimensions());
        assertEquals(List.of("010121", "010129", "010130"), index.search(new double[]{1,0}, 20).stream().map(PrecomputedEmbeddingIndex.Hit::code).toList());
        assertArrayEquals(before, Files.readAllBytes(directory.resolve("vectors.jsonl")));
        assertThrows(IllegalArgumentException.class, () -> index.search(new double[]{1}, 2));
        assertThrows(IllegalArgumentException.class, () -> index.search(new double[]{0,0}, 2));
        assertThrows(IllegalArgumentException.class, () -> index.search(new double[]{Double.NaN,0}, 2));
        assertThrows(IllegalArgumentException.class, () -> index.search(new double[]{1,0}, 0));
    }
    @Test void rejectsCatalogueChangesButAcceptsFormattingChanges() throws Exception {
        var path = directory.resolve("catalog.jsonl");
        Files.writeString(path, "  "+Files.readString(path)+"  ");
        assertEquals(3, RagTestSupport.index(directory).size());
        Files.writeString(path, Files.readString(path).replace("Animals >", "Different >"));
        assertThrows(IllegalArgumentException.class, () -> RagTestSupport.index(directory));
    }
    @Test void rejectsTamperingTruncationOrderAndDuplicates() throws Exception {
        var path = directory.resolve("vectors.jsonl"); var lines = Files.readAllLines(path);
        for (var content : List.of(lines.getFirst()+"\n", String.join("\n", List.of(lines.get(1), lines.get(0), lines.get(2)))+"\n",
                String.join("\n", List.of(lines.get(0), lines.get(0), lines.get(2)))+"\n", String.join("\n", lines)+"\n ")) {
            Files.writeString(path, content);
            assertThrows(IllegalArgumentException.class, () -> RagTestSupport.index(directory));
        }
    }
    @Test void rejectsWrongModelScopeCountAndDimensions() throws Exception {
        var path = directory.resolve("manifest.json"); var initial = Files.readString(path);
        for (String field : List.of("schema_version", "edition", "language", "text_field", "provider", "count", "dimensions", "normalization", "vectors_sha256")) {
            var manifest = (ObjectNode) parse(initial); manifest.put(field, "invalid");
            Files.writeString(path, encode(manifest));
            assertThrows(IllegalArgumentException.class, () -> RagTestSupport.index(directory), field);
        }
        var manifest = (ObjectNode) parse(initial);
        ((ObjectNode) manifest.get("config")).put("model", "text-embedding-3-large");
        Files.writeString(path, encode(manifest));
        assertThrows(IllegalArgumentException.class, () -> RagTestSupport.index(directory));
        manifest = (ObjectNode) parse(initial); ((ObjectNode) manifest.get("config")).put("dimensions", 3);
        Files.writeString(path, encode(manifest));
        assertThrows(IllegalArgumentException.class, () -> RagTestSupport.index(directory));
    }
    @Test void rejectsZeroNonNumericAndOverflowVectorsEvenWithUpdatedChecksum() throws Exception {
        for (String vector : List.of("[0,0]", "[1]", "[1,true]", "[1e100,0]", "[1e-100,0]")) {
            RagTestSupport.fixtures(directory);
            var path = directory.resolve("vectors.jsonl"); var lines = Files.readAllLines(path);
            var row = (ObjectNode) parse(lines.getFirst()); row.set("vector", parse(vector)); lines.set(0, encode(row));
            Files.write(path, lines);
            updateChecksum();
            assertThrows(IllegalArgumentException.class, () -> RagTestSupport.index(directory), vector);
        }
    }
    @Test void exactTiesUseCodeOrderAndManifestCannotMutateIndex() throws Exception {
        var path = directory.resolve("vectors.jsonl"); var lines = Files.readAllLines(path);
        var row = (ObjectNode) parse(lines.get(1)); row.set("vector", parse("[1,0]")); lines.set(1, encode(row));
        Files.write(path, lines); updateChecksum();
        var index = RagTestSupport.index(directory);
        assertEquals(List.of("010121", "010129"), index.search(new double[]{1,0},2).stream().map(PrecomputedEmbeddingIndex.Hit::code).toList());
        ((ObjectNode) index.manifest()).put("count", 99);
        assertEquals(3, index.manifest().get("count").intValue());
    }
    @Test void rejectsEmptyCatalogueDuplicateCodesWrongLanguageAndMissingContext() throws Exception {
        var path = directory.resolve("catalog.jsonl"); String original = Files.readString(path);
        for (String content : List.of("", original + original, original.replace("\"en\"", "\"fr\""), original.replace("contextual_description", "unused"))) {
            Files.writeString(path, content);
            assertThrows(IllegalArgumentException.class, () -> RagTestSupport.index(directory));
        }
    }
    private void updateChecksum() throws Exception {
        var path = directory.resolve("manifest.json"); var manifest = (ObjectNode) parse(Files.readString(path));
        manifest.put("vectors_sha256", sha(Files.readAllBytes(directory.resolve("vectors.jsonl"))));
        Files.writeString(path, encode(manifest));
    }
}
