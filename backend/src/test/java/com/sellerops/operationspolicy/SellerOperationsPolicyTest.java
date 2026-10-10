package com.sellerops.operationspolicy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sellerops.common.ApiException;
import com.sellerops.operationscase.CaseDecider;
import com.sellerops.operationscase.CaseDisposition;
import com.sellerops.operationscase.CaseEventKind;
import com.sellerops.operationscase.CaseReason;
import com.sellerops.operationscase.OperationsCase;
import com.sellerops.operationscase.OperationsCaseEvent;
import com.sellerops.operationscase.OperationsCaseEventRepository;
import com.sellerops.operationscase.OperationsCaseKind;
import com.sellerops.operationscase.OperationsCaseProcessor;
import com.sellerops.operationscase.OperationsCaseRepository;
import com.sellerops.operationscase.OperationsCaseStatus;
import com.sellerops.operationscase.OperationsSubjectKind;
import com.sellerops.operationscase.RecommendedActionType;
import com.sellerops.operationscase.RequiredAuthority;
import com.sellerops.product.Product;
import com.sellerops.product.ProductRepository;
import com.sellerops.review.Review;
import com.sellerops.review.ReviewRepository;
import com.sellerops.reviewissue.IssueSignatureExtractor;
import com.sellerops.reviewissue.ReviewIssueEvidenceRepository;
import com.sellerops.reviewissue.ReviewIssueExtractionService;
import com.sellerops.reviewissue.ReviewIssueRepository;
import com.sellerops.reviewissue.ReviewIssueStateEventRepository;
import com.sellerops.reviewissue.ReviewIssueUnknownUnitRepository;
import com.sellerops.reviewissue.RuleBasedIssueSignatureExtractor;
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
 * Seller-declared Operations Policy v1 — the rule, its fences, and what a change does to open work.
 *
 * <p>Real Postgres and the real issue extraction, because the whole claim is that «같은 문제» means the same thing
 * on the policy screen as in the issue memory: the key under test is the one
 * {@code RuleBasedIssueSignatureExtractor} actually writes, not one this test invented.
 *
 * <p>The processor is used only through {@link OperationsCaseProcessor#applyPolicy} — the same method a run calls
 * and the only one a policy ever reaches a case through. Nothing here constructs a run, a responsibility or an
 * investigator, which is also the point: the overlay is deterministic and costs no model.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class SellerOperationsPolicyTest {

    @Autowired SellerOperationsPolicyRepository policies;
    @Autowired SellerOperationsPolicyAuditRepository audits;
    @Autowired ProductRepository products;
    @Autowired ReviewRepository reviews;
    @Autowired ReviewIssueRepository issues;
    @Autowired ReviewIssueEvidenceRepository evidence;
    @Autowired ReviewIssueUnknownUnitRepository unknowns;
    @Autowired ReviewIssueStateEventRepository stateEvents;
    @Autowired OperationsCaseRepository cases;
    @Autowired OperationsCaseEventRepository events;

    private static final LocalDate REF = LocalDate.of(2026, 10, 1);

    private final UUID org = UUID.randomUUID();
    private final UUID channel = UUID.randomUUID();
    private final UUID actor = UUID.randomUUID();

    private ReviewIssueExtractionService extraction;
    private SellerOperationsPolicyService service;
    private SellerPolicyOverlay overlay;
    private OperationsPolicyRedecider redecider;
    private OperationsCaseProcessor processor;

    @BeforeEach
    void setUp() {
        IssueSignatureExtractor extractor = new RuleBasedIssueSignatureExtractor(false);
        extraction = new ReviewIssueExtractionService(extractor, issues, evidence, unknowns, stateEvents);
        service = new SellerOperationsPolicyService(policies, audits, products);
        overlay = new SellerPolicyOverlay(policies, evidence);
        processor = processorWithOverlay(overlay);
        redecider = new OperationsPolicyRedecider(cases, events, processor);
    }

    /* ─────────────────────────── the key is the issue memory's ─────────────────────────── */

    @Test
    @DisplayName("the policy's key is the key the extractor writes — one vocabulary, not two")
    void theKeyIsTheIssueMemorysOwn() {
        UUID product = product("전선몰딩");
        extraction.extract(review("배송이 너무 늦게 왔어요", product));

        SellerOperationsPolicy policy = declared(OperationsPolicyScope.ORG, null, "배송", "지연",
                RecommendedActionType.REPLY_TO_CUSTOMER);

        assertThat(policy.getSignatureKey()).isEqualTo("배송:지연");
        assertThat(issues.findByOrgIdAndSignatureKey(org, "배송:지연")).isPresent();
        assertThat(overlay.forReview(org, reviewOf("배송이 너무 늦게 왔어요").getId(), product))
                .map(SellerPolicyOverlay.Applied::problem).contains("배송:지연");
    }

    @Test
    @DisplayName("a problem outside the vocabulary is refused, not stored as a rule that matches nothing")
    void anUnknownProblemIsRefused() {
        assertThatThrownBy(() -> declared(OperationsPolicyScope.ORG, null, "배송", "느낌이이상함",
                RecommendedActionType.REPLY_TO_CUSTOMER))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("문제 종류");
    }

    /* ─────────────────────────── the fences at write time ─────────────────────────── */

    @Test
    @DisplayName("PRODUCT scope that cannot bind is REFUSED — never silently widened to the whole company")
    void productScopeIsNeverWidened() {
        // No product named at all.
        assertThatThrownBy(() -> declared(OperationsPolicyScope.PRODUCT, null, "배송", "지연",
                RecommendedActionType.REPLY_TO_CUSTOMER))
                .isInstanceOf(ApiException.class).hasMessageContaining("상품을 지정해 주세요");

        // A product with no operator-visible name — the exact case `seller_guidance` turns into an ORG row.
        Product nameless = new Product();
        nameless.setOrgId(org);
        nameless.setStatus("ACTIVE");
        UUID namelessId = products.save(nameless).getId();
        assertThatThrownBy(() -> declared(OperationsPolicyScope.PRODUCT, namelessId, "배송", "지연",
                RecommendedActionType.REPLY_TO_CUSTOMER))
                .isInstanceOf(ApiException.class).hasMessageContaining("상품 이름이 확인되는");

        // Another org's product.
        Product theirs = new Product();
        theirs.setOrgId(UUID.randomUUID());
        theirs.setName("남의 상품");
        theirs.setStatus("ACTIVE");
        UUID theirsId = products.save(theirs).getId();
        assertThatThrownBy(() -> declared(OperationsPolicyScope.PRODUCT, theirsId, "배송", "지연",
                RecommendedActionType.REPLY_TO_CUSTOMER))
                .isInstanceOf(ApiException.class);

        // Nothing was stored by any of the three. A refusal that left a row would be the widening by another name.
        assertThat(policies.findByOrgIdOrderByDeclaredAtDesc(org)).isEmpty();
    }

    @Test
    @DisplayName("an ORG rule may not name a product either — the scope and the product are one fact")
    void orgScopeRefusesAProduct() {
        UUID product = product("전선몰딩");
        assertThatThrownBy(() -> declared(OperationsPolicyScope.ORG, product, "배송", "지연",
                RecommendedActionType.REPLY_TO_CUSTOMER))
                .isInstanceOf(ApiException.class).hasMessageContaining("상품을 지정할 수 없습니다");
    }

    @Test
    @DisplayName("only an action that still needs a person may be a rule — both AUTO actions are refused")
    void autoActionsAreRefused() {
        for (RecommendedActionType auto : List.of(RecommendedActionType.NO_ACTION,
                RecommendedActionType.MONITOR_REPEAT_ISSUE)) {
            assertThatThrownBy(() -> declared(OperationsPolicyScope.ORG, null, "배송", "지연", auto))
                    .as("policy action %s", auto)
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("사람 확인 없이 끝내도록");
        }
        assertThat(OperationsPolicyFence.allowedActions())
                .containsExactly(RecommendedActionType.REPLY_TO_CUSTOMER, RecommendedActionType.CONTACT_CUSTOMER,
                        RecommendedActionType.REFUND_OR_COMPENSATION, RecommendedActionType.CANCEL_OR_EXCHANGE,
                        RecommendedActionType.ADD_KNOWLEDGE, RecommendedActionType.REVIEW_PRODUCT_LISTING);
        assertThat(OperationsPolicyFence.allowedActions())
                .allSatisfy(action -> assertThat(action.authority()).isEqualTo(RequiredAuthority.HUMAN));
    }

    /* ─────────────────────────── revision and retirement ─────────────────────────── */

    @Test
    @DisplayName("re-declaring the same key revises the rule; an unchanged save moves nothing")
    void reDeclaringRevises() {
        SellerOperationsPolicy first = declared(OperationsPolicyScope.ORG, null, "배송", "지연",
                RecommendedActionType.REPLY_TO_CUSTOMER);
        assertThat(first.getVersion()).isEqualTo(1);

        SellerOperationsPolicyService.Declared again = declare(OperationsPolicyScope.ORG, null, "배송", "지연",
                RecommendedActionType.REPLY_TO_CUSTOMER);
        assertThat(again.changed()).as("nothing moved, so no version bump and no re-decision").isFalse();
        assertThat(again.policy().getVersion()).isEqualTo(1);

        SellerOperationsPolicyService.Declared changed = declare(OperationsPolicyScope.ORG, null, "배송", "지연",
                RecommendedActionType.REFUND_OR_COMPENSATION);
        assertThat(changed.changed()).isTrue();
        assertThat(changed.policy().getVersion()).isEqualTo(2);
        assertThat(changed.policy().getId()).as("a revision, not a second rule").isEqualTo(first.getId());
        assertThat(policies.findByOrgIdAndActiveTrueOrderByDeclaredAtDesc(org)).hasSize(1);

        assertThat(audits.findByPolicyIdOrderByDecidedAtAsc(first.getId()))
                .extracting(row -> row.getKind().name() + ":" + row.getActionFrom() + "→" + row.getActionTo())
                .containsExactly("DECLARED:null→REPLY_TO_CUSTOMER",
                        "CHANGED:REPLY_TO_CUSTOMER→REFUND_OR_COMPENSATION");
    }

    @Test
    @DisplayName("retiring frees the key and keeps the trail; retiring twice writes nothing")
    void retiringFreesTheKey() {
        SellerOperationsPolicy first = declared(OperationsPolicyScope.ORG, null, "배송", "지연",
                RecommendedActionType.REPLY_TO_CUSTOMER);

        assertThat(service.retire(org, first.getId(), actor).changed()).isTrue();
        assertThat(service.retire(org, first.getId(), actor).changed())
                .as("a second withdrawal is not a second decision").isFalse();

        SellerOperationsPolicy second = declared(OperationsPolicyScope.ORG, null, "배송", "지연",
                RecommendedActionType.CONTACT_CUSTOMER);
        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(policies.findByOrgIdAndActiveTrueOrderByDeclaredAtDesc(org)).hasSize(1);
        assertThat(policies.findByOrgIdOrderByDeclaredAtDesc(org)).as("the retired rule is kept").hasSize(2);
        assertThat(audits.findByPolicyIdOrderByDecidedAtAsc(first.getId()))
                .extracting(row -> row.getKind().name()).containsExactly("DECLARED", "RETIRED");
    }

    /* ─────────────────────────── the overlay's precedence ─────────────────────────── */

    @Test
    @DisplayName("a rule written for this product beats the company-wide one")
    void productBeatsOrg() {
        UUID product = product("전선몰딩");
        Review review = extracted("배송이 너무 늦게 왔어요", product);
        declared(OperationsPolicyScope.ORG, null, "배송", "지연", RecommendedActionType.REPLY_TO_CUSTOMER);
        declared(OperationsPolicyScope.PRODUCT, product, "배송", "지연",
                RecommendedActionType.REFUND_OR_COMPENSATION);

        assertThat(overlay.forReview(org, review.getId(), product))
                .map(SellerPolicyOverlay.Applied::action)
                .contains(RecommendedActionType.REFUND_OR_COMPENSATION);
    }

    @Test
    @DisplayName("a PRODUCT rule reaches its own product only, and a review with no product reaches ORG only")
    void productRulesDoNotStretch() {
        UUID mine = product("전선몰딩");
        UUID other = product("걸레받이");
        Review onOther = extracted("배송이 너무 늦게 왔어요", other);
        Review unbound = extracted("배송이 또 늦었어요", null);
        declared(OperationsPolicyScope.PRODUCT, mine, "배송", "지연",
                RecommendedActionType.REFUND_OR_COMPENSATION);

        assertThat(overlay.forReview(org, onOther.getId(), other)).isEmpty();
        assertThat(overlay.forReview(org, unbound.getId(), null)).isEmpty();

        declared(OperationsPolicyScope.ORG, null, "배송", "지연", RecommendedActionType.REPLY_TO_CUSTOMER);
        assertThat(overlay.forReview(org, unbound.getId(), null))
                .map(SellerPolicyOverlay.Applied::scope).contains(OperationsPolicyScope.ORG);
    }

    @Test
    @DisplayName("a dismissed problem is not a rule's business — 「중요하지 않음」 is not reopened by the back door")
    void aDismissedIssueMatchesNothing() {
        UUID product = product("전선몰딩");
        Review review = extracted("배송이 너무 늦게 왔어요", product);
        declared(OperationsPolicyScope.ORG, null, "배송", "지연", RecommendedActionType.REPLY_TO_CUSTOMER);
        assertThat(overlay.forReview(org, review.getId(), product)).isPresent();

        issues.findByOrgIdAndSignatureKey(org, "배송:지연").ifPresent(issue -> {
            issue.setDismissed(true);
            issues.save(issue);
        });

        assertThat(overlay.forReview(org, review.getId(), product)).isEmpty();
    }

    @Test
    @DisplayName("a review the extractor recognised nothing in matches no rule at all")
    void noEvidenceNoRule() {
        UUID product = product("전선몰딩");
        Review quiet = extracted("잘 받았습니다", product);
        declared(OperationsPolicyScope.ORG, null, "배송", "지연", RecommendedActionType.REPLY_TO_CUSTOMER);

        assertThat(overlay.forReview(org, quiet.getId(), product)).isEmpty();
    }

    /* ─────────────────────────── what it does to a case, and what it may not ─────────────────────────── */

    @Test
    @DisplayName("the rule sets the handling, stamps SELLER, and leaves every fence exactly where it was")
    void applyingAPolicyChangesHandlingOnly() {
        UUID product = product("전선몰딩");
        Review review = extracted("배송이 너무 늦게 왔어요", product);
        declared(OperationsPolicyScope.ORG, null, "배송", "지연",
                RecommendedActionType.REFUND_OR_COMPENSATION);
        OperationsCase c = openCase(review, product);

        assertThat(processor.applyPolicy(c, null)).isTrue();

        assertThat(c.getRecommendedActionType()).isEqualTo(RecommendedActionType.REFUND_OR_COMPENSATION);
        assertThat(c.getDecidedBy()).isEqualTo(CaseDecider.SELLER);
        // The fences. A rule chooses the handling; it never decides that nobody has to carry it.
        assertThat(c.getDisposition()).isEqualTo(CaseDisposition.NEEDS_DECISION);
        assertThat(c.getRequiredAuthority()).isEqualTo(RequiredAuthority.HUMAN);
        assertThat(c.getStatus()).isEqualTo(OperationsCaseStatus.PREPARED);
        assertThat(c.getClosedAt()).isNull();
        assertThat(c.getConfidence()).as("a rule is not an investigation and borrows none of its furniture").isNull();

        assertThat(eventsOf(c)).extracting(OperationsCaseEvent::getKind).contains(CaseEventKind.POLICY_APPLIED);
        assertThat(eventsOf(c)).filteredOn(e -> e.getKind() == CaseEventKind.POLICY_APPLIED)
                .allSatisfy(e -> assertThat(e.getProvenance())
                        .contains("\"problem\":\"배송:지연\"")
                        .contains("\"scope\":\"ORG\"")
                        .contains("\"policyVersion\":1"));
    }

    @Test
    @DisplayName("an inquiry case is never policy-decided — the key is the review issue memory's and it has none")
    void inquiryCasesAreNotReached() {
        declared(OperationsPolicyScope.ORG, null, "배송", "지연", RecommendedActionType.REPLY_TO_CUSTOMER);
        OperationsCase c = openCase(null, null);
        c.setSubjectKind(OperationsSubjectKind.INQUIRY);
        c.setSubjectId(UUID.randomUUID());

        assertThat(processor.applyPolicy(c, null)).isFalse();
        assertThat(c.getDecidedBy()).isEqualTo(CaseDecider.RULE);
    }

    @Test
    @DisplayName("with no overlay bean a case is decided exactly as it was before this package existed")
    void noOverlayNoChange() {
        UUID product = product("전선몰딩");
        Review review = extracted("배송이 너무 늦게 왔어요", product);
        declared(OperationsPolicyScope.ORG, null, "배송", "지연", RecommendedActionType.REPLY_TO_CUSTOMER);
        OperationsCase c = openCase(review, product);

        assertThat(processorWithOverlay(null).applyPolicy(c, null)).isFalse();
        assertThat(c.getRecommendedActionType()).isNull();
        assertThat(c.getDecidedBy()).isEqualTo(CaseDecider.RULE);
    }

    /* ─────────────────────────── the re-decision, and only matching open work ─────────────────────────── */

    @Test
    @DisplayName("declaring a rule re-decides the matching open cards and touches nothing else")
    void aChangeRedecidesMatchingOpenCasesOnly() {
        UUID product = product("전선몰딩");
        OperationsCase matching = openCase(extracted("배송이 너무 늦게 왔어요", product), product);
        OperationsCase otherProblem = openCase(extracted("표면이 오염되어 있었어요", product), product);
        OperationsCase closed = openCase(extracted("배송이 또 늦었어요", product), product);
        closed.setStatus(OperationsCaseStatus.CLOSED);
        cases.saveAndFlush(closed);

        SellerOperationsPolicyService.Declared declared = declare(OperationsPolicyScope.ORG, null, "배송", "지연",
                RecommendedActionType.CONTACT_CUSTOMER);
        OperationsPolicyRedecider.Report moved = redecider.redecide(declared.policy());

        assertThat(moved.redecided()).isEqualTo(1);
        assertThat(moved.cleared()).isZero();
        assertThat(reload(matching).getRecommendedActionType()).isEqualTo(RecommendedActionType.CONTACT_CUSTOMER);
        assertThat(reload(matching).getDecidedBy()).isEqualTo(CaseDecider.SELLER);
        // A rule about 배송:지연 is not an occasion to touch a 표면:오염 card.
        assertThat(reload(otherProblem).getRecommendedActionType()).isNull();
        assertThat(reload(otherProblem).getDecidedBy()).isEqualTo(CaseDecider.RULE);
        // What was decided was decided under the revision the trail records; a closed case is not reopened.
        assertThat(reload(closed).getRecommendedActionType()).isNull();
        assertThat(reload(closed).getStatus()).isEqualTo(OperationsCaseStatus.CLOSED);
    }

    @Test
    @DisplayName("a PRODUCT rule re-decides its own product's cards only")
    void aProductRuleRedecidesItsOwnProductOnly() {
        UUID mine = product("전선몰딩");
        UUID other = product("걸레받이");
        OperationsCase onMine = openCase(extracted("배송이 너무 늦게 왔어요", mine), mine);
        OperationsCase onOther = openCase(extracted("배송이 또 늦었어요", other), other);

        SellerOperationsPolicyService.Declared declared = declare(OperationsPolicyScope.PRODUCT, mine, "배송", "지연",
                RecommendedActionType.REFUND_OR_COMPENSATION);
        assertThat(redecider.redecide(declared.policy()).redecided()).isEqualTo(1);

        assertThat(reload(onMine).getRecommendedActionType())
                .isEqualTo(RecommendedActionType.REFUND_OR_COMPENSATION);
        assertThat(reload(onOther).getRecommendedActionType()).isNull();
    }

    @Test
    @DisplayName("retiring a rule takes its answer off the cards it decided, and leaves the card the seller's")
    void retiringClearsTheCardsItDecided() {
        UUID product = product("전선몰딩");
        OperationsCase c = openCase(extracted("배송이 너무 늦게 왔어요", product), product);
        SellerOperationsPolicy policy = declared(OperationsPolicyScope.ORG, null, "배송", "지연",
                RecommendedActionType.CONTACT_CUSTOMER);
        redecider.redecide(policy);
        assertThat(reload(c).getRecommendedActionType()).isEqualTo(RecommendedActionType.CONTACT_CUSTOMER);

        SellerOperationsPolicyService.Declared retired = service.retire(org, policy.getId(), actor);
        OperationsPolicyRedecider.Report moved = redecider.redecide(retired.policy());

        assertThat(moved.cleared()).isEqualTo(1);
        OperationsCase after = reload(c);
        assertThat(after.getRecommendedActionType()).as("the withdrawn rule's answer is gone").isNull();
        assertThat(after.getDecidedBy()).isEqualTo(CaseDecider.RULE);
        // Still the seller's move, and still open. A retirement closes nothing.
        assertThat(after.getDisposition()).isEqualTo(CaseDisposition.NEEDS_DECISION);
        assertThat(after.getRequiredAuthority()).isEqualTo(RequiredAuthority.HUMAN);
        assertThat(after.getStatus()).isEqualTo(OperationsCaseStatus.PREPARED);
        assertThat(eventsOf(after)).extracting(OperationsCaseEvent::getKind)
                .contains(CaseEventKind.POLICY_REDECIDED);
    }

    @Test
    @DisplayName("a retirement never touches a card the AGENT concluded — that conclusion was never this rule's")
    void retiringLeavesAgentConclusionsAlone() {
        UUID product = product("전선몰딩");
        OperationsCase c = openCase(extracted("배송이 너무 늦게 왔어요", product), product);
        c.setDecidedBy(CaseDecider.AGENT);
        c.setRecommendedActionType(RecommendedActionType.REPLY_TO_CUSTOMER);
        cases.saveAndFlush(c);

        SellerOperationsPolicy policy = declared(OperationsPolicyScope.ORG, null, "배송", "지연",
                RecommendedActionType.CONTACT_CUSTOMER);
        SellerOperationsPolicyService.Declared retired = service.retire(org, policy.getId(), actor);

        assertThat(redecider.redecide(retired.policy()).touched()).isZero();
        assertThat(reload(c).getDecidedBy()).isEqualTo(CaseDecider.AGENT);
        assertThat(reload(c).getRecommendedActionType()).isEqualTo(RecommendedActionType.REPLY_TO_CUSTOMER);
    }

    /* ─────────────────────────── fixtures ─────────────────────────── */

    private OperationsCaseProcessor processorWithOverlay(SellerPolicyOverlay withOverlay) {
        OperationsCaseProcessor built = new OperationsCaseProcessor(null, null, null, null, null,
                cases, events, null, null, null, null, null, null);
        built.setPolicyOverlay(withOverlay);
        return built;
    }

    private UUID product(String name) {
        Product product = new Product();
        product.setOrgId(org);
        product.setName(name);
        product.setStatus("ACTIVE");
        return products.save(product).getId();
    }

    private Review review(String body, UUID productId) {
        Review review = new Review();
        review.setOrgId(org);
        review.setChannelId(channel);
        review.setProductId(productId);
        review.setBody(body);
        review.setRating(2);
        review.setReceivedAt(REF.atStartOfDay(ZoneOffset.UTC).toInstant());
        return reviews.save(review);
    }

    /** A review the real extraction has run over, so its issue evidence is the production shape. */
    private Review extracted(String body, UUID productId) {
        Review saved = review(body, productId);
        extraction.extract(saved);
        return saved;
    }

    private Review reviewOf(String body) {
        return reviews.findAll().stream().filter(r -> body.equals(r.getBody())).findFirst().orElseThrow();
    }

    /** A case in the shape the rules leave one in when they could not settle it: the seller's move, open. */
    private OperationsCase openCase(Review review, UUID productId) {
        OperationsCase c = new OperationsCase();
        c.setOrgId(org);
        c.setResponsibilityId(UUID.randomUUID());
        c.setCaseKind(OperationsCaseKind.CUSTOMER_WORK);
        c.setSubjectKind(OperationsSubjectKind.REVIEW);
        c.setSubjectId(review == null ? UUID.randomUUID() : review.getId());
        c.setProductId(productId);
        c.setSignature(UUID.randomUUID().toString().replace("-", ""));
        c.setSourceState("rr:review;test");
        c.setStatus(OperationsCaseStatus.PREPARED);
        c.setPriority(com.sellerops.operationscase.CasePriority.NORMAL);
        c.setReason(CaseReason.REVIEW_NEEDS_ATTENTION);
        c.setReasonNote(CaseReason.REVIEW_NEEDS_ATTENTION.noteKo());
        c.setPreparedAction(com.sellerops.operationscase.CasePreparedAction.NONE);
        c.setDisposition(CaseDisposition.NEEDS_DECISION);
        c.setRequiredAuthority(RequiredAuthority.HUMAN);
        c.setDecidedBy(CaseDecider.RULE);
        c.setOriginRunId(null);
        c.setSurfacedAt(Instant.now());
        return cases.saveAndFlush(c);
    }

    private OperationsCase reload(OperationsCase c) {
        return cases.findByIdAndOrgId(c.getId(), org).orElseThrow();
    }

    private List<OperationsCaseEvent> eventsOf(OperationsCase c) {
        return events.findAll().stream().filter(e -> c.getId().equals(e.getCaseId())).toList();
    }

    private SellerOperationsPolicy declared(OperationsPolicyScope scope, UUID productId, String aspect,
                                            String problem, RecommendedActionType action) {
        return declare(scope, productId, aspect, problem, action).policy();
    }

    private SellerOperationsPolicyService.Declared declare(OperationsPolicyScope scope, UUID productId,
                                                           String aspect, String problem,
                                                           RecommendedActionType action) {
        return service.declare(new SellerOperationsPolicyService.Declaration(org, scope, productId, aspect,
                problem, action, null, actor));
    }
}
