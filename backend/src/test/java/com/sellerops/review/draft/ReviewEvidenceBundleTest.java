package com.sellerops.review.draft;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.inquiry.draft.InquiryEvidenceRetriever.ScopedPassage;
import com.sellerops.knowledge.KnowledgeScope;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The small bundle a review reply is written from (2026-11-01 third review): limited to this org's material in use, capped
 * in passages and characters, each passage once and whole; and the writer's citations kept only when the name is in the
 * bundle and the quote is in that passage.
 */
class ReviewEvidenceBundleTest {

    private static ScopedPassage passage(KnowledgeScope scope, String text) {
        return new ScopedPassage(scope, "제목", text, UUID.randomUUID(), UUID.randomUUID(), null, 0);
    }

    private static final ScopedPassage FOUND = passage(KnowledgeScope.ORG_OPERATIONS, "수령 후 7일 이내, 개봉하지 않은 상품에 한해 교환 가능합니다.");
    private static final ScopedPassage CARE = passage(KnowledgeScope.PRODUCT, "중성세제로 손세탁하고 그늘에 말려 주세요.");

    @Test
    void theWordSearchsFindingsComeFirstThenTheProductThenThePolicies() {
        ScopedPassage policy = passage(KnowledgeScope.ORG_OPERATIONS, "평일 오후 2시 전 주문은 당일 출고합니다.");
        List<ReviewEvidenceBundle.Item> bundle = ReviewEvidenceBundle.build(List.of(FOUND), List.of(CARE),
                List.of(policy, FOUND));
        assertThat(bundle).extracting(ReviewEvidenceBundle.Item::id).containsExactly("근거 1", "근거 2", "근거 3");
        assertThat(bundle).extracting(i -> i.passage().text())
                .containsExactly(FOUND.text(), CARE.text(), policy.text());
    }

    @Test
    void aPastAnswerABlankAndAnythingOverTheCapStayOut() {
        List<ScopedPassage> many = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            many.add(passage(KnowledgeScope.PRODUCT, "사양 " + i + ": 가로 " + i + "cm 입니다."));
        }
        List<ReviewEvidenceBundle.Item> bundle = ReviewEvidenceBundle.build(
                List.of(passage(KnowledgeScope.PAST_ANSWER, "이번에는 특별히 교환해 드렸습니다."),
                        passage(KnowledgeScope.ORG_OPERATIONS, "반품비는 【확인: 금액】원입니다.")), many, List.of());
        assertThat(bundle).hasSize(ReviewEvidenceBundle.MAX_ITEMS);
        assertThat(bundle).noneMatch(i -> i.passage().scope() == KnowledgeScope.PAST_ANSWER);
        assertThat(bundle).noneMatch(i -> i.passage().text().contains("【확인"));

