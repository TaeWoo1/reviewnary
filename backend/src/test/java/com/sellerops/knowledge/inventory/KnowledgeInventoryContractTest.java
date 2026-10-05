package com.sellerops.knowledge.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.inquiry.draft.InquiryDraftEvidence;
import com.sellerops.inquiry.draft.InquiryDraftEvidenceRepository;
import com.sellerops.knowledge.inventory.dto.KnowledgeInventoryView;
import com.sellerops.knowledge.org.OrgKnowledgeSource;
import com.sellerops.knowledge.org.OrgKnowledgeSourceRepository;
import com.sellerops.knowledge.org.OrgKnowledgeType;
import com.sellerops.organization.Organization;
import com.sellerops.organization.OrganizationRepository;
import com.sellerops.product.Product;
import com.sellerops.product.ProductRepository;
import com.sellerops.product.library.KnowledgeSourceType;
import com.sellerops.product.library.ProductKnowledgeSource;
import com.sellerops.product.library.ProductKnowledgeSourceRepository;
import com.sellerops.review.draft.ReviewDraftEvidence;
import com.sellerops.review.draft.ReviewDraftEvidenceRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * <b>지식 목록의 canonical ordering과, 「답변 근거」라는 수가 말할 수 있는 것의 경계.</b>
 *
 * <p>정렬 계약이 여기 한 자리에 고정되는 이유는 상품 목록과 같다 — 서버가 줄 세우고 화면은 받은 순서를
 * 그리며, 화면이 같은 기준을 다시 해석하지 않는다. 고정되는 것은 셋이다: <b>답변 근거 → 마지막 사용 →
 * 제목</b>이라는 순서, 코퍼스 전체를 줄 세운 뒤에 페이지를 자른다는 것, 그리고 운영 기준은 주제 순으로
 * 도착해 화면이 빈 주제를 그려 넣을 수 있다는 것.
 *
 * <p>그리고 수의 의미가 함께 고정된다. 「답변 근거 N」은 저장된 evidence relation의 수이고, 그 이상을
 * 뜻하지 않는다 — 초안이 세 번 고쳐지면 세 판의 인용이 모두 남고, 아무에게도 보내지 않은 초안의 인용도
 * 남는다. 발송도, 승인도, 고객 노출도 아니다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class KnowledgeInventoryContractTest {

    @Autowired OrganizationRepository organizations;
    @Autowired ProductRepository products;
    @Autowired OrgKnowledgeSourceRepository rules;
    @Autowired ProductKnowledgeSourceRepository facts;
    @Autowired InquiryDraftEvidenceRepository inquiryEvidence;
    @Autowired ReviewDraftEvidenceRepository reviewEvidence;

    private UUID org;
    private UUID productId;
    private KnowledgeInventoryService inventory;

    private static final Instant T0 = Instant.parse("2026-09-01T00:00:00Z");

    @BeforeEach
    void setUp() {
        Organization o = new Organization();
        o.setName("지식 목록 계약");
        org = organizations.save(o).getId();
        productId = product("전선몰딩 1호");
        inventory = new KnowledgeInventoryService(rules, facts, products,
                new KnowledgeUsageReader(inquiryEvidence, reviewEvidence));
    }

    @Test
    @DisplayName("가장 많이 인용된 지식이 맨 앞 — 최근에 쓴 것도, 먼저 쓴 것도 그보다 앞서지 못한다")
    void theMostCitedLeads() {
        UUID quiet = fact("가 아무도 인용하지 않은 지식");
        UUID recent = fact("나 어제 한 번 쓰인 지식");
        UUID heavy = fact("다 스무 번 쓰인 지식");
        citeInReview(recent, 1, T0.plus(30, ChronoUnit.DAYS));
        citeInReview(heavy, 20, T0);

        assertThat(titles(inventory.of(org).productKnowledge()))
                .containsExactly("다 스무 번 쓰인 지식", "나 어제 한 번 쓰인 지식", "가 아무도 인용하지 않은 지식");
        assertThat(quiet).isNotNull();
    }

    @Test
    @DisplayName("문의 초안과 리뷰 초안의 인용은 같은 수로 더해진다 — 둘은 한 지식의 두 쓰임이다")
    void bothLanesCountTowardTheSameNumber() {
        UUID both = fact("두 곳에서 쓰인 지식");
        UUID oneLane = fact("한 곳에서 쓰인 지식");
        citeInInquiry(both, 2, T0);
        citeInReview(both, 3, T0.plus(1, ChronoUnit.DAYS));
        citeInInquiry(oneLane, 4, T0);

        List<KnowledgeInventoryView.ProductFact> rows = inventory.of(org).productKnowledge();
        assertThat(rows.get(0).title()).isEqualTo("두 곳에서 쓰인 지식");
        assertThat(rows.get(0).citations()).isEqualTo(5);
        // 마지막 사용은 두 레인을 통틀어 가장 나중의 한 시각이다.
        assertThat(rows.get(0).lastUsedAt()).isEqualTo(T0.plus(1, ChronoUnit.DAYS));
        assertThat(rows.get(1).citations()).isEqualTo(4);
    }

    @Test
    @DisplayName("한 번도 인용되지 않은 지식은 0이고, 빠지지 않는다 — 비어 있다는 사실이 이 화면의 절반이다")
    void theUncitedAreListedAsZeroRatherThanOmitted() {
        fact("한 번도 쓰이지 않은 지식");
        List<KnowledgeInventoryView.ProductFact> rows = inventory.of(org).productKnowledge();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).citations()).isZero();
        assertThat(rows.get(0).lastUsedAt()).isNull();
    }

    @Test
    @DisplayName("꼬리는 제목으로 안정 — 아무도 쓰지 않은 것들이 읽을 때마다 자리를 바꾸지 않는다")
    void theUncitedTailIsStable() {
        fact("다 세 번째");
        fact("가 첫 번째");
        fact("나 두 번째");
        assertThat(titles(inventory.of(org).productKnowledge()))
                .containsExactly("가 첫 번째", "나 두 번째", "다 세 번째");
    }

    @Test
    @DisplayName("전체를 줄 세운 뒤에 자른다 — 한 페이지 밖에 있던 지식이 가장 많이 쓰였을 수 있다")
    void theWholeCorpusIsRankedBeforeThePageIsCut() {
        for (int i = 0; i < KnowledgeInventoryView.PAGE + 5; i += 1) {
            fact(String.format("가%03d 조용한 지식", i));
        }
        UUID late = fact("힣 맨 뒤에 쓰인 이름이지만 가장 많이 인용된 지식");
        citeInReview(late, 9, T0);

        KnowledgeInventoryView view = inventory.of(org);
        assertThat(view.productKnowledge()).hasSize(KnowledgeInventoryView.PAGE);
        assertThat(view.productKnowledgeTotal()).isEqualTo(KnowledgeInventoryView.PAGE + 6);
        assertThat(view.productKnowledge().get(0).title())
                .isEqualTo("힣 맨 뒤에 쓰인 이름이지만 가장 많이 인용된 지식");
    }

    @Test
    @DisplayName("운영 기준은 주제 순으로 도착한다 — 화면이 비어 있는 주제를 그려 넣을 수 있도록")
    void rulesArriveInTopicOrder() {
        rule(OrgKnowledgeType.TAX_INVOICE, "세금계산서 발행 안내");
        rule(OrgKnowledgeType.SHIPPING_POLICY, "배송 기준");
        UUID cited = rule(OrgKnowledgeType.SHIPPING_POLICY, "많이 쓰인 배송 기준");
        citeInInquiry(cited, 4, T0, InquiryDraftEvidence.KIND_ORG_POLICY);

        List<KnowledgeInventoryView.OperatingRule> rows = inventory.of(org).rules();
        assertThat(rows.stream().map(KnowledgeInventoryView.OperatingRule::knowledgeType).toList())
                .containsExactly("SHIPPING_POLICY", "SHIPPING_POLICY", "TAX_INVOICE");
        // 한 주제 안에서는 많이 쓰인 것이 앞 — 상품 지식과 같은 이유다.
        assertThat(rows.get(0).title()).isEqualTo("많이 쓰인 배송 기준");
        assertThat(rows.get(0).citations()).isEqualTo(4);
    }

    @Test
    @DisplayName("상품을 세는 것은 상품이고, 지식을 가진 상품이 몇인지 따로 말한다")
    void itSaysHowManyProductsHaveAnyKnowledgeAtAll() {
        product("지식 없는 상품 하나");
        product("지식 없는 상품 둘");
        fact("이 상품에만 있는 지식");
        fact("같은 상품의 두 번째 지식");

        KnowledgeInventoryView view = inventory.of(org);
        assertThat(view.products()).isEqualTo(3);
        // 지식 2건이 한 상품에 있으므로, 지식을 가진 상품은 1이다 — 2가 아니다.
        assertThat(view.productsWithKnowledge()).isEqualTo(1);
    }

    @Test
    @DisplayName("문서를 가리키지 않는 인용은 아무 줄에도 수를 더하지 않는다")
    void anEvidenceRowWithNoDocumentAddsToNothing() {
        UUID f = fact("지식 한 건");
        citeInInquiry(f, 1, T0);
        // 주문 사실은 문서를 인용하지 않는다 — source_id가 없다.
        InquiryDraftEvidence orderFact = new InquiryDraftEvidence();
        orderFact.setOrgId(org);
        orderFact.setWorkItemId(UUID.randomUUID());
        orderFact.setDraftVersion(1);
        orderFact.setOrdinal(0);
        orderFact.setKind(InquiryDraftEvidence.KIND_ORDER_FACT);
        orderFact.setCreatedAt(T0);
        inquiryEvidence.save(orderFact);

        assertThat(inventory.of(org).productKnowledge().get(0).citations()).isEqualTo(1);
    }

    @Test
    @DisplayName("다른 회사의 인용은 이 회사의 수에 닿지 않는다")
    void anotherOrgsCitationsNeverCount() {
        UUID f = fact("우리 지식");
        Organization other = new Organization();
        other.setName("남의 회사");
        UUID otherOrg = organizations.save(other).getId();
        ReviewDraftEvidence row = new ReviewDraftEvidence();
        row.setOrgId(otherOrg);
        row.setReviewId(UUID.randomUUID());
        row.setDraftVersion(1);
        row.setOrdinal(0);
        row.setKind(InquiryDraftEvidence.KIND_PRODUCT_KNOWLEDGE);
        row.setSourceId(f);
        row.setCreatedAt(T0);
        reviewEvidence.save(row);

        assertThat(inventory.of(org).productKnowledge().get(0).citations()).isZero();
    }

    @Test
    @DisplayName("상품을 이름으로 부를 수 없는 지식도 목록에 남는다 — 다만 「지식을 가진 상품」에는 들지 않는다")
    void knowledgeWhoseProductCannotBeNamedIsListedButNotCounted() {
        fact("이름을 부를 수 있는 상품의 지식");
        // 제조된 상품은 RealDataOnly 걸름망에 걸려 이름이 돌아오지 않고, 지워진 상품도 마찬가지다.
        // 어느 쪽이든 지식 자체는 판매자가 쓴 실제 행이므로 목록에서 빠지면 안 된다.
        ProductKnowledgeSource orphan = new ProductKnowledgeSource();
        orphan.setOrgId(org);
        orphan.setProductId(UUID.randomUUID());
        orphan.setSourceType(KnowledgeSourceType.FAQ);
        orphan.setTitle("이름을 부를 수 없는 상품의 지식");
        orphan.setBody("본문");
        facts.save(orphan);

        KnowledgeInventoryView view = inventory.of(org);
        assertThat(titles(view.productKnowledge()))
                .contains("이름을 부를 수 없는 상품의 지식");
        KnowledgeInventoryView.ProductFact orphanRow = view.productKnowledge().stream()
                .filter(f -> f.title().equals("이름을 부를 수 없는 상품의 지식"))
                .findFirst().orElseThrow();
        assertThat(orphanRow.productName()).isNull();
        // 열리지 않을 문은 제공하지 않는다 — 카탈로그가 404로 답할 상품에는 링크가 없어야 한다.
        assertThat(orphanRow.productReachable()).isFalse();
        assertThat(view.productKnowledge().stream()
                .filter(f -> f.title().equals("이름을 부를 수 있는 상품의 지식"))
                .findFirst().orElseThrow().productReachable()).isTrue();
        // 분모(294)에 없는 상품이 분자에 들어가면 「294개 가운데 5개」는 아무 뜻도 없는 문장이 된다.
        assertThat(view.productsWithKnowledge()).isEqualTo(1);
    }

    @Test
    @DisplayName("지금 목록에 없는 기준을 인용한 답변 근거는 수로만 남는다 — 어떤 기준이었는지는 말하지 않는다")
    void citationsOfARuleThatIsGoneAreCountedAndNeverNamed() {
        UUID living = rule(OrgKnowledgeType.SHIPPING_POLICY, "지금 있는 배송 기준");
        citeInInquiry(living, 2, T0, InquiryDraftEvidence.KIND_ORG_POLICY);
        // 지워진 기준의 인용. 인용은 제 출처보다 오래 살고(V91), 그것이 이 수가 세는 상태다.
        UUID gone = UUID.randomUUID();
        citeInInquiry(gone, 3, T0, InquiryDraftEvidence.KIND_ORG_POLICY);

        KnowledgeInventoryView view = inventory.of(org);
        assertThat(view.orphanRuleCitations()).isEqualTo(3);
        // 살아 있는 기준의 인용은 그 기준의 줄에 그대로 서 있고, 고아 수에 섞이지 않는다.
        assertThat(view.rules()).hasSize(1);
        assertThat(view.rules().get(0).citations()).isEqualTo(2);
    }

    @Test
    @DisplayName("상품 지식 인용은 운영 기준 고아 수에 들어가지 않는다 — 종류가 다른 것은 다른 수다")
    void productKnowledgeCitationsNeverCountAsOrphanRules() {
        UUID f = fact("상품 지식 한 건");
        citeInReview(f, 4, T0);
        // 사라진 상품 지식을 가리키는 인용도 마찬가지다 — 운영 기준 줄 아래에 적힐 수가 아니다.
        ReviewDraftEvidence row = new ReviewDraftEvidence();
        row.setOrgId(org);
        row.setReviewId(UUID.randomUUID());
        row.setDraftVersion(1);
        row.setOrdinal(0);
        row.setKind(InquiryDraftEvidence.KIND_PRODUCT_KNOWLEDGE);
        row.setSourceId(UUID.randomUUID());
        row.setCreatedAt(T0);
        reviewEvidence.save(row);

        assertThat(inventory.of(org).orphanRuleCitations()).isZero();
    }

    /* ── fixtures ─────────────────────────────────────────────────────────── */

    private static List<String> titles(List<KnowledgeInventoryView.ProductFact> rows) {
        return rows.stream().map(KnowledgeInventoryView.ProductFact::title).toList();
    }

    private UUID product(String name) {
        Product p = new Product();
        p.setOrgId(org);
        p.setName(name);
        p.setSku("SKU-" + UUID.randomUUID());
        p.setStatus("ACTIVE");
        return products.save(p).getId();
    }

    private UUID fact(String title) {
        ProductKnowledgeSource s = new ProductKnowledgeSource();
        s.setOrgId(org);
        s.setProductId(productId);
        s.setSourceType(KnowledgeSourceType.FAQ);
        s.setTitle(title);
        s.setBody("본문");
        return facts.save(s).getId();
    }

    private UUID rule(OrgKnowledgeType type, String title) {
        OrgKnowledgeSource s = new OrgKnowledgeSource();
        s.setOrgId(org);
        s.setKnowledgeType(type);
        s.setTitle(title);
        s.setBody("본문");
        return rules.save(s).getId();
    }

    private void citeInInquiry(UUID sourceId, int times, Instant at) {
        citeInInquiry(sourceId, times, at, InquiryDraftEvidence.KIND_PRODUCT_KNOWLEDGE);
    }

    private void citeInInquiry(UUID sourceId, int times, Instant at, String kind) {
        for (int i = 0; i < times; i += 1) {
            InquiryDraftEvidence row = new InquiryDraftEvidence();
            row.setOrgId(org);
            row.setWorkItemId(UUID.randomUUID());
            row.setDraftVersion(1);
            row.setOrdinal(i);
            row.setKind(kind);
            row.setSourceId(sourceId);
            row.setCreatedAt(at);
            inquiryEvidence.save(row);
        }
    }

    private void citeInReview(UUID sourceId, int times, Instant at) {
        for (int i = 0; i < times; i += 1) {
            ReviewDraftEvidence row = new ReviewDraftEvidence();
            row.setOrgId(org);
            row.setReviewId(UUID.randomUUID());
            row.setDraftVersion(1);
            row.setOrdinal(i);
            row.setKind(InquiryDraftEvidence.KIND_PRODUCT_KNOWLEDGE);
            row.setSourceId(sourceId);
            row.setCreatedAt(at);
            reviewEvidence.save(row);
        }
    }
}
