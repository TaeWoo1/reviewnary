package com.sellerops.review.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sellerops.attention.reply.ReviewReplyDraftRepository;
import com.sellerops.knowledge.claim.ClaimAttribute;
import com.sellerops.knowledge.claim.ClaimCondition;
import com.sellerops.knowledge.claim.ClaimReader;
import com.sellerops.knowledge.currency.KnowledgeCurrencyService;
import com.sellerops.knowledge.currency.KnowledgeState;
import com.sellerops.knowledge.org.OrgKnowledgeSource;
import com.sellerops.knowledge.org.OrgKnowledgeSourceRepository;
import com.sellerops.product.library.ProductKnowledgeSource;
import com.sellerops.product.library.ProductKnowledgeSourceRepository;
import com.sellerops.review.Review;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The review twin of {@code InquiryDraftRecheckStateSupportTest} (2026-11-15 review P1: 문의 · 리뷰 모두): a figure the
 * material does not stand behind keeps every word-level finding it had, and a review — which names no order — is read with
 * no order date, as its search was.
 */
class ReviewDraftRecheckStateSupportTest {

    private final UUID org = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();
    private final ReviewDraftComposer composer = mock(ReviewDraftComposer.class);
    private final ReviewReplyDraftRepository drafts = mock(ReviewReplyDraftRepository.class);
    private final ReviewDraftEvidenceRepository evidence = mock(ReviewDraftEvidenceRepository.class);
    private final ProductKnowledgeSourceRepository productSources = mock(ProductKnowledgeSourceRepository.class);
    private final OrgKnowledgeSourceRepository orgSources = mock(OrgKnowledgeSourceRepository.class);
    private final KnowledgeCurrencyService currency = mock(KnowledgeCurrencyService.class);
    private final List<OrgKnowledgeSource> orgDocs = new ArrayList<>();
    private final List<ProductKnowledgeSource> productDocs = new ArrayList<>();
    private final List<KnowledgeState.Source> stateSources = new ArrayList<>();
    private final List<KnowledgeState.Replacement> replacements = new ArrayList<>();
    private final List<String> license = new ArrayList<>();
    private final Review review = new Review();
    private ReviewDraftRecheck recheck;

    @BeforeEach
    void setUp() {
        recheck = new ReviewDraftRecheck(composer, drafts, evidence, productSources, orgSources);
        recheck.setCurrency(currency);
        review.setId(UUID.randomUUID());
        review.setProductId(productId);
        when(drafts.findTopByReviewIdAndContentFingerprintOrderByVersionDesc(any(), any())).thenReturn(Optional.empty());
        when(composer.currentLicense(eq(org), any(), any())).thenReturn(license);
        when(orgSources.findAllByOrgIdOrderByCreatedAtAsc(org)).thenReturn(orgDocs);
        when(productSources.findAllByOrgIdAndProductIdOrderByCreatedAtAsc(org, productId)).thenReturn(productDocs);
        when(currency.stateFor(eq(org), eq(productId), any(), isNull())).thenAnswer(inv -> KnowledgeState.of(
                List.copyOf(stateSources), List.copyOf(replacements), inv.getArgument(2), null));
    }

    private UUID orgDoc(String title, String body) {
        OrgKnowledgeSource s = new OrgKnowledgeSource();
        s.setId(UUID.randomUUID());
        s.setOrgId(org);
        s.setTitle(title);
        s.setBody(body);
        orgDocs.add(s);
        license.add(title + "\n" + body);
        stateSources.add(new KnowledgeState.Source(s.getId(), "ORG", null, "ORG", title, 1, null,
                ClaimReader.read(body, title)));
        return s.getId();
    }

    private void productDoc(String title, String body) {
        ProductKnowledgeSource s = new ProductKnowledgeSource();
        s.setId(UUID.randomUUID());
        s.setOrgId(org);
        s.setProductId(productId);
        s.setTitle(title);
        s.setBody(body);
        productDocs.add(s);
        stateSources.add(new KnowledgeState.Source(s.getId(), "PRODUCT", productId, "PRODUCT:" + productId, title, 1,
                null, ClaimReader.read(body, title)));
    }

