package com.sellerops.vendor.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * <b>This port computes the contract's fingerprint, not a lookalike</b> — every golden vector of
 * {@code contracts/review-fingerprint/v1}, read from the shared file (the same file the backend and the collector
 * hold themselves to), normalizes and hashes to the recorded values.
 */
class ReviewBodyFingerprintTest {

    private static final Path VECTORS = Path.of("..", "contracts", "review-fingerprint", "v1", "golden-vectors.json");

    @Test
    void everyGoldenVectorReproduces() throws Exception {
        JsonNode cases = new ObjectMapper().readTree(Files.readString(VECTORS)).path("cases");
        assertThat(cases.size()).as("the vectors were read").isGreaterThanOrEqualTo(10);
        for (JsonNode c : cases) {
            String raw = c.path("raw").asText();
            assertThat(ReviewBodyFingerprint.normalizeForFingerprint(raw)).as(c.path("name").asText())
                    .isEqualTo(c.path("normalized").asText());
            assertThat(ReviewBodyFingerprint.of(raw)).as(c.path("name").asText())
                    .isEqualTo(c.path("fingerprint").asText());
        }
    }
}
