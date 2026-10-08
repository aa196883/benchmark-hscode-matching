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
    @Test void indexStreamsCloseAfterSuccess() throws Exception {
        var streams = new ArrayList<TrackedStream>();
        var hsCodeService = RagTestSupport.hsCodeService();
        var index = RagTestSupport.index(new PrecomputedIndexResources(name -> {
            var stream = new TrackedStream(fixture(name)); streams.add(stream); return stream;
        }, hsCodeService));
        assertEquals(3, index.size()); assertEquals(2, streams.size());
        assertTrue(streams.stream().allMatch(stream -> stream.closed));
    }
    @Test void vectorStreamClosesWhenValidationFails() throws Exception {
        var streams = new ArrayList<TrackedStream>();
        var hsCodeService = RagTestSupport.hsCodeService();
        assertThrows(IllegalArgumentException.class, () -> RagTestSupport.index(new PrecomputedIndexResources(name -> {
            byte[] bytes = name.equals("vectors.jsonl") ? "invalid".getBytes(java.nio.charset.StandardCharsets.UTF_8) : fixture(name);
            var stream = new TrackedStream(bytes); streams.add(stream); return stream;
        }, hsCodeService)));
        assertEquals(2, streams.size()); assertTrue(streams.stream().allMatch(stream -> stream.closed));
    }
}
