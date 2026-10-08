package com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia;

import com.semsoft.lestr.shared.kernel.goods.HSCode;
import com.semsoft.lestr.tradeanalysis.domain.model.*;
import com.semsoft.lestr.tradeanalysis.domain.spi.HSCodeAnalysisService;
import com.semsoft.lestr.tradeanalysis.domain.spi.HSCodeService;
import java.io.*;
import java.nio.file.*;
import java.util.*;

final class RagTestSupport {
    static void fixtures(Path directory) throws IOException {
        for (String name : List.of("manifest.json", "vectors.jsonl")) {
            try (var stream = RagTestSupport.class.getResourceAsStream("/rag-fixtures/" + name)) {
                if (stream == null) throw new FileNotFoundException(name);
                Files.copy(stream, directory.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
    static PrecomputedEmbeddingIndex index(Path directory) throws IOException {
        return index(new PrecomputedIndexResources(directory, hsCodeService()));
    }
    static PrecomputedEmbeddingIndex index(PrecomputedIndexResources resources) throws IOException {
        var store = new dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore<dev.langchain4j.data.segment.TextSegment>();
        try {
            resources.readVectors((row, vector) -> store.add(RagIndexImporter.id(row.hsCode().toDigits()).toString(),
                    dev.langchain4j.data.embedding.Embedding.from(vector),
                    dev.langchain4j.data.segment.TextSegment.from(resources.contextualDescription(row),
                            dev.langchain4j.data.document.Metadata.from("code", row.hsCode().toDigits()))));
        } catch (java.sql.SQLException impossible) { throw new AssertionError(impossible); }
        return new PrecomputedEmbeddingIndex(resources, store);
    }
    static HSCodeService hsCodeService() {
        var nomenclature = new HSNomenclature();
        nomenclature.addChapter("01", HSVersion.V_2022, "Animals");
        nomenclature.addHeading("0101", HSVersion.V_2022, "Horses");
        nomenclature.addSubHeading("010121", HSVersion.V_2022, "Breeding horses");
        nomenclature.addSubHeading("010129", HSVersion.V_2022, "Other horses");
        nomenclature.addSubHeading("010130", HSVersion.V_2022, "Asses");
        nomenclature.addSubHeading("010190", HSVersion.V_2017, "Old code");
        nomenclature.addChapter("99", HSVersion.V_2022, "Special");
        nomenclature.addHeading("9900", HSVersion.V_2022, "Special");
        nomenclature.addSubHeading("990000", HSVersion.V_2022, "Special code");
        return hsCodeService(nomenclature);
    }
    static HSCodeService hsCodeService(HSNomenclature nomenclature) {
        return new HSCodeService() {
            public HSNomenclature getHSNomenclature() { return nomenclature; }
            public List<HSCodeWithDescription> getAllHsCodes() {
                var result = new ArrayList<HSCodeWithDescription>();
                for (var chapter : nomenclature.getChapters()) {
                    result.add(new HSCodeWithDescription(chapter.getNomenclatureCode(), chapter.getDescription()));
                    for (var heading : chapter.getChildren().values()) {
                        result.add(new HSCodeWithDescription(heading.getNomenclatureCode(), heading.getDescription()));
                        for (var leaf : heading.getChildren().values())
                            result.add(new HSCodeWithDescription(leaf.getNomenclatureCode(), leaf.getDescription()));
                    }
                }
                return result;
            }
            public boolean validHSCode(HSCode code, HSVersion version) {
                return nomenclature.getSubHeading(code.toDigits())
                        .map(level -> level.getVersions().contains(version)).orElse(false);
            }
            public Collection<HSCode> convertHSCode(HSCode code, HSVersion from, HSVersion to) {
                throw new UnsupportedOperationException("Not used by RAG fixtures");
            }
        };
    }
    static final HSCodeAnalysisService DELEGATE = new HSCodeAnalysisService() {
        public SearchResult searchFromDescription(String description) { throw new AssertionError("Search must not delegate"); }
        public AnalyseResult analyse(String description, HSCode code) { return new AnalyseResult(Source.Verbatim, description + " " + code.toDigits()); }
        public Source getSource() { return Source.Verbatim; }
    };
}
