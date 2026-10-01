package com.semsoft.lestr.tradeanalysis.infra.service.analysis.rag;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static com.semsoft.lestr.tradeanalysis.infra.service.analysis.rag.RagJson.*;

/** HS 2022 / EN catalogue, including the eligibility rules of the Python POC. */
public final class RagCatalog {
    public record Row(String code, String description, String contextualDescription, boolean eligible) {}
    private final SortedMap<String, Row> rows;
    private final String sha256;

    public RagCatalog(Path path) throws IOException {
        byte[] raw = Files.readAllBytes(path);
        sha256 = sha(raw);
        var loaded = new TreeMap<String, Row>();
        try (var parser = MAPPER.getFactory().createParser(raw)) {
            while (parser.nextToken() != null) {
                JsonNode node = MAPPER.readTree(parser);
                require(node.isObject(), "Catalogue must contain JSON objects");
                String code = text(node, "code"), description = text(node, "description");
                require(!description.isBlank(), "Blank catalogue description");
                require("2022".equals(text(node, "edition")) && "en".equals(text(node, "language")), "Expected HS 2022 / EN catalogue");
                boolean eligible = code.matches("[0-9]{6}") && node.path("level").isIntegralNumber()
                        && node.path("level").intValue() == 6 && node.path("is_candidate").isBoolean()
                        && node.path("is_candidate").booleanValue() && !node.path("is_special").asBoolean(false);
                String context = node.path("contextual_description").isTextual() ? node.get("contextual_description").textValue() : null;
                require(loaded.putIfAbsent(code, new Row(code, description, context, eligible)) == null, "Duplicate catalogue code: " + code);
            }
        }
        require(!loaded.isEmpty(), "Empty catalogue");
        rows = Collections.unmodifiableSortedMap(loaded);
    }
    public String sha256() { return sha256; }
    public Row row(String code) { return rows.get(code); }
    public String rejectionReason(String code) {
        if (code == null || !code.matches("[0-9]{6}")) return "invalid_format";
        Row row = rows.get(code);
        if (row == null) return "unknown_code";
        return row.eligible() ? null : "ineligible_code";
    }
    public List<Row> candidates() {
        var candidates = rows.values().stream().filter(Row::eligible).toList();
        require(!candidates.isEmpty(), "No eligible candidates");
        for (var row : candidates) require(row.contextualDescription() != null && !row.contextualDescription().isBlank(), "Missing contextual description: " + row.code());
        return candidates;
    }
    public String descriptionsSha256() {
        var texts = candidates().stream().map(row -> List.of(row.code(), row.contextualDescription())).toList();
        return sha(encode(texts).getBytes(StandardCharsets.UTF_8));
    }
}
