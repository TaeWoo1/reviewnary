package com.sellerops.review.triage.pilot;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.review.triage.ReviewTriageTier;
import com.sellerops.review.triage.feedback.TriagePrediction;
import com.sellerops.review.triage.llm.ReviewTriageClassifier;
import com.sellerops.review.triage.llm.TriageSuggestedAction;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>「응대가 필요하다」 from the classifier's own answer</b> (owner, 2026-10-28: 「1~5점 전부 일관된 기준으로 llm이
 * 심사해야해」) — the model's tier, never the rating-guarded one, so the same answer means the same thing at every
 * rating.
 */
class ResponseVerdictTest {

    private static TriagePrediction answer(ReviewTriageClassifier.Status status, ReviewTriageTier modelTier,
                                           ReviewTriageTier guardedTier, TriageSuggestedAction action) {
        TriagePrediction p = new TriagePrediction();
        p.setStatus(status);
        p.setModelTier(modelTier);
        p.setTier(guardedTier);
        p.setSuggestedNextAction(action);
        return p;
    }

    @Test
    @DisplayName("확인 필요, or a reply or a remedy named as the thing to do, is a response owed")
    void whatIsAResponseOwed() {
        assertThat(AiTriagePilotService.verdictOf(answer(ReviewTriageClassifier.Status.OK,
                ReviewTriageTier.NEEDS_ATTENTION, ReviewTriageTier.NEEDS_ATTENTION, TriageSuggestedAction.INVESTIGATE_PRODUCT)))
                .contains(true);
        assertThat(AiTriagePilotService.verdictOf(answer(ReviewTriageClassifier.Status.OK,
                ReviewTriageTier.FYI, ReviewTriageTier.FYI, TriageSuggestedAction.REPLY_TO_BUYER))).contains(true);
        assertThat(AiTriagePilotService.verdictOf(answer(ReviewTriageClassifier.Status.OK,
                ReviewTriageTier.WATCH, ReviewTriageTier.WATCH, TriageSuggestedAction.OFFER_REMEDY))).contains(true);
        assertThat(AiTriagePilotService.verdictOf(answer(ReviewTriageClassifier.Status.OK,
                ReviewTriageTier.WATCH, ReviewTriageTier.WATCH, TriageSuggestedAction.MONITOR_REPEAT))).contains(false);
        assertThat(AiTriagePilotService.verdictOf(answer(ReviewTriageClassifier.Status.OK,
                ReviewTriageTier.FYI, ReviewTriageTier.FYI, TriageSuggestedAction.NONE))).contains(false);
    }

    @Test
    @DisplayName("the rating's floor is not the model's word: a ★1 the guard lifted but the model called 참고 owes nothing")
    void theGuardedTierIsNotTheVerdict() {
        assertThat(AiTriagePilotService.verdictOf(answer(ReviewTriageClassifier.Status.OK,
                ReviewTriageTier.FYI, ReviewTriageTier.NEEDS_ATTENTION, TriageSuggestedAction.NONE))).contains(false);
    }

    @Test
    @DisplayName("a failed, refused or missing answer is no verdict — the caller waits")
    void noAnswerIsNoVerdict() {
        assertThat(AiTriagePilotService.verdictOf(null)).isEqualTo(Optional.empty());
        assertThat(AiTriagePilotService.verdictOf(answer(ReviewTriageClassifier.Status.CLASSIFICATION_FAILED,
                null, ReviewTriageTier.NEEDS_ATTENTION, null))).isEmpty();
        assertThat(AiTriagePilotService.verdictOf(answer(ReviewTriageClassifier.Status.UNCLASSIFIED,
                null, ReviewTriageTier.FYI, null))).isEmpty();
    }
}
