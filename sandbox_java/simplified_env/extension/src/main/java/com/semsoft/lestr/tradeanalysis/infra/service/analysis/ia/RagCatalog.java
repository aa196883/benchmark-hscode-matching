package com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia.RagJson.*;

/** HS 2022 / EN catalogue with candidate eligibility checks. */
public final class RagCatalog {
    public record Row(String code, String description, String contextualDescription, boolean eligible) {}
    private final SortedMap<String, Row> rows;

    public RagCatalog(Path path) throws IOException {
        this(Files.newInputStream(path));
    }
    /** Takes ownership of the stream and closes it, including when validation fails. */
    public RagCatalog(InputStream input) throws IOException {
        byte[] raw;
        try (input) { raw = input.readAllBytes(); }
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
    public Row row(String code) { return rows.get(code); }
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
