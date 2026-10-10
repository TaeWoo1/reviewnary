package com.sellerops.review.triage.corpus;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>«online learning은 금지» as a property of the source tree, not a sentence in a document.</b>
 *
 * <p>{@code docs/slices/production-triage-feedback-draft-v1.md} §3 and §7.1 say a correction may never train a
 * running classifier, never become gold, and never be few-shot material without a separate decision. Those are
 * absences, and an absence has no runtime to test — so they are asserted here the way
 * {@code SellerOperationsPolicyFenceTest} and {@code AnswerMemoryWriteFenceTest} assert theirs.
 *
 * <p>The failure mode is not dramatic. Somebody adds «and feed the last snapshot's corrections into the prompt
 * as examples» to lift a benchmark, or lets the corpus read back into {@code AiTriageCurrent} so the product
 * «learns». Nothing would look wrong on any screen, and the classifier's measured accuracy would quietly become
 * a function of whichever sellers corrected most.
 */
class SellerFeedbackCorpusFenceTest {

    private static final Path CORPUS = Paths.get("src/main/java/com/sellerops/review/triage/corpus");

    /* ───────── 1. the corpus cannot reach anything that classifies ───────── */

    /**
     * The machinery a cut set may not touch. {@code TriagePrompt} and {@code ReviewTriageClassifier} are the two
     * that would turn this into online learning; {@code AiTriageCurrent} is the one row the product's surface
     * actually reads, so a corpus that wrote it would change what a seller sees from an evaluation set.
     */
    private static final List<String> NO_CLASSIFICATION = List.of(
            "TriagePrompt", "ReviewTriageClassifier", "ApiTriageClassifier", "AdditiveTriageDecision",
            "AiTriageCurrent", "AiTriagePilot", "ReviewTriageRules", "ReviewTriageTier", "labels.json",
            "ChatModel", "OpenAi", "embedding", "Embedding", "fewShot", "trainingSet");

    @Test
    @DisplayName("the corpus package cannot reach a classifier, a prompt, the shown mark, or the gold set")
    void nothingHereCanTeachAnything() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path source : sources()) {
            String code = stripComments(Files.readString(source));
            for (String forbidden : NO_CLASSIFICATION) {
                if (code.contains(forbidden)) {
                    offenders.add(source.getFileName() + " → " + forbidden);
                }
            }
        }
        assertThat(offenders)
                .as("a cut set is measured against offline; it may not reach the thing it measures")
                .isEmpty();
    }

    /* ───────── 2. the only writes are the version stamp and the manifest ───────── */

    @Test
    @DisplayName("the corpus writes a manifest and nothing else — every stamp still goes through TriageFeedbackService")
    void theStamperIsStillTheFeedbackService() throws IOException {
        String service = stripComments(Files.readString(CORPUS.resolve("TriageCorpusService.java")));
        assertThat(service)
                .as("the manifest is this package's own row")
                .contains("snapshots.save(");
        // Not `dispositions.save(`, not `actions.save(`, not `behavior.save(`: the version stamp belongs to the
        // one class that already owned it, so there is exactly one place a row can be frozen.
        assertThat(service).contains("feedback.freezeSnapshot(").contains("feedback.freezeSilverSnapshot(");
        for (String forbidden : List.of("dispositions.save", "actions.save", "behavior.save",
                "corrections.save", "predictions.save", ".delete(", "deleteBy")) {
            assertThat(service).as("corpus must not write through %s", forbidden).doesNotContain(forbidden);
        }
    }

    /* ───────── 3. the exported row is the privacy boundary ───────── */

    /**
     * Every field name that would make the exported row carry more than the gold set does.
     *
     * <p>{@code contracts/review-eval/naver/v2/labels.json} carries «ONLY the review-id fingerprint and the
     * operator's judgment … Never the body, the raw 리뷰글번호, the rating, the date, the body length, the
     * stratum, the product, or any seller identity». A correction corpus that carried a body would be a second,
     * looser boundary for the same content — and the looser one is the one that leaks.
     */
    private static final List<String> NOT_ON_A_CORPUS_ROW = List.of(
            "body", "text", "rating", "stars", "orgId", "productId", "reviewId", "userId", "author",
            "customer", "externalId", "receivedAt", "correctedAt", "decidedBy");

    @Test
    @DisplayName("a corpus row carries a fingerprint and closed vocabulary — no body, no rating, no date, no identity")
    void theExportedRowCannotCarryContent() {
        List<String> components = Stream.of(TriageCorpusRow.class.getRecordComponents())
                .map(RecordComponent::getName).toList();
        assertThat(components).as("the record must have fields, or this passes vacuously").isNotEmpty();
        assertThat(components).contains("reviewIdFingerprint");
        for (String forbidden : NOT_ON_A_CORPUS_ROW) {
            assertThat(components)
                    .as("«%s» on a corpus row widens the boundary the gold set already drew", forbidden)
                    .doesNotContain(forbidden);
        }
    }

    /* ───────── 4. the restriction travels with the rows ───────── */

    @Test
    @DisplayName("the exported document states what it may not be used for, in the document")
    void theDocumentCarriesItsOwnRestriction() {
        assertThat(TriageCorpusDocument.USAGE).isNotEmpty();
        String all = String.join("\n", TriageCorpusDocument.USAGE);
        // The three refusals of §3, each present as a sentence somebody reading the file will see.
        assertThat(all).contains("MUST NOT become gold");
        assertThat(all).contains("fine-tuning");
        assertThat(all).contains("MUST NOT grow");
        assertThat(TriageCorpusDocument.CONTRACT).isEqualTo("triage-feedback-corpus/v1");
    }

    @Test
    @DisplayName("there is no third snapshot kind, and no way to cut both at once")
    void silverAndCorrectionsAreNeverOneSet() throws IOException {
        assertThat(SnapshotKind.values()).containsExactly(SnapshotKind.CORRECTION, SnapshotKind.SILVER);
        String service = stripComments(Files.readString(CORPUS.resolve("TriageCorpusService.java")));
        // Both freezes exist and neither call site is reachable from the other's branch — one cut, one kind.
        assertThat(service).doesNotContain("MIXED").doesNotContain("freezeBoth");
    }

    private static List<Path> sources() throws IOException {
        try (Stream<Path> walk = Files.walk(CORPUS)) {
            return walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    /**
     * Strip comments before matching — load-bearing for the same reason it is in
     * {@code SellerOperationsPolicyFenceTest}: this package's javadoc NAMES the machinery it may not reach, and
     * a fence that matched prose would push the explanation out of the code to stay green.
     */
    private static String stripComments(String code) {
        return code.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }
}
