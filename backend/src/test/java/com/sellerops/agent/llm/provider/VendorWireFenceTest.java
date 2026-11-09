package com.sellerops.agent.llm.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>Only the shared model connection speaks a vendor's wire</b> (owner, 2026-10-29: 「업체별 요청·응답 처리를 분리한다」).
 * A capability keeps its prompt, schema, parser, flag and key; where a request goes and how a vendor authenticates live in
 * {@code agent/llm/provider} alone, so adding a vendor is one new wire, not an edit in every capability.
 *
 * <p>Reads every main source file recursively, comments stripped. The exceptions are named with their reason:
 * <ul>
 *   <li>{@code OutboundGate} — the egress allow-list names the hosts it allows;</li>
 *   <li>{@code ImageFetchPolicy}, {@code SentryScrub} — header deny-lists name the headers they strip;</li>
 *   <li>{@code ApiTriageClassifier} — the old review classifier: its request bytes are part of a sealed calibration
 *   record (RUBRIC v2 §8.6, a changed request needs a new version), and the shared judgement replaces it.</li>
 * </ul>
 */
class VendorWireFenceTest {

    private static final Path MAIN = Path.of("src", "main", "java", "com", "sellerops");
    private static final List<String> VENDOR_MARKERS = List.of("api.openai.com", "api.anthropic.com",
            "\"anthropic-version\"", "\"x-api-key\"");
    private static final Map<String, String> EXCEPTIONS = Map.of(
            "OutboundGate.java", "the egress allow-list",
            "ImageFetchPolicy.java", "a header deny-list",
            "SentryScrub.java", "a header deny-list",
            "ApiTriageClassifier.java", "sealed calibration bytes (RUBRIC v2 §8.6)");

    /**
     * Block comments, and line comments not preceded by {@code :} — a URL literal's {@code https://} is code, and a
     * stripper that removed it would erase exactly what this fence looks for (it did, on its first run).
     */
    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)(?<!:)//.*$", "");
    }

    static List<String> offenders(Path root) throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String name = file.getFileName().toString();
                if (file.toString().contains("/agent/llm/provider/") || EXCEPTIONS.containsKey(name)) {
                    continue;
                }
                String code = stripComments(Files.readString(file));
                for (String marker : VENDOR_MARKERS) {
                    if (code.contains(marker)) {
                        found.add(name + " names " + marker);
                    }
                }
            }
        }
        return found;
    }

    @Test
    @DisplayName("vendor hosts and vendor auth headers appear only in the provider package and the named exceptions")
    void onlyTheProviderPackageSpeaksAVendorWire() throws IOException {
        assertThat(offenders(MAIN)).isEmpty();
    }

    @Test
    @DisplayName("the fence reads something: the provider package itself names every marker, recursively found")
    void theFenceIsNotReadingAnEmptyTree() throws IOException {
        StringBuilder provider = new StringBuilder();
        try (Stream<Path> files = Files.walk(MAIN.resolve(Path.of("agent", "llm", "provider")))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                provider.append(stripComments(Files.readString(file)));
            }
        }
        for (String marker : VENDOR_MARKERS) {
            assertThat(provider.toString()).as(marker).contains(marker);
        }
        long javaFiles;
        try (Stream<Path> files = Files.walk(MAIN)) {
            javaFiles = files.filter(p -> p.toString().endsWith(".java")).count();
        }
        assertThat(javaFiles).as("the walk is recursive").isGreaterThan(1000);
    }
}
