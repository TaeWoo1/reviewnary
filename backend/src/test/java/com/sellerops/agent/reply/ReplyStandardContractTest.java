package com.sellerops.agent.reply;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>The backend reads the same reply standard the contract declares</b> (owner, 2026-10-28) — byte for byte. The
 * backend renders it into the reply agent's model steps; a copy that drifted from {@code contracts/reply-standard/v1}
 * would judge and draft by a standard nobody decided.
 */
class ReplyStandardContractTest {

    private static Path contract(String version) {
        Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 4 && dir != null; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve("contracts/reply-standard/" + version + "/standard.json");
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("contracts/reply-standard/" + version + "/standard.json not found from user.dir="
                + System.getProperty("user.dir"));
    }

    private static byte[] mirror(String version) throws Exception {
        try (InputStream in = ReplyStandardContractTest.class.getResourceAsStream(
                "/reply-standard/" + version + "/standard.json")) {
            assertThat(in).as("the backend's copy of the reply standard is on the classpath").isNotNull();
            return in.readAllBytes();
        }
    }

    private static byte[] mirror() throws Exception {
        return mirror(ReplyStandard.IN_USE);
    }

    @Test
    @DisplayName("the backend's copies are the contracts, byte for byte — the one in use and every one before it")
    void theMirrorIsTheContract() throws Exception {
        for (String version : new String[] {"v1", "v2"}) {
            assertThat(mirror(version)).as(version).isEqualTo(Files.readAllBytes(contract(version)));
        }
    }

    @Test
    @DisplayName("ten categories in priority order, S·L·X hand to a person, and every rule has a unique id")
    void theStandardsShape() throws Exception {
        JsonNode root = new ObjectMapper().readTree(mirror());
        assertThat(root.path("version").asText()).isEqualTo("reply-standard/v2");
        java.util.List<String> codes = new java.util.ArrayList<>();
        java.util.List<String> handoff = new java.util.ArrayList<>();
        java.util.List<String> needs = new java.util.ArrayList<>();
        root.path("categories").forEach(c -> {
            codes.add(c.path("code").asText());
            if (c.path("handoff").asBoolean()) handoff.add(c.path("code").asText());
            if (c.path("needsResponse").asBoolean()) needs.add(c.path("code").asText());
        });
        java.util.List<String> priority = new java.util.ArrayList<>();
        root.path("priority").forEach(p -> priority.add(p.asText()));
        assertThat(priority).containsExactly("S", "L", "R", "Q", "D", "M", "V", "X", "P", "U");
        assertThat(codes).containsExactlyInAnyOrderElementsOf(priority);
        assertThat(handoff).containsExactlyInAnyOrder("S", "L", "X");
        assertThat(needs).containsExactlyInAnyOrder("S", "L", "R", "Q", "D", "M");
        java.util.Set<String> ids = new java.util.HashSet<>();
        root.path("rules").forEach(r -> assertThat(ids.add(r.path("id").asText())).as(r.path("id").asText()).isTrue());
        assertThat(ids).contains("N1", "N9", "G1", "G4", "F4");
    }
}
