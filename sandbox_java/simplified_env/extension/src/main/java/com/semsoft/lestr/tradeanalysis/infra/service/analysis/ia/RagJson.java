package com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia;

import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.nio.file.*;
import java.security.*;
import java.util.*;

final class RagJson {
    static final ObjectMapper MAPPER = new ObjectMapper();
    private RagJson() {}
    static JsonNode parse(String text) {
        try { return MAPPER.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(text); }
        catch (IOException | IllegalArgumentException e) { throw new IllegalArgumentException("Invalid JSON response", e); }
    }
    static String encode(Object value) {
        try { return MAPPER.writeValueAsString(value); }
        catch (IOException e) { throw new IllegalArgumentException("Cannot serialize JSON", e); }
    }
    static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
    static String text(JsonNode node, String field) {
        require(node.path(field).isTextual(), "Missing text field: " + field);
        return node.get(field).textValue();
    }
    static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    static String sha(byte[] bytes) { return HexFormat.of().formatHex(digest().digest(bytes)); }
    static double seconds(long start) { return (System.nanoTime() - start) / 1_000_000_000d; }
}
