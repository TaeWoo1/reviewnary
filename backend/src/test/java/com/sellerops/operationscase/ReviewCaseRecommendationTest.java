package com.sellerops.operationscase;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>A review reaches 「내 결정 필요」 carrying what is already known about it.</b>
 *
 * <p>Before this, every review case was written with {@code recommendedAction} null and stood in the queue beside an
 * inquiry that said 「제안: 답변 확인 후 발송 · 초안 있음」. That was read as a missing capability and it was not one:
 * the deterministic review preparation had shipped in the proactive lane long before, and this lane simply threw its
 * answer away every tick.
 *
 * <p>What these assertions hold is <b>whose judgement it is</b>. The review lane must not reach its own conclusion
 * about repetition — two producers counting 「같은 문제가 몇 번 있었나」 would drift, and the seller would have two
 * answers to one question. So the preparation is a call into the existing investigator and nothing else: no rule
 * about ratings, no sentence composed here, no vocabulary invented for it.
 */
class ReviewCaseRecommendationTest {

    private static final Path PROCESSOR =
            Paths.get("src/main/java/com/sellerops/operationscase/OperationsCaseProcessor.java");
    private static final Path INVESTIGATOR =
            Paths.get("src/main/java/com/sellerops/proactive/ProactiveReviewInvestigator.java");
    private static final Path HOME_WORK = Paths.get("../frontend/src/lib/homeWork.ts");
    private static final Path COPY = Paths.get("../frontend/src/lib/copy/customerOps.ts");
    private static final Path NAV = Paths.get("../frontend/src/lib/nav.v2.ts");
    /** Where the prepared recommendation is read now that the queue row carries customer context only. */
    private static final Path CASE_SCREEN = Paths.get("../frontend/src/pages/app/OperationsCase.tsx");

