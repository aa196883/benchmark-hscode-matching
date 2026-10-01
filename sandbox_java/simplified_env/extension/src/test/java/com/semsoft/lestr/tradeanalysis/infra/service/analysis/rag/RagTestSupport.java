package com.semsoft.lestr.tradeanalysis.infra.service.analysis.rag;

import com.semsoft.lestr.shared.kernel.goods.HSCode;
import com.semsoft.lestr.tradeanalysis.domain.model.*;
import com.semsoft.lestr.tradeanalysis.domain.spi.HSCodeAnalysisService;
import java.io.*;
import java.nio.file.*;
import java.util.List;

final class RagTestSupport {
    static void fixtures(Path directory) throws IOException {
        for (String name : List.of("catalog.jsonl", "manifest.json", "vectors.jsonl")) {
            try (var stream = RagTestSupport.class.getResourceAsStream("/rag-fixtures/" + name)) {
                if (stream == null) throw new FileNotFoundException(name);
                Files.copy(stream, directory.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
    static PrecomputedEmbeddingIndex index(Path directory) throws IOException {
        return new PrecomputedEmbeddingIndex(directory, new RagCatalog(directory.resolve("catalog.jsonl")));
    }
    static final HSCodeAnalysisService DELEGATE = new HSCodeAnalysisService() {
        public SearchResult searchFromDescription(String description) { throw new AssertionError("Search must not delegate"); }
        public AnalyseResult analyse(String description, HSCode code) { return new AnalyseResult(Source.Verbatim, description + " " + code.toDigits()); }
        public Source getSource() { return Source.Verbatim; }
    };
}
