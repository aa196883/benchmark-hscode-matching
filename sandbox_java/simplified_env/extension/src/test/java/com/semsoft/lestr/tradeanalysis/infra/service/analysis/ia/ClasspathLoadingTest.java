package com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia;

import org.junit.jupiter.api.Test;
import com.semsoft.lestr.tradeanalysis.domain.spi.HSCodeService;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import static org.junit.jupiter.api.Assertions.*;

/** Reads packaged import resources from an isolated JAR containing small fixtures. */
class ClasspathLoadingTest {
    private static final String PACKAGE = "com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia";
    private static final String PREFIX = PACKAGE.replace('.', '/') + "/";
    @TempDir Path directory;

    @Test void importReadsResourcesFromJarWithoutFilesystemPaths() throws Exception {
        try (var loader = loader(null, false)) {
            Class<?> type = loader.loadClass(PACKAGE + ".PrecomputedIndexResources");
            assertEquals("jar", type.getResource("h6_2022/manifest.json").getProtocol());
            Object resources = type.getMethod("packaged", HSCodeService.class).invoke(null, RagTestSupport.hsCodeService());
            assertEquals(3, type.getMethod("size").invoke(resources));
            readVectors(type, resources);
        }
    }
    @Test void runtimeResourcesDoNotRequireVectors() throws Exception {
        try (var loader = loader("vectors.jsonl", false)) {
            Class<?> type = loader.loadClass(PACKAGE + ".PrecomputedIndexResources");
            Object resources = type.getMethod("packaged", HSCodeService.class).invoke(null, RagTestSupport.hsCodeService());
            assertEquals(3, type.getMethod("size").invoke(resources));
            var error = assertThrows(InvocationTargetException.class, () -> readVectors(type, resources));
            assertInstanceOf(FileNotFoundException.class, error.getCause());
        }
    }
    @Test void missingManifestNamesTheClasspathResource() throws Exception { missing("manifest.json"); }
    @Test void corruptPackagedIndexIsRejectedDuringImportValidation() throws Exception {
        try (var loader = loader(null, true)) {
            Class<?> type = loader.loadClass(PACKAGE + ".PrecomputedIndexResources");
            Object resources = type.getMethod("packaged", HSCodeService.class).invoke(null, RagTestSupport.hsCodeService());
            var error = assertThrows(InvocationTargetException.class, () -> readVectors(type, resources));
            assertInstanceOf(IllegalArgumentException.class, error.getCause());
            assertTrue(error.getCause().getMessage().contains("checksum"));
        }
    }
    private void readVectors(Class<?> type, Object resources) throws Exception {
        Class<?> consumer = type.getClassLoader().loadClass(PACKAGE + ".PrecomputedIndexResources$VectorConsumer");
        var count = new java.util.concurrent.atomic.AtomicInteger();
        Object callback = java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{consumer},
                (proxy, method, args) -> { count.incrementAndGet(); return null; });
        type.getMethod("readVectors", consumer).invoke(resources, callback);
        assertEquals(3, count.get());
    }
    private void missing(String name) throws Exception {
        try (var loader = loader(name, false)) {
            Class<?> type = loader.loadClass(PACKAGE + ".PrecomputedIndexResources");
            var error = assertThrows(InvocationTargetException.class, () -> type.getMethod("packaged", HSCodeService.class).invoke(null, RagTestSupport.hsCodeService()));
            assertInstanceOf(FileNotFoundException.class, error.getCause());
            assertTrue(error.getCause().getMessage().contains(name));
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
            for (String name : List.of("manifest.json", "vectors.jsonl")) {
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
