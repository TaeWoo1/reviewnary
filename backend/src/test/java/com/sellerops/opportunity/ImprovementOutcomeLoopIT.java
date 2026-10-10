package com.sellerops.opportunity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.when;

import com.sellerops.channel.Channel;
import com.sellerops.channel.ChannelRepository;
import com.sellerops.channel.ChannelStatus;
import com.sellerops.common.ApiException;
import com.sellerops.product.Product;
import com.sellerops.product.ProductRepository;
import com.sellerops.product.ProductSignalsService;
import com.sellerops.review.Review;
import com.sellerops.review.ReviewRepository;
import com.sellerops.reviewissue.IssueLifecycleState;
import com.sellerops.reviewissue.IssueStateActor;
import com.sellerops.reviewissue.IssueStateReason;
import com.sellerops.reviewissue.MatchConfidence;
import com.sellerops.reviewissue.ReviewIssue;
import com.sellerops.reviewissue.ReviewIssueEvidence;
import com.sellerops.reviewissue.ReviewIssueEvidenceRepository;
import com.sellerops.reviewissue.ReviewIssueLifecycleService;
import com.sellerops.reviewissue.ReviewIssueQueryService;
import com.sellerops.reviewissue.ReviewIssueRepository;
import com.sellerops.reviewissue.ReviewIssueSnapshotService;
import com.sellerops.reviewissue.ReviewIssueStateEvent;
import com.sellerops.reviewissue.ReviewIssueStateEventRepository;
import com.sellerops.reviewissue.dto.ReviewIssueView;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
 * <b>반복 문제 → 제안 → 채택 → 적용 → 관찰 → 결과, against a real database.</b>
 *
 * <p>Before this package the chain broke twice. 적용 was recorded nowhere — the draft went into the seller's
 * library through two calls the browser made and the opportunity row never learned it — and 결과 was never
 * measured, because {@code IssueChangeKind.IMPROVED} is a sliding window evaluated against today and by the time
 * 해결됨 arrives the baseline a remediation would be measured against has slid out of it.
 *
 * <p>Each test below is one of the links, written as the assertion that had no object before.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class ImprovementOutcomeLoopIT {

    @Autowired ReviewRepository reviews;
    @Autowired ProductRepository products;
    @Autowired ChannelRepository channels;
    @Autowired ReviewIssueRepository issueRows;
    @Autowired ReviewIssueEvidenceRepository evidence;
    @Autowired ReviewIssueStateEventRepository stateEvents;
    @Autowired ImprovementOpportunityRepository decisions;
    @Autowired OpportunityDecisionEventRepository trail;
    @Autowired ImprovementOutcomeRepository outcomes;

    /** 적용 happens on this day; the baseline is the 28 days before it, the window the 28 from it. */
    private static final LocalDate APPLIED_ON = LocalDate.of(2026, 9, 1);
    private static final LocalDate WINDOW_CLOSED = LocalDate.of(2026, 9, 28);

    private final UUID org = UUID.randomUUID();
    private final UUID seller = UUID.randomUUID();

    private UUID channelId;
    private UUID productId;
    private ReviewIssue issue;
    private ImprovementOutcomeService outcomeService;
    private ReviewIssueLifecycleService lifecycle;
    private OpportunityService service;
    private ReviewIssueQueryService issueQuery;

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

        Product p = new Product();
        p.setOrgId(org);
        p.setName("선바로 일체형 전선몰딩");
        p.setStatus("ACTIVE");
        productId = products.save(p).getId();

        issue = new ReviewIssue();
        issue.setOrgId(org);
        issue.setAspect("접착");
        issue.setProblem("탈락");
        issue.setSignatureKey("접착:탈락");
        issue.setTitle("접착 탈락");
        issue.setSeverity(com.sellerops.reviewissue.IssueSeverity.HIGH);
        issue.setLifecycleState(IssueLifecycleState.NEEDS_REVIEW);
        issue.setDismissed(false);
        issue.setExtractorKind("RULE_BASED");
        issue.setExtractorVersion("rule-based-issue-signature/v1");
        issue = issueRows.save(issue);

        outcomeService = new ImprovementOutcomeService(outcomes, decisions, evidence, reviews,
                java.time.Clock.fixed(Instant.parse("2026-09-28T00:00:00Z"), ZoneOffset.UTC));
        lifecycle = new ReviewIssueLifecycleService(issueRows, stateEvents,
                new ReviewIssueSnapshotService(evidence));
        issueQuery = mock(ReviewIssueQueryService.class);
        service = new OpportunityService(issueQuery, mock(ProductSignalsService.class),
                mock(KnowledgeMentionCheck.class), decisions, trail);
        service.setOutcomeLane(outcomeService, lifecycle);
        when(issueQuery.issueView(any(), any(), any())).thenAnswer(i -> view());
    }

    // ── 1. 적용 is recorded, the premise is frozen, and the problem moves to 개선 확인 중 ─────────

    @Test
    @DisplayName("applying a prepared draft records the act, freezes the baseline, and records remediation on the problem")
    void applyingClosesTheMissingLink() {
        evidenceOn(APPLIED_ON.minusDays(10), 12);           // the problem, before
        reviewsOn(APPLIED_ON.minusDays(10), 60);            // the denominator, before
        ImprovementOpportunity accepted = accepted(OpportunityKind.PRODUCT_GUIDE_SUPPLEMENT);

        OpportunityService.Applied applied = service.apply(org, seller, issue.getId(),
                OpportunityKind.PRODUCT_GUIDE_SUPPLEMENT, APPLIED_ON, AppliedArtifact.PRODUCT_KNOWLEDGE,
                UUID.randomUUID());

        assertThat(applied.status()).isEqualTo("APPLIED");
        assertThat(applied.remediationRecorded()).isTrue();
        assertThat(applied.outcome().verdict()).isEqualTo("OBSERVING");
        // The one date a seller asks for while a window is open.
        assertThat(applied.outcome().observedThrough()).isEqualTo(WINDOW_CLOSED);

        ImprovementOpportunity row = decisions.findById(accepted.getId()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(OpportunityStatus.APPLIED);
        assertThat(row.getAppliedAt()).isNotNull();
        assertThat(row.getAppliedRefKind()).isEqualTo(AppliedArtifact.PRODUCT_KNOWLEDGE);
        assertThat(trail.findByOrgIdAndOpportunityIdInOrderByDecidedAtAsc(org, List.of(row.getId())))
                .extracting(OpportunityDecisionEvent::getEvent)
                .containsExactly(OpportunityEvent.APPLIED);

        // The frozen anchor — the thing that did not exist before, and the reason a result can be read at all.
        ImprovementOutcome anchor = outcomes.findByOrgIdAndOpportunityId(org, row.getId()).orElseThrow();
        assertThat(anchor.getAppliedOn()).isEqualTo(APPLIED_ON);
        assertThat(anchor.getBaselineEvidence()).isEqualTo(12);
        // 60 reviews carrying no evidence plus the 12 that do — the whole population the problem was 12 of.
        assertThat(anchor.getBaselineReviews()).isEqualTo(72);
        assertThat(anchor.getScope()).isEqualTo(OpportunityRules.Scope.PRODUCT);
        assertThat(anchor.getProductId()).isEqualTo(productId);
        assertThat(anchor.getEvaluatedAt()).isNull();

        // And the problem itself now says 개선 확인 중, in ONE transition with its own reason.
        assertThat(issueRows.findById(issue.getId()).orElseThrow().getLifecycleState())
                .isEqualTo(IssueLifecycleState.VERIFYING);
        assertThat(stateEvents.findByOrgIdAndIssueIdOrderByCreatedAtAsc(org, issue.getId()))
                .extracting(ReviewIssueStateEvent::getToState, ReviewIssueStateEvent::getActor,
                        ReviewIssueStateEvent::getReason)
                .containsExactly(tuple(IssueLifecycleState.VERIFYING, IssueStateActor.OPERATOR,
                        IssueStateReason.IMPROVEMENT_APPLIED));
    }

    @Test
    @DisplayName("applying twice records one act, one trail row and one anchor")
    void applyingIsIdempotent() {
        evidenceOn(APPLIED_ON.minusDays(10), 12);
        reviewsOn(APPLIED_ON.minusDays(10), 60);
        ImprovementOpportunity accepted = accepted(OpportunityKind.PRODUCT_GUIDE_SUPPLEMENT);

        service.apply(org, seller, issue.getId(), OpportunityKind.PRODUCT_GUIDE_SUPPLEMENT, APPLIED_ON,
                AppliedArtifact.PRODUCT_KNOWLEDGE, null);
        Instant first = decisions.findById(accepted.getId()).orElseThrow().getAppliedAt();
        service.apply(org, seller, issue.getId(), OpportunityKind.PRODUCT_GUIDE_SUPPLEMENT,
                APPLIED_ON.plusDays(3), AppliedArtifact.PRODUCT_KNOWLEDGE, null);

        assertThat(decisions.findById(accepted.getId()).orElseThrow().getAppliedAt()).isEqualTo(first);
        assertThat(trail.findByOrgIdAndOpportunityIdInOrderByDecidedAtAsc(org, List.of(accepted.getId())))
                .hasSize(1);
        assertThat(outcomes.findAll()).hasSize(1);
        assertThat(outcomes.findAll().get(0).getAppliedOn())
                .as("a second press must not re-measure the baseline against a later day")
                .isEqualTo(APPLIED_ON);
        assertThat(stateEvents.findByOrgIdAndIssueIdOrderByCreatedAtAsc(org, issue.getId())).hasSize(1);
    }

    @Test
    @DisplayName("a draft the seller only carried away is recorded as their word, and moves no problem")
    void testimonyIsNotARecordedChange() {
        evidenceOn(APPLIED_ON.minusDays(10), 12);
        reviewsOn(APPLIED_ON.minusDays(10), 60);
        accepted(OpportunityKind.PRODUCT_IMPROVEMENT_REVIEW);

        OpportunityService.Applied applied = service.apply(org, seller, issue.getId(),
                OpportunityKind.PRODUCT_IMPROVEMENT_REVIEW, APPLIED_ON, AppliedArtifact.SELLER_DECLARED, null);

        assertThat(applied.artifact()).isEqualTo("SELLER_DECLARED");
        assertThat(applied.remediationRecorded()).isFalse();
        assertThat(issueRows.findById(issue.getId()).orElseThrow().getLifecycleState())
                .as("saving a memo is not remediation; the act is still recorded")
                .isEqualTo(IssueLifecycleState.NEEDS_REVIEW);
        assertThat(outcomes.findAll()).as("the window is anchored either way").hasSize(1);
    }

    @Test
    @DisplayName("nothing can be applied that was not prepared")
    void onlyAPreparedDraftCanBeCarriedOut() {
        assertThatThrownBy(() -> service.apply(org, seller, issue.getId(),
                OpportunityKind.PRODUCT_GUIDE_SUPPLEMENT, APPLIED_ON, AppliedArtifact.PRODUCT_KNOWLEDGE, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("초안이 준비된 기회에서만");
        assertThat(outcomes.findAll()).isEmpty();
    }

    // ── 2. reading the window ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the window is read once it closes, and not before")
    void theWindowIsReadWhenItCloses() {
        evidenceOn(APPLIED_ON.minusDays(10), 12);
        reviewsOn(APPLIED_ON.minusDays(10), 60);
        accepted(OpportunityKind.PRODUCT_GUIDE_SUPPLEMENT);
        service.apply(org, seller, issue.getId(), OpportunityKind.PRODUCT_GUIDE_SUPPLEMENT, APPLIED_ON,
                AppliedArtifact.PRODUCT_KNOWLEDGE, null);

        // After: reviews still arriving, complaints down to two.
        reviewsOn(APPLIED_ON.plusDays(5), 55);
        evidenceOn(APPLIED_ON.plusDays(5), 2);

        assertThat(outcomeService.read(org, WINDOW_CLOSED.minusDays(1)))
                .as("an open window is not a result").isZero();
        assertThat(outcomeService.read(org, WINDOW_CLOSED)).isEqualTo(1);
        assertThat(outcomeService.read(org, WINDOW_CLOSED))
                .as("a settled verdict is never read twice — it would be a different measurement under one name")
                .isZero();

        ImprovementOutcome read = outcomes.findAll().get(0);
        assertThat(read.getVerdict()).isEqualTo(OutcomeVerdict.IMPROVED);
        assertThat(read.getReason()).isEqualTo(OutcomeReason.EVIDENCE_DOWN);
        assertThat(read.getObservedEvidence()).isEqualTo(2);
        assertThat(read.getObservedReviews()).isEqualTo(57);
        assertThat(read.getObservedThrough()).isEqualTo(WINDOW_CLOSED);
        assertThat(read.getEvaluatedAt()).isNotNull();
    }

    @Test
    @DisplayName("a quiet window with no reviews in it is 판단 보류, never 개선 — and the counts are still there")
    void theFalseCalmIsRefused() {
        evidenceOn(APPLIED_ON.minusDays(10), 12);
        reviewsOn(APPLIED_ON.minusDays(10), 60);
        accepted(OpportunityKind.PRODUCT_GUIDE_SUPPLEMENT);
        service.apply(org, seller, issue.getId(), OpportunityKind.PRODUCT_GUIDE_SUPPLEMENT, APPLIED_ON,
                AppliedArtifact.PRODUCT_KNOWLEDGE, null);
        // Nothing arrives at all — a collection gap, a holiday, a connection that lapsed.

        outcomeService.read(org, WINDOW_CLOSED);

        ImprovementOutcome read = outcomes.findAll().get(0);
        assertThat(read.getVerdict()).isEqualTo(OutcomeVerdict.INCONCLUSIVE);
        assertThat(read.getReason()).isEqualTo(OutcomeReason.NO_COVERAGE);
        assertThat(read.getBaselineEvidence()).as("the counts survive a withheld verdict").isEqualTo(12);
        assertThat(read.getObservedEvidence()).isZero();
        assertThat(read.getObservedReviews()).isZero();
    }

    @Test
    @DisplayName("a PRODUCT remediation is measured in its product's reviews, not the company's")
    void theScopeIsThePopulation() {
        evidenceOn(APPLIED_ON.minusDays(10), 12);
        reviewsOn(APPLIED_ON.minusDays(10), 60);
        // Another product's reviews in the same window — part of the company, not of this measurement.
        Product other = new Product();
        other.setOrgId(org);
        other.setName("다른 상품");
        other.setStatus("ACTIVE");
        UUID otherId = products.save(other).getId();
        for (int i = 0; i < 200; i++) {
            review(APPLIED_ON.plusDays(5), otherId);
        }
        accepted(OpportunityKind.PRODUCT_GUIDE_SUPPLEMENT);
        service.apply(org, seller, issue.getId(), OpportunityKind.PRODUCT_GUIDE_SUPPLEMENT, APPLIED_ON,
                AppliedArtifact.PRODUCT_KNOWLEDGE, null);
        outcomeService.read(org, WINDOW_CLOSED);

        ImprovementOutcome read = outcomes.findAll().get(0);
        assertThat(read.getObservedReviews())
                .as("the other product's 200 reviews are not this remediation's denominator")
                .isZero();
        assertThat(read.getVerdict()).isEqualTo(OutcomeVerdict.INCONCLUSIVE);
        assertThat(read.getReason()).isEqualTo(OutcomeReason.NO_COVERAGE);
    }

    // ── 3. the record outlives the suggestion ────────────────────────────────────────────────────

    @Test
    @DisplayName("the record of what was done is readable by the issue, so it survives the rules no longer deriving the kind")
    void theRecordIsReadByTheProblem() {
        evidenceOn(APPLIED_ON.minusDays(10), 12);
        reviewsOn(APPLIED_ON.minusDays(10), 60);
        accepted(OpportunityKind.OPERATING_POLICY_SUPPLEMENT);
        service.apply(org, seller, issue.getId(), OpportunityKind.OPERATING_POLICY_SUPPLEMENT, APPLIED_ON,
                AppliedArtifact.ORG_KNOWLEDGE, UUID.randomUUID());

        assertThat(service.outcomes(org, issue.getId()))
                .singleElement()
                .satisfies(view -> {
                    assertThat(view.kind()).isEqualTo("OPERATING_POLICY_SUPPLEMENT");
                    assertThat(view.scope()).as("a company rule is measured company-wide").isEqualTo("ORG");
                    assertThat(view.appliedOn()).isEqualTo(APPLIED_ON);
                    assertThat(view.settled()).isFalse();
                    assertThat(view.evidenceBefore()).isEqualTo(12);
                });
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────────

    private ReviewIssueView view() {
        return OpportunityFixtures.issue(issue.getId(), "접착", "탈락", 12, productId, "NEEDS_REVIEW", false);
    }

    private ImprovementOpportunity accepted(OpportunityKind kind) {
        ImprovementOpportunity row = new ImprovementOpportunity();
        row.setOrgId(org);
        row.setIssueId(issue.getId());
        row.setKind(kind);
        row.setStatus(OpportunityStatus.ACCEPTED);
        row.setDraftTitle("접착 안내");
        row.setDraftBody("부착 전 표면의 먼지를 닦아 주세요.");
        row.setDecidedAt(Instant.parse("2026-08-30T00:00:00Z"));
        return decisions.save(row);
    }

    /** {@code count} evidence rows for this issue, on one day, attributed to the product. */
    private void evidenceOn(LocalDate day, int count) {
        for (int i = 0; i < count; i++) {
            Review r = review(day, productId);
            ReviewIssueEvidence e = new ReviewIssueEvidence();
            e.setOrgId(org);
            e.setIssueId(issue.getId());
            e.setReviewId(r.getId());
            e.setUnitOrdinal(0);
            e.setProductId(productId);
            e.setOccurredOn(day);
            e.setMatchConfidence(MatchConfidence.EXACT_SIGNATURE);
            evidence.save(e);
        }
    }

    /** {@code count} reviews of this product on one day, carrying no issue evidence — the denominator. */
    private void reviewsOn(LocalDate day, int count) {
        for (int i = 0; i < count; i++) {
            review(day, productId);
        }
    }

    private Review review(LocalDate day, UUID product) {
        Review r = new Review();
        r.setOrgId(org);
        r.setChannelId(channelId);
        r.setProductId(product);
        r.setExternalId(UUID.randomUUID().toString());
        r.setRating(3);
        r.setBody("접착이 떨어졌습니다");
        r.setReceivedAt(day.atStartOfDay(ZoneOffset.UTC).toInstant());
        return reviews.save(r);
    }
}
