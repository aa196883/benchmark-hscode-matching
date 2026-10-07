package local.lestr.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.semsoft.lestr.shared.kernel.goods.HSCode;
import com.semsoft.lestr.tradeanalysis.domain.model.*;
import com.semsoft.lestr.tradeanalysis.domain.spi.HSCodeAnalysisService;
import com.semsoft.lestr.tradeanalysis.infra.configuration.OpenAIProperties;
import com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia.CompletionHSCodeChatServiceImpl;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class Main {
    private static final ObjectMapper JSON = new ObjectMapper();
    private Main() {}
    public static void main(String[] args) throws Exception {
        String command = args.length == 0 ? "demo" : args[0];
        if (command.equals("rag") || command.equals("rag-replay") || command.equals("rag-import")) {
            if (RagMain.run(args) != 0) System.exit(1);
            return;
        }
        if (command.equals("--help")) {
            System.out.println("demo | search CATALOG RESPONSE_JSON DESCRIPTION | analyse CATALOG RESPONSE_TEXT DESCRIPTION CODE | live-search CATALOG DESCRIPTION | live-analyse CATALOG DESCRIPTION CODE");
            System.out.println("rag-import | rag DESCRIPTION [TOP_K [RETRIEVAL_K]] | rag-replay VECTOR_JSON RESPONSE_JSON DESCRIPTION [TOP_K [RETRIEVAL_K]]");
            return;
        }
        if (command.equals("demo")) {
            if (args.length > 1) throw new IllegalArgumentException("demo takes no arguments");
            try (var input = resource("catalog.jsonl")) {
                var catalogue = CatalogLoader.load(input, HSVersion.V_2022);
                var chat = new ScriptedChatService(textResource("candidates.json"), "Offline fixture: supported by the supplied description.");
                var service = LocalCompletionFactory.scripted(catalogue, HSVersion.V_2022, chat);
                printSearch(service.searchFromDescription("Live breeding horses"));
            }
            return;
        }
        int expected = switch(command) {
            case "search", "live-analyse" -> 4;
            case "analyse" -> 5;
            case "live-search" -> 3;
            default -> throw new IllegalArgumentException("Unknown command; use --help");
        };
        if (args.length != expected) throw new IllegalArgumentException("Wrong argument count; use --help");
        try (var input = Files.newInputStream(Path.of(args[1]))) {
            var catalogue = CatalogLoader.load(input, HSVersion.V_2022);
            HSCodeAnalysisService service;
            boolean live = command.startsWith("live-");
            if (live) {
                String key = System.getenv("OPENAI_API_KEY");
                if (key == null || key.isBlank()) throw new IllegalArgumentException("OPENAI_API_KEY required for live commands");
                service = CompletionHSCodeChatServiceImpl.construct(new OpenAIProperties(key), catalogue, HSVersion.V_2022);
            } else {
                String response = Files.readString(Path.of(args[2]));
                service = LocalCompletionFactory.scripted(catalogue, HSVersion.V_2022, new ScriptedChatService(response, response));
            }
            int descriptionIndex = live ? 2 : 3;
            if (command.endsWith("search")) printSearch(service.searchFromDescription(args[descriptionIndex]));
            else System.out.println(JSON.writeValueAsString(service.analyse(args[descriptionIndex], HSCode.hsCode(args[descriptionIndex + 1]))));
        }
    }
    private static InputStream resource(String name) throws IOException {
        var input = Main.class.getResourceAsStream("/fixtures/" + name);
        if (input == null) throw new FileNotFoundException("Missing classpath fixture: " + name);
        return input;
    }
    private static String textResource(String name) throws IOException {
        try (var input = resource(name)) { return new String(input.readAllBytes(), StandardCharsets.UTF_8); }
    }
    private static void printSearch(SearchResult result) throws IOException {
        // CLI presentation only; the industrial Java return type remains SearchResult.
        var candidates = result.matchingHSCodes().stream().map(c -> Map.of("code", c.HSCode().toDigits(), "score", c.score().score())).toList();
        System.out.println(JSON.writeValueAsString(Map.of("source", result.source().name(), "matchingHSCodes", candidates)));
    }
}
