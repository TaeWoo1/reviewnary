import com.sellerops.common.VocPreviewSanitizer;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * The backend's own pre-send redaction ({@link VocPreviewSanitizer#redactFullBody}) for the evaluation's judge files
 * (review 2026-10-28, second pass: the judge input carried the review and the passages unredacted). One base64 text per
 * line in, its redacted text as base64 out — the same procedure every model payload gets, not a copy of its patterns.
 *
 *     java -cp backend/build/classes/java/main tools/eval/reply-agent/Redact.java
 */
public class Redact {
    public static void main(String[] args) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        for (String line; (line = in.readLine()) != null; ) {
            String text = new String(Base64.getDecoder().decode(line.strip()), StandardCharsets.UTF_8);
            String redacted = VocPreviewSanitizer.redactFullBody(text).text();
            out.append(Base64.getEncoder().encodeToString(
                    (redacted == null ? "" : redacted).getBytes(StandardCharsets.UTF_8))).append('\n');
        }
        System.out.print(out);
    }
}