    private static String code(Path path) throws IOException {
        return Files.readString(path).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    /**
     * The body of one method, ending where the next one starts — <b>not</b> at a named neighbour. Slicing to a
     * neighbour made this test depend on file order, and it broke the day an unrelated method was inserted between
     * the two. What these assertions are about is one method's contents, so that is what the slice has to be.
     */
    private static String bodyOf(String source, String signature) {
        String from = source.substring(source.indexOf(signature));
        int next = from.indexOf("\n    private ", signature.length());
        return next < 0 ? from : from.substring(0, next);
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }

    @Test
    @DisplayName("the review recommendation is the existing investigator's, reached in exactly one place")
    void oneProducerOfTheReviewJudgement() throws IOException {
        String processor = code(PROCESSOR);

        assertThat(count(processor, "reviewInvestigator.investigate("))
                .as("one call site: a second would be a second opinion about the same review")
                .isEqualTo(1);
        assertThat(count(processor, "prepareReviewRecommendation("))
                .as("declared once and called once, from the rule-decided path")
                .isEqualTo(2);
        assertThat(processor)
                .as("the sentence is carried whole from the investigator, never composed here")
                .contains("c.setRecommendedAction(found.recommendation())");
    }

    @Test
    @DisplayName("nothing about a review is judged here — no rating rule, no repeat rule, no new sentence")
    void noSecondReviewBrain() throws IOException {
        String processor = code(PROCESSOR);
        String body = bodyOf(processor, "private void prepareReviewRecommendation(");

        assertThat(body)
                .as("no threshold, no rating arithmetic and no Korean prose: this method decides nothing, it asks")
                .doesNotContain("getRating()")
                .doesNotContain("Repository")
                .doesNotContain("\"");
        assertThat(count(body, "if ("))
                .as("one guard — is this a review the rules handed to a decision — and no branch beyond it")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("it costs no model call and no marketplace call, so no review has to be chosen over another")
    void freeByConstruction() throws IOException {
        String investigator = code(INVESTIGATOR);
        String processor = code(PROCESSOR);
        String body = bodyOf(processor, "private void prepareReviewRecommendation(");

        assertThat(investigator)
                .as("the investigator reads the issue memory; it holds no model, transport or channel client")
                .doesNotContain("AgentLlm").doesNotContain("ChatModel").doesNotContain("prompt")
                .doesNotContain("Connector").doesNotContain("apiKey");
        assertThat(body)
                .as("no quota is charged and no investigation flag is consulted — there is no spend to ration")
                .doesNotContain("quota").doesNotContain("investigation.isEnabledFor").doesNotContain("investigator.");
    }

    @Test
    @DisplayName("preparation stops at RECOMMENDATION_ONLY — a review still has nothing to send")
    void ceilingHolds() throws IOException {
        String processor = code(PROCESSOR);
        String body = bodyOf(processor, "private void prepareReviewRecommendation(");

        assertThat(body)
                .as("the ceiling for a review, and the honest name for what now exists: something to read")
                .contains("CasePreparedAction.RECOMMENDATION_ONLY")
                .doesNotContain("DRAFT_PREPARED");
        assertThat(processor)
                .as("the draft fences are untouched — both still refuse a non-INQUIRY subject")
                .contains("c.getSubjectKind() != OperationsSubjectKind.INQUIRY");
    }

    @Test
    @DisplayName("an investigation still outranks the row count, and a settled review is prepared nothing")
    void ruleLaneIsTheFloorNotTheAnswer() throws IOException {
        String processor = code(PROCESSOR);
        String body = bodyOf(processor, "private void prepareReviewRecommendation(");

        assertThat(body)
                .as("only a review the rules handed to a decision is prepared — not one they closed or put under watch")
                .contains("conclusion.needsInvestigation()");
        assertThat(processor.indexOf("prepareReviewRecommendation(c,"))
                .as("written before applyInvestigation can overwrite it: a model that read the review outranks a count")
                .isLessThan(processor.indexOf("c.setRecommendedActionType(output.recommendedActionType())"));
    }

    @Test
    @DisplayName("the seller sees the prepared step, and a review is no longer described as 「판단 보류」")
    void theQueueShowsIt() throws IOException {
        String homeWork = code(HOME_WORK);
        String copy = code(COPY);

        /*
         * <b>Re-pointed to where the prepared sentence now stands</b> (product-owner decision,
         * 2026-10-01 — 「Home row line은 customer context만」).
         *
         * <p>This asserted the fall-through `missing ?? summary ?? recommendedAction ?? reasonNote` in
         * the QUEUE ROW. The visual QA that ended it: a row carrying 「대응 필요로 정함」 or
         * 「이 상품에서 「포장 파손」 문제가 3건 확인됐습니다」 is reviewnary's own workflow prose standing
         * in the slot a seller reads as 「이 고객이 무슨 말을 했나」. The row's second line is the case's
         * `summary` now, which is the one of the four that is about the customer.
         *
         * <p><b>The guarantee is unchanged: the seller still SEES the prepared step.</b> It is on the
         * case screen the row opens — `OperationsCase` reads `recommendedAction` as the 「왜 판단이
         * 필요한가」 line — so this asserts it there rather than deleting the claim. The 판단 보류
         * half below never moved.
         */
        assertThat(homeWork)
                .as("the queue row's second line is customer context — the case's own account of the subject")
                // `preview` is the guard that drops it when it would be the title again; what matters
                // to this fence is that `summary` is the only field feeding the line.
                .contains("const line = preview(row.summary,");
        assertThat(homeWork)
                .as("and never reviewnary's own workflow prose about what to do")
                // `recommendedActionType` is still read — it chooses the row's TAG, which is a closed
                // token and not prose. What may not reach the row is the sentence.
                .doesNotContain("row.recommendedAction ")
                .doesNotContain("row.recommendedAction;")
                .doesNotContain("row.recommendedAction)")
                .doesNotContain("row.reasonNote");
        assertThat(code(CASE_SCREEN))
                .as("the prepared recommendation is still shown — on the screen the row opens")
                .contains("detail.recommendedAction");
        assertThat(copy)
                .as("a review that named no action type is tagged for what it is; subjectKind is a stored fact")
                .contains("subjectKind === \"REVIEW\" ? REASON.review : REASON.withheld");
    }

    @Test
    @DisplayName("the queue has a standing door, and it is not two doors")
    void theQueueIsReachable() throws IOException {
        String nav = code(NAV);

        assertThat(count(nav, "/customer-operations/cases"))
                .as("one menu entry for one screen")
                .isEqualTo(1);
        assertThat(nav)
                .as("named for the seller's question, in 운영 where that question is asked")
                .contains("label: \"확인할 일\"");
    }
}
