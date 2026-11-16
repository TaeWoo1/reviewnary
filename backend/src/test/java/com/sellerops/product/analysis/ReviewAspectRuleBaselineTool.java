package com.sellerops.product.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sellerops.reviewissue.RuleBasedIssueSignatureExtractor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Not a test: the word-rule baseline of the review-aspect accuracy evaluation (2026-11-17). Runs only when
 * {@code REVIEW_ASPECT_EVAL_DIR} names the evaluation folder (outside the repository — it holds real reviews), reads its
 * {@code sample.jsonl} with the analysis screen's own rule reader, and writes {@code rules.jsonl} for {@code score.py}.
 */
@EnabledIfEnvironmentVariable(named = "REVIEW_ASPECT_EVAL_DIR", matches = ".+")
class ReviewAspectRuleBaselineTool {

    @Test
    void writeRuleReadings() throws Exception {
        Path dir = Path.of(System.getenv("REVIEW_ASPECT_EVAL_DIR"));
        ObjectMapper mapper = new ObjectMapper();
        ReviewOpinionReader reader = new ReviewOpinionReader(new RuleBasedIssueSignatureExtractor(false));
        List<String> out = new ArrayList<>();
        for (String line : Files.readAllLines(dir.resolve("sample.jsonl"))) {
            JsonNode s = mapper.readTree(line);
            var row = mapper.createObjectNode();
            row.put("id", s.path("id").asText());
            var ops = row.putArray("opinions");
            for (ReviewOpinionReader.Opinion o : reader.read(s.path("body").asText())) {
                var n = ops.addObject();
                if (o.aspect() == null) {
                    n.putNull("aspect");
                } else {
                    n.put("aspect", o.aspect());
                }
                n.put("praise", o.praise());
            }
            out.add(row.toString());
        }
        Files.write(dir.resolve("rules.jsonl"), out);
    }
}
