package com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia;

import com.semsoft.lestr.shared.kernel.goods.HSCode;
import com.semsoft.lestr.tradeanalysis.domain.spi.HSCodeAnalysisService;
import com.semsoft.lestr.tradeanalysis.infra.configuration.OpenAIProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import static org.junit.jupiter.api.Assertions.*;

/** Loads the actual service classes from an isolated JAR containing small resource fixtures. */
class ClasspathLoadingTest {
    private static final String PACKAGE = "com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia";
    private static final String PREFIX = PACKAGE.replace('.', '/') + "/";
    @TempDir Path directory;

    @Test void factoryLoadsResourcesFromJarWithoutPathsOrNetwork() throws Exception {
        try (var loader = loader(null, false)) {
            Class<?> serviceClass = loader.loadClass(PACKAGE + ".RagHSCodeAnalysisService");
            assertEquals("jar", serviceClass.getResource("h6_2022/catalog.jsonl").getProtocol());
            Object index = serviceClass.getMethod("loadIndex").invoke(null);
            assertEquals(3, index.getClass().getMethod("size").invoke(index));
            assertEquals("classpath:/" + PREFIX + "h6_2022/", index.getClass().getMethod("location").invoke(index));
            var service = (HSCodeAnalysisService) serviceClass.getMethod("construct", OpenAIProperties.class, HSCodeAnalysisService.class)
                    .invoke(null, new OpenAIProperties("unused-offline-key"), RagTestSupport.DELEGATE);
            assertEquals("description 010121", service.analyse("description", HSCode.hsCode("010121")).analyse());
            Object detailed = serviceClass.getMethod("searchDetailed", String.class).invoke(service, "");
            assertEquals("error", detailed.getClass().getMethod("status").invoke(detailed));
        }
    }
    @Test void missingCatalogueNamesTheClasspathResource() throws Exception { missing("catalog.jsonl"); }
    @Test void missingManifestNamesTheClasspathResource() throws Exception { missing("manifest.json"); }
    @Test void missingVectorsNameTheClasspathResource() throws Exception { missing("vectors.jsonl"); }
    @Test void corruptPackagedIndexIsRejected() throws Exception {
        try (var loader = loader(null, true)) {
            var type = loader.loadClass(PACKAGE + ".RagHSCodeAnalysisService");
            var error = assertThrows(InvocationTargetException.class, () -> type.getMethod("loadIndex").invoke(null));
            assertInstanceOf(IllegalArgumentException.class, error.getCause());
            assertTrue(error.getCause().getMessage().contains("checksum"));
        }
    }
    private void missing(String name) throws Exception {
        try (var loader = loader(name, false)) {
            var type = loader.loadClass(PACKAGE + ".RagHSCodeAnalysisService");
            var error = assertThrows(InvocationTargetException.class, () -> type.getMethod("construct", OpenAIProperties.class, HSCodeAnalysisService.class)
                    .invoke(null, new OpenAIProperties("unused-offline-key"), RagTestSupport.DELEGATE));
            assertInstanceOf(FileNotFoundException.class, error.getCause());
            assertTrue(error.getCause().getMessage().contains(PREFIX + "h6_2022/" + name));
        }
    }
    private URLClassLoader loader(String excluded, boolean corrupt) throws Exception {
        Path jar = directory.resolve(UUID.randomUUID() + ".jar");
        Path compiled = Path.of(RagHSCodeAnalysisService.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            try (var files = Files.walk(compiled.resolve(PREFIX))) {
                for (var file : files.filter(p -> p.toString().endsWith(".class")).toList())
                    entry(output, compiled.relativize(file).toString().replace(File.separatorChar, '/'), Files.readAllBytes(file));
            }
            for (String name : List.of("rag_v1.txt", "rag_v1.schema.json")) {
                try (var stream = RagHSCodeAnalysisService.class.getResourceAsStream(name)) {
                    entry(output, PREFIX + name, Objects.requireNonNull(stream).readAllBytes());
                }
            }
            for (String name : List.of("catalog.jsonl", "manifest.json", "vectors.jsonl")) {
                if (name.equals(excluded)) continue;
                try (var stream = getClass().getResourceAsStream("/rag-fixtures/" + name)) {
                    byte[] bytes = Objects.requireNonNull(stream).readAllBytes();
                    if (corrupt && name.equals("manifest.json")) {
                        var manifest = (com.fasterxml.jackson.databind.node.ObjectNode) RagJson.MAPPER.readTree(bytes);
                        manifest.put("vectors_sha256", "invalid"); bytes = RagJson.MAPPER.writeValueAsBytes(manifest);
                    }
                    entry(output, PREFIX + "h6_2022/" + name, bytes);
                }
            }
        }
        return new URLClassLoader(new URL[]{jar.toUri().toURL()}, getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null && name.startsWith(PACKAGE + ".")) {
                        try { loaded = findClass(name); } catch (ClassNotFoundException ignored) { }
                    }
                    if (loaded == null) loaded = super.loadClass(name, false);
                    if (resolve) resolveClass(loaded);
                    return loaded;
                }
            }
            @Override public URL getResource(String name) {
                return name.startsWith(PREFIX) ? findResource(name) : super.getResource(name);
            }
        };
    }
    private static void entry(JarOutputStream output, String name, byte[] data) throws IOException {
        output.putNextEntry(new JarEntry(name)); output.write(data); output.closeEntry();
    }
}