        String long1 = "가".repeat(ReviewEvidenceBundle.MAX_CHARS - 10);
        List<ReviewEvidenceBundle.Item> capped = ReviewEvidenceBundle.build(List.of(),
                List.of(passage(KnowledgeScope.PRODUCT, long1), passage(KnowledgeScope.PRODUCT, "길이가 넘치는 두 번째 문단입니다.")),
                List.of(passage(KnowledgeScope.ORG_OPERATIONS, "짧음")));
        assertThat(capped).extracting(i -> i.passage().text()).as("a passage that does not fit is left out whole")
                .containsExactly(long1, "짧음");
    }

    @Test
    void aCitationCountsOnlyWhenItsNameAndItsSentenceAreInTheBundle() {
        List<ReviewEvidenceBundle.Item> bundle = ReviewEvidenceBundle.build(List.of(FOUND), List.of(CARE), List.of());
        ReviewEvidenceBundle.Chosen chosen = ReviewEvidenceBundle.chosen(bundle, List.of(
                new ReviewEvidenceBundle.Used("근거 1", "개봉하지 않은 상품에 한해  교환 가능합니다"),
                new ReviewEvidenceBundle.Used("근거 2", "세탁기에 돌려도 됩니다"),
                new ReviewEvidenceBundle.Used("근거 9", "중성세제로 손세탁"),
                new ReviewEvidenceBundle.Used("근거 2", "그늘")));
        assertThat(chosen.ids()).containsExactly("근거 1");
        assertThat(chosen.rejected()).isEqualTo(3);
        assertThat(ReviewEvidenceBundle.chosen(bundle, List.of()).items()).as("citing nothing grounds nothing").isEmpty();
        // The reply's comma in the quote is not another sentence; another word is.
        assertThat(ReviewEvidenceBundle.chosen(bundle, List.of(new ReviewEvidenceBundle.Used("근거 1",
                "수령 후 7일 이내 개봉하지 않은 상품에 한해, 교환 가능합니다."))).ids()).containsExactly("근거 1");
        assertThat(ReviewEvidenceBundle.chosen(bundle, List.of(new ReviewEvidenceBundle.Used("근거 1",
                "수령 후 14일 이내 개봉하지 않은 상품에 한해 교환 가능합니다."))).rejected()).isEqualTo(1);
    }

    @Test
    void manyProductPassagesDoNotPushThePoliciesOutAndWhatDidNotFitIsCounted() {
        List<ScopedPassage> product = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            product.add(passage(KnowledgeScope.PRODUCT, "상품 안내 " + i + ": 설치 방법과 관리 방법을 적은 문단입니다."));
        }
        List<ScopedPassage> rules = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            rules.add(passage(KnowledgeScope.ORG_OPERATIONS, "운영 정책 " + i + ": 교환 · 반품 기준을 적은 문단입니다."));
        }
        ReviewEvidenceBundle.Built built = ReviewEvidenceBundle.assemble(List.of(), product, rules);
        assertThat(built.items()).hasSize(ReviewEvidenceBundle.MAX_ITEMS);
        assertThat(built.items()).filteredOn(i -> i.passage().scope() == KnowledgeScope.ORG_OPERATIONS)
                .as("the policies' own room").hasSize(ReviewEvidenceBundle.POLICY_ROOM_ITEMS);
        assertThat(built.policiesLeftOut()).isEqualTo(2);
        assertThat(built.productLeftOut()).isEqualTo(15 - (ReviewEvidenceBundle.MAX_ITEMS - ReviewEvidenceBundle.POLICY_ROOM_ITEMS));
        assertThat(built.items()).extracting(ReviewEvidenceBundle.Item::id).as("named in the order shown")
                .startsWith("근거 1", "근거 2");
        assertThat(built.items().get(0).passage().scope()).as("product before policies on screen")
                .isEqualTo(KnowledgeScope.PRODUCT);
    }

    // ── most related first (2026-11-01 sixth review) ────────────────────────────────────────────────────────────────

    private static final String WALLPAPER = "실크 벽지처럼 표면이 매끄럽지 않은 도배지에서는 접착이 약할 수 있어 실리콘이나 양면 보조 "
            + "테이프를 함께 쓰시면 좋습니다.";

    private static List<ScopedPassage> productWithAnswerAt(int at, int fillers) {
        List<ScopedPassage> out = new ArrayList<>();
        for (int i = 0; i < fillers; i++) {
            out.add(new ScopedPassage(KnowledgeScope.PRODUCT, "안내 " + i, "포장 단위 " + i + "번은 종이 상자에 담겨 출고됩니다.",
                    UUID.randomUUID(), UUID.randomUUID(), null, 0));
        }
        out.add(Math.min(at, out.size()), new ScopedPassage(KnowledgeScope.PRODUCT, "도배지에 붙일 때", WALLPAPER,
                UUID.randomUUID(), UUID.randomUUID(), null, 0));
        return out;
    }

    @Test
    void theAnswerIsInTheBundleWhereverItWasRegistered() {
        String question = "실크 벽지에 붙였더니 자꾸 떨어져요. 좋은 방법 없을까요?\n벽지에 붙인 뒤 떨어짐";
        for (int at : new int[] {0, 7, 15}) {
            for (int fillers : new int[] {15, 25}) {
                ReviewEvidenceBundle.Built built = ReviewEvidenceBundle.assemble(List.of(),
                        productWithAnswerAt(at, fillers), List.of(), question);
                assertThat(built.items()).as("answer at %d of %d", at, fillers + 1)
                        .anyMatch(i -> i.passage().text().equals(WALLPAPER));
                assertThat(built.productLeftOut()).isPositive();
            }
        }
        // With no question, registration order — the answer at the end of a full bundle is left out, as before.
        assertThat(ReviewEvidenceBundle.assemble(List.of(), productWithAnswerAt(15, 15), List.of(), null).items())
                .noneMatch(i -> i.passage().text().equals(WALLPAPER));
    }

    @Test
    void aPolicyDocumentTravelsWholeSoItsConditionsStayWithIt() {
        UUID exchange = UUID.randomUUID();
        ScopedPassage grant = new ScopedPassage(KnowledgeScope.ORG_OPERATIONS, "교환 안내", "단순 변심 교환은 7일 이내 가능합니다.",
                exchange, UUID.randomUUID(), null, 0);
        ScopedPassage limit = new ScopedPassage(KnowledgeScope.ORG_OPERATIONS, "교환 안내", "단, 개봉한 상품은 제외합니다.",
                exchange, UUID.randomUUID(), null, 0);
        List<ScopedPassage> others = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            others.add(new ScopedPassage(KnowledgeScope.ORG_OPERATIONS, "안내 " + i, "상담 " + i + "번 창구는 평일에 엽니다.",
                    UUID.randomUUID(), UUID.randomUUID(), null, 0));
        }
        List<ScopedPassage> policies = new ArrayList<>(others);
        policies.add(grant);
        policies.add(limit);
        // The search found only the grant; the limit is in the same document.
        ReviewEvidenceBundle.Built built = ReviewEvidenceBundle.assemble(List.of(grant), List.of(), policies,
                "교환하고 싶어요");
        assertThat(built.items()).extracting(i -> i.passage().text()).contains(grant.text(), limit.text());
    }

    @Test
    void aMarkThatIsPartOfANumberIsKeptWhenAQuoteIsCompared() {
        List<ReviewEvidenceBundle.Item> bundle = ReviewEvidenceBundle.build(List.of(), List.of(
                passage(KnowledgeScope.PRODUCT, "두께는 1.5mm이고, 배송은 1~2일, 할인은 10%입니다.")), List.of());
        // The seventh review's case: the point dropped makes another number.
        assertThat(ReviewEvidenceBundle.chosen(bundle, List.of(new ReviewEvidenceBundle.Used("근거 1", "두께는 15mm이고")))
                .rejected()).isEqualTo(1);
        assertThat(ReviewEvidenceBundle.chosen(bundle, List.of(new ReviewEvidenceBundle.Used("근거 1", "배송은 12일, 할인은")))
                .rejected()).isEqualTo(1);
        assertThat(ReviewEvidenceBundle.chosen(bundle, List.of(new ReviewEvidenceBundle.Used("근거 1", "할인은 10입니다")))
                .rejected()).isEqualTo(1);
        // The words and the numbers as written pass, whatever the commas and spaces between words.
        assertThat(ReviewEvidenceBundle.chosen(bundle, List.of(new ReviewEvidenceBundle.Used("근거 1",
                "두께는 1.5mm이고 배송은 1~2일"))).ids()).containsExactly("근거 1");
    }
}
