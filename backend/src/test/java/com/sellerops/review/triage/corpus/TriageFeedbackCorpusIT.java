package com.sellerops.review.triage.corpus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sellerops.channel.Channel;
import com.sellerops.channel.ChannelRepository;
import com.sellerops.channel.ChannelStatus;
import com.sellerops.common.ApiException;
import com.sellerops.common.ReviewIdFingerprint;
import com.sellerops.review.Review;
import com.sellerops.review.ReviewRepository;
import com.sellerops.review.triage.ReviewTriageTier;
import com.sellerops.review.triage.feedback.AiTriageCurrentRepository;
import com.sellerops.review.triage.feedback.CorrectionDispositionKind;
import com.sellerops.review.triage.feedback.CorrectionDispositionRepository;
import com.sellerops.review.triage.feedback.SellerCorrectionState;
import com.sellerops.review.triage.feedback.TriageAction;
import com.sellerops.review.triage.feedback.TriageActionKind;
import com.sellerops.review.triage.feedback.TriageActionRepository;
import com.sellerops.review.triage.feedback.TriageBehaviorEventRepository;
import com.sellerops.review.triage.feedback.TriageCorrection;
import com.sellerops.review.triage.feedback.TriageCorrectionAuditRepository;
import com.sellerops.review.triage.feedback.TriageCorrectionRepository;
import com.sellerops.review.triage.feedback.TriageFeedbackService;
import com.sellerops.review.triage.feedback.TriagePredictionRepository;
import com.sellerops.review.triage.feedback.TriageShownSource;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * The corpus door, against a real database: disposition → cut → read, and every refusal.
 *
 * <p>Before this package the three methods under test had <b>no caller in {@code main/}</b>, so none of these
 * sequences had ever happened outside a unit test with mocks. Each assertion below is a property of the offline
 * discipline in {@code docs/slices/production-triage-feedback-draft-v1.md} §3 / §7.4, and each would have been
 * unreachable rather than wrong.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class TriageFeedbackCorpusIT {

    @Autowired ReviewRepository reviews;
    @Autowired ChannelRepository channels;
    @Autowired TriagePredictionRepository predictions;
    @Autowired TriageCorrectionRepository corrections;
    @Autowired CorrectionDispositionRepository dispositions;
    @Autowired AiTriageCurrentRepository aiCurrent;
    @Autowired TriageActionRepository actions;
    @Autowired TriageBehaviorEventRepository behavior;
    @Autowired TriageCorrectionAuditRepository correctionAudit;
    @Autowired TriageFeedbackSnapshotRepository snapshots;
    @Autowired TriageCorpusRepository rows;

    private final UUID org = UUID.randomUUID();
    private final UUID reviewer = UUID.randomUUID();
    private TriageFeedbackService feedback;
    private TriageCorpusService corpus;
    private UUID channelId;

    @BeforeEach
    void setUp() {
        Channel ch = new Channel();
        ch.setCode("NAVER");
        ch.setNameKo("네이버");
        ch.setStatus(ChannelStatus.AVAILABLE);
        ch.setSupportsInquiry(true);
        ch.setSupportsReview(true);
        ch.setSupportsOrder(true);
        ch.setSupportsSales(true);
        ch.setSupportsProduct(true);
        ch.setSortOrder(0);
        channelId = channels.save(ch).getId();

        feedback = new TriageFeedbackService(predictions, corrections, dispositions, aiCurrent, actions,
                behavior, correctionAudit);
        corpus = new TriageCorpusService(feedback, snapshots, rows, corrections, predictions);
    }

    // ── 1. the whole sequence, end to end ────────────────────────────────────────────────────────

    @Test
    @DisplayName("disposition → cut → read: the set is a fingerprint and a judgement, and it says what it may not be used for")
    void theCorpusIsReadable() {
        TriageCorrection c = correction("2026100001", ReviewTriageTier.FYI, ReviewTriageTier.NEEDS_ATTENTION);
        feedback.disposition(org, c.getId(), CorrectionDispositionKind.CLASSIFIER_ERROR, reviewer);

        assertThat(corpus.pending(org).correctionRows()).isEqualTo(1);
        TriageCorpusService.Cut cut = corpus.cut(org, SnapshotKind.CORRECTION, "2026-10/1", "첫 컷", reviewer);
        assertThat(cut.rowCount()).isEqualTo(1);

        TriageCorpusDocument doc = corpus.corpus(org, "2026-10/1");
        assertThat(doc.contract()).isEqualTo("triage-feedback-corpus/v1");
        assertThat(doc.rowCount()).isEqualTo(1);
        assertThat(doc.withheldRowCount()).isZero();
        assertThat(doc.usage()).isNotEmpty();
        assertThat(doc.rows()).hasSize(1);
        TriageCorpusRow row = doc.rows().get(0);
        // The one-way digest of the channel-side id, and nothing that could reconstruct a customer.
        assertThat(row.reviewIdFingerprint()).isEqualTo(ReviewIdFingerprint.of("2026100001"));
        assertThat(row.shownTier()).isEqualTo("FYI");
        assertThat(row.correctedTier()).isEqualTo("NEEDS_ATTENTION");
        assertThat(row.shownSource()).isEqualTo("RULES");
        // Nothing classified this review, so the prediction columns are empty rather than invented.
        assertThat(row.predictedTier()).isNull();
        assertThat(row.classifierVersion()).isNull();

        // Cut and never reopened: the frozen row refuses a second reading.
        assertThatThrownBy(() -> feedback.disposition(org, c.getId(),
                CorrectionDispositionKind.SELLER_PREFERENCE, reviewer))
                .isInstanceOf(ApiException.class);
        assertThat(corpus.pending(org).correctionRows()).isZero();
    }

    // ── 2. a version names one cut, across both kinds (§7.4) ─────────────────────────────────────

    @Test
    @DisplayName("one version string names one cut — re-using it is refused, and a silver cut cannot borrow a correction's name")
    void aVersionIsUsedOnce() {
        feedback.disposition(org, correction("2026100002", ReviewTriageTier.WATCH, ReviewTriageTier.FYI).getId(),
                CorrectionDispositionKind.CLASSIFIER_ERROR, reviewer);
        corpus.cut(org, SnapshotKind.CORRECTION, "v1", null, reviewer);

        feedback.disposition(org, correction("2026100003", ReviewTriageTier.WATCH, ReviewTriageTier.FYI).getId(),
                CorrectionDispositionKind.CLASSIFIER_ERROR, reviewer);
        assertThatThrownBy(() -> corpus.cut(org, SnapshotKind.CORRECTION, "v1", null, reviewer))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("이미 고정된");

        action();
        assertThatThrownBy(() -> corpus.cut(org, SnapshotKind.SILVER, "v1", null, reviewer))
                .as("§7.4 — a silver set under a correction set's name is how the weaker one stops being visible")
                .isInstanceOf(ApiException.class);

        // The loose row is still loose: a refused cut stamps nothing.
        assertThat(corpus.pending(org).correctionRows()).isEqualTo(1);
    }

    // ── 3. a seller preference is never in an evaluation set ─────────────────────────────────────

    @Test
    @DisplayName("SELLER_PREFERENCE is counted, reported, and never stamped")
    void preferencesStayOutOfTheSet() {
        TriageCorrection preference =
                correction("2026100004", ReviewTriageTier.NEEDS_ATTENTION, ReviewTriageTier.FYI);
        feedback.disposition(org, preference.getId(), CorrectionDispositionKind.SELLER_PREFERENCE, reviewer);

        TriageCorpusService.Pending pending = corpus.pending(org);
        assertThat(pending.sellerPreferenceRows()).isEqualTo(1);
        assertThat(pending.correctionRows()).isZero();
        assertThatThrownBy(() -> corpus.cut(org, SnapshotKind.CORRECTION, "pref/1", null, reviewer))
                .as("an empty cut burns no version string")
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("고정할 항목이 없습니다");
        assertThat(snapshots.findByOrgIdAndVersion(org, "pref/1")).isEmpty();
        assertThat(dispositions.findByCorrectionId(preference.getId()).orElseThrow().getSnapshotVersion())
                .isNull();
    }

    // ── 4. a withdrawn correction is the absence of an answer, not a weak one ────────────────────

    @Test
    @DisplayName("a correction the seller took back is reported as withdrawn and never cut")
    void withdrawnCorrectionsAreNotEvidence() {
        TriageCorrection withdrawn =
                correction("2026100005", ReviewTriageTier.FYI, ReviewTriageTier.NEEDS_ATTENTION);
        feedback.disposition(org, withdrawn.getId(), CorrectionDispositionKind.CLASSIFIER_ERROR, reviewer);
        withdrawn.setState(SellerCorrectionState.WITHDRAWN);
        corrections.save(withdrawn);

        TriageCorpusService.Pending pending = corpus.pending(org);
        assertThat(pending.correctionRows()).isZero();
        assertThat(pending.withdrawnRows()).as("named, never silently subtracted").isEqualTo(1);
        assertThatThrownBy(() -> corpus.cut(org, SnapshotKind.CORRECTION, "w/1", null, reviewer))
                .isInstanceOf(ApiException.class);
    }

    // ── 5. silver is cut, and is not a corpus ────────────────────────────────────────────────────

    @Test
    @DisplayName("a silver cut is recorded and has no document — behaviour is never an evaluation set")
    void silverIsNotExported() {
        action();
        TriageCorpusService.Cut cut = corpus.cut(org, SnapshotKind.SILVER, "silver/1", null, reviewer);
        assertThat(cut.rowCount()).isEqualTo(1);
        assertThat(cut.kind()).isEqualTo("SILVER");

        assertThatThrownBy(() -> corpus.corpus(org, "silver/1"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("평가 집합이 아닙니다");
        assertThat(corpus.snapshots(org)).extracting(TriageFeedbackSnapshot::getVersion)
                .containsExactly("silver/1");
    }

    // ── 6. a member nobody can join is withheld, and said out loud ───────────────────────────────

    @Test
    @DisplayName("a review with no channel-side id is withheld from the export and counted in the document")
    void anUnjoinableMemberIsNamed() {
        TriageCorrection joinable =
                correction("2026100006", ReviewTriageTier.FYI, ReviewTriageTier.NEEDS_ATTENTION);
        TriageCorrection orphan = correction(null, ReviewTriageTier.FYI, ReviewTriageTier.NEEDS_ATTENTION);
        feedback.disposition(org, joinable.getId(), CorrectionDispositionKind.CLASSIFIER_ERROR, reviewer);
        feedback.disposition(org, orphan.getId(), CorrectionDispositionKind.CLASSIFIER_ERROR, reviewer);
        corpus.cut(org, SnapshotKind.CORRECTION, "mixed/1", null, reviewer);

        TriageCorpusDocument doc = corpus.corpus(org, "mixed/1");
        assertThat(doc.rowCount()).as("the frozen set still has both").isEqualTo(2);
        assertThat(doc.rows()).hasSize(1);
        assertThat(doc.withheldRowCount()).isEqualTo(1);
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────────

    private TriageCorrection correction(String externalId, ReviewTriageTier shown, ReviewTriageTier corrected) {
        Review review = new Review();
        review.setOrgId(org);
        review.setChannelId(channelId);
        review.setExternalId(externalId);
        review.setRating(3);
        review.setBody("설명과 달랐습니다");
        review.setReceivedAt(Instant.parse("2026-09-01T00:00:00Z"));
        reviews.save(review);

        TriageCorrection row = new TriageCorrection();
        row.setOrgId(org);
        row.setReviewId(review.getId());
        row.setShownTier(shown);
        row.setShownSource(TriageShownSource.RULES);
        row.setCorrectedTier(corrected);
        row.setCorrectedAt(Instant.now());
        row.setState(SellerCorrectionState.STANDING);
        return corrections.save(row);
    }

    private void action() {
        TriageAction row = new TriageAction();
        row.setOrgId(org);
        row.setReviewId(UUID.randomUUID());
        row.setKind(TriageActionKind.ACTION_COMPLETED);
        row.setShownTier(ReviewTriageTier.NEEDS_ATTENTION);
        row.setShownSource(TriageShownSource.RULES);
        row.setActedAt(Instant.now());
        actions.save(row);
    }
}
