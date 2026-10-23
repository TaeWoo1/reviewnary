package com.sellerops.review.draft;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The settings screen warns about a bare topic word as it is typed, from its own copy of the list
 * ({@code frontend/src/lib/reviewTopicWords.ts}). The copy is the promise check's list, or the warning lies.
 */
class ReviewTopicWordsParityTest {

    private static final Path COPY = Path.of("..", "frontend", "src", "lib", "reviewTopicWords.ts");

    @Test
    void theScreensCopyIsThePromiseChecksListWordForWord() throws IOException {
        String source = Files.readString(COPY);
        int start = source.indexOf("REVIEW_TOPIC_WORDS = [");
        int end = source.indexOf("] as const", start);
        assertThat(start).as("the list is where this test reads it").isPositive();
        assertThat(end).as("and ends where this test stops").isGreaterThan(start);
        List<String> words = new ArrayList<>();
        Matcher m = Pattern.compile("\"([^\"]+)\"").matcher(source.substring(start, end));
        while (m.find()) {
            words.add(m.group(1));
        }
        assertThat(words).isNotEmpty().containsExactlyElementsOf(ReviewClaimGuard.COMMITMENT_TERMS);
    }
}
