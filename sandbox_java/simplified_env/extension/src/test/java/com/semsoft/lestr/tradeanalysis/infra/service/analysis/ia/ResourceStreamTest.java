package com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ResourceStreamTest {
    static final class TrackedStream extends ByteArrayInputStream {
        boolean closed;
        TrackedStream(byte[] bytes) { super(bytes); }
        @Override public void close() throws IOException { closed = true; super.close(); }
    }
    private byte[] fixture(String name) throws IOException {
        try (var stream = getClass().getResourceAsStream("/rag-fixtures/" + name)) {
            return Objects.requireNonNull(stream).readAllBytes();
        }
    }
    @Test void catalogueStreamClosesAfterSuccessAndFailure() throws Exception {
        var good = new TrackedStream(fixture("catalog.jsonl"));
        assertEquals(3, new RagCatalog(good).candidates().size()); assertTrue(good.closed);
        var bad = new TrackedStream("invalid".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> new RagCatalog(bad)); assertTrue(bad.closed);
    }
    @Test void indexStreamsCloseAfterSuccess() throws Exception {
        var streams = new ArrayList<TrackedStream>();
        var catalog = new RagCatalog(new ByteArrayInputStream(fixture("catalog.jsonl")));
        var index = new PrecomputedEmbeddingIndex(name -> {
            var stream = new TrackedStream(fixture(name)); streams.add(stream); return stream;
        }, catalog);
        assertEquals(3, index.size()); assertEquals(2, streams.size());
        assertTrue(streams.stream().allMatch(stream -> stream.closed));
    }
    @Test void vectorStreamClosesWhenValidationFails() throws Exception {
        var streams = new ArrayList<TrackedStream>();
        var catalog = new RagCatalog(new ByteArrayInputStream(fixture("catalog.jsonl")));
        assertThrows(IllegalArgumentException.class, () -> new PrecomputedEmbeddingIndex(name -> {
            byte[] bytes = name.equals("vectors.jsonl") ? "invalid".getBytes(java.nio.charset.StandardCharsets.UTF_8) : fixture(name);
            var stream = new TrackedStream(bytes); streams.add(stream); return stream;
        }, catalog));
        assertEquals(2, streams.size()); assertTrue(streams.stream().allMatch(stream -> stream.closed));
    }
}