    private List<String> codes(String reply) {
        return recheck.check(org, review, "교환 기한이 궁금해요", reply).codes();
    }

    private List<String> codesWithoutState(String reply) {
        return recheck.checkWithoutState(org, review, "교환 기한이 궁금해요", reply).codes();
    }

    @Test
    @DisplayName("one current 7일 and a reply's 14일: the conflict stays with the state read")
    void aSingleCurrentPolicyStillConflicts() {
        orgDoc("현행 정책", "교환은 수령 후 7일 이내에 신청해 주세요.");
        String reply = "교환은 수령 후 14일 이내에 신청해 주세요.";
        assertThat(codesWithoutState(reply)).contains(ReviewDraftRecheck.POLICY_CONFLICT);
        assertThat(codes(reply)).contains(ReviewDraftRecheck.POLICY_CONFLICT);
    }

    @Test
    void splitCostBearerConflictCannotBecomeAnException() {
        orgDoc("반품 배송비", "단순 변심 반품의 왕복 배송비 6,000원은 고객님이 부담합니다.");
        String reply = "단순 변심 반품은 수령 후 7일 안에 신청해 주세요. 왕복 배송비 6,000원은 저희가 부담합니다.";
        assertThat(codesWithoutState(reply)).contains(ReviewDraftRecheck.POLICY_CONFLICT);
        assertThat(codes(reply)).contains(ReviewDraftRecheck.POLICY_CONFLICT);
    }

    @Test
    @DisplayName("a third value after a replacement conflicts; the current value passes")
    void aThirdValue() {
        UUID now = orgDoc("교환·반품 안내", "교환은 수령 후 7일 이내에 신청해 주세요.");
        UUID old = orgDoc("옛 공지", "교환은 수령 후 14일 이내에 신청해 주세요.");
        replacements.add(new KnowledgeState.Replacement(UUID.randomUUID(), now, old, ClaimAttribute.EXCHANGE_WINDOW,
                ClaimCondition.GENERAL));
        assertThat(codes("교환은 수령 후 21일 안에 신청해 주세요.")).contains(ReviewDraftRecheck.POLICY_CONFLICT);
        assertThat(codes("교환은 수령 후 7일 안에 신청해 주세요.")).doesNotContain(ReviewDraftRecheck.POLICY_CONFLICT,
                ReviewDraftRecheck.UNSETTLED_VALUE);
    }

    @Test
    @DisplayName("a product document's 14일 is not the company's exception to its 7일")
    void aProductDocument() {
        orgDoc("교환·반품 안내", "교환은 수령 후 7일 이내에 신청해 주세요.");
        productDoc("상품 안내", "이 상품은 교환을 수령 후 14일 이내에 신청할 수 있습니다.");
        assertThat(codes("교환은 수령 후 14일 이내에 신청해 주세요.")).contains(ReviewDraftRecheck.POLICY_CONFLICT);
    }

    @Test
    @DisplayName("nothing stated or another unit: whatever was found without the state is still found")
    void noSupport() {
        orgDoc("배송 안내", "출고 후 보통 1~2일 안에 도착합니다.");
        orgDoc("제작 안내", "교환은 수령 후 영업일 7일 이내에 신청해 주세요.");
        for (String reply : List.of("교환은 14일 안에 신청해 주세요.", "교환은 7일 안에 신청해 주세요.")) {
            assertThat(codes(reply)).as(reply).containsAll(codesWithoutState(reply));
        }
    }

    @Test
    @DisplayName("who pays is read against every document as before: the seller's cost said for the customer's conflicts")
    void whoPays() {
        orgDoc("교환·반품 안내", "단순 변심에 의한 교환·반품은 왕복 배송비 6,000원을 고객님이 부담합니다.");
        assertThat(codes("단순 변심 반품의 왕복 배송비 6,000원은 저희가 부담합니다.")).contains(ReviewDraftRecheck.POLICY_CONFLICT);
    }
}
