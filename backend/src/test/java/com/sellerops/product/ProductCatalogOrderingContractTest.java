package com.sellerops.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.channel.Channel;
import com.sellerops.channel.ChannelRepository;
import com.sellerops.common.DataOrigin;
import com.sellerops.inquiry.Inquiry;
import com.sellerops.inquiry.InquiryRepository;
import com.sellerops.organization.Organization;
import com.sellerops.organization.OrganizationRepository;
import com.sellerops.product.dto.ProductCatalogView;
import com.sellerops.product.dto.ProductSummaryView;
import com.sellerops.review.Review;
import com.sellerops.review.ReviewRepository;
import com.sellerops.reviewissue.IssueLifecycleState;
import com.sellerops.reviewissue.IssueSeverity;
import com.sellerops.reviewissue.MatchConfidence;
import com.sellerops.reviewissue.ReviewIssue;
import com.sellerops.reviewissue.ReviewIssueEvidence;
import com.sellerops.reviewissue.ReviewIssueEvidenceRepository;
import com.sellerops.reviewissue.ReviewIssueRepository;
import java.time.Instant;
import java.time.LocalDate;
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
 * <b>상품 목록의 canonical ordering — 답변 대기 → 문제 근거 → 리뷰 → 이름.</b>
 *
 * <p>이 계약이 한 자리에 고정되는 이유는, 전에 두 자리에 있었기 때문이다. 서버는 294개를 미답변 →
 * <b>부정 리뷰 수</b> → 리뷰 수로 줄 세워 20개를 잘랐고, 화면은 받은 20개를 미답변 → <b>문제 근거</b> →
 * 리뷰 수로 다시 줄 세웠다. 그래서 어느 20개가 페이지에 오르는지와 그 20개가 어떤 순서로 서는지를 서로
 * 다른 수량이 정했고, 둘 중 하나는 화면 어디에도 적히지 않은 수량이었다.
 *
 * <p>여기 고정되는 것은 네 가지다: <b>세 키가 모두 화면에 적히는 수량이라는 것</b>, 정렬이 카탈로그
 * 전체에 적용된 뒤에 잘린다는 것, 문제 근거는 화면이 세는 방식 그대로(철회된 문제는 빼고) 센다는 것,
 * 그리고 {@code (미지정 상품)} 버킷은 무엇을 들고 있든 맨 뒤라는 것.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class ProductCatalogOrderingContractTest {

    @Autowired OrganizationRepository organizations;
    @Autowired ProductRepository products;
    @Autowired ChannelRepository channels;
    @Autowired InquiryRepository inquiries;
    @Autowired ReviewRepository reviews;
    @Autowired ReviewIssueRepository issues;
    @Autowired ReviewIssueEvidenceRepository evidence;

    private UUID org;
    private UUID channelId;
    private ProductCatalogService catalogue;

    @BeforeEach
    void setUp() {
        Organization o = new Organization();
        o.setName("상품 목록 정렬 계약");
        org = organizations.save(o).getId();
        channelId = channels.findAll().stream().findFirst().map(Channel::getId).orElseGet(() -> {
            Channel c = new Channel();
            c.setCode("CAFE24");
            c.setNameKo("카페24 자사몰");
            c.setStatus(com.sellerops.channel.ChannelStatus.CONNECTED);
            return channels.save(c).getId();
        });
        catalogue = new ProductCatalogService(products, inquiries, reviews, evidence);
    }

    @Test
    @DisplayName("답변 대기가 맨 앞 — 근거도 리뷰도 그보다 앞서지 못한다")
    void whatTheSellerOwesLeads() {
        UUID owed = product("나 답변을 기다리는 상품");
        UUID heavy = product("다 근거가 쌓인 상품");
        UUID popular = product("라 리뷰가 몰린 상품");
        unansweredInquiry(owed);
        evidenceFor(heavy, 20, false);
        for (int i = 0; i < 40; i += 1) review(popular, true);

        assertThat(ids(catalogue.catalog(org, 20))).containsExactly(owed, heavy, popular);
    }

    @Test
    @DisplayName("두 번째 키는 문제 근거다 — 부정 리뷰 수가 아니다")
    void theSecondKeyIsEvidenceAndNotNegativeReviews() {
        // 전에는 이 둘의 순서가 뒤집혀 있었다. 부정 리뷰가 많은 쪽이 앞서고, 근거가 많은 쪽이 밀렸다.
        UUID evidenceHeavy = product("가 근거 12건 · 부정 리뷰 0건");
        UUID negativeHeavy = product("나 근거 0건 · 부정 리뷰 30건");
        evidenceFor(evidenceHeavy, 12, false);
        for (int i = 0; i < 30; i += 1) review(negativeHeavy, true);

        assertThat(ids(catalogue.catalog(org, 20))).containsExactly(evidenceHeavy, negativeHeavy);
    }

    @Test
    @DisplayName("철회된 문제의 근거는 세지 않는다 — 화면이 세는 방식 그대로")
    void dismissedIssuesDoNotRank() {
        // 상품 상세의 문제 근거는 철회된 문제를 뺀 합이고, 목록의 열도 같은 수를 찍는다. 순위가 그 수를
        // 세는 방식에서 벗어나면, 열이 자기 순서를 설명하지 못하게 된다.
        UUID withdrawn = product("가 철회된 문제의 근거 30건");
        UUID standing = product("나 살아 있는 문제의 근거 3건");
        evidenceFor(withdrawn, 30, true);
        evidenceFor(standing, 3, false);

        assertThat(ids(catalogue.catalog(org, 20))).containsExactly(standing, withdrawn);
    }

    @Test
    @DisplayName("정렬은 카탈로그 전체에 적용된 뒤에 잘린다 — 뒤쪽 상품도 무게가 있으면 올라온다")
    void theWholeCatalogueIsRankedBeforeItIsCut() {
        for (int i = 0; i < 30; i += 1) {
            product(String.format("가 조용한 상품 %02d", i));
        }
        UUID late = product("하 이름이 맨 뒤인, 답변 대기 상품");
        unansweredInquiry(late);

        ProductCatalogView page = catalogue.catalog(org, 20);

        assertThat(page.total()).isEqualTo(31);
        assertThat(page.rows()).hasSize(20);
        // 이름순으로는 31번째다. 전체를 줄 세우지 않고 20개를 먼저 자르면 페이지에 없다.
        assertThat(page.rows().get(0).id()).isEqualTo(late);
    }

    @Test
    @DisplayName("(미지정 상품)은 무엇을 들고 있어도 맨 뒤다 — 상품이 아니라 귀속되지 않은 행들의 자리다")
    void theUnspecifiedBucketSortsLastWhateverItHolds() {
        UUID bucket = product(OperatorProductName.UNSPECIFIED_PRODUCT_NAME);
        UUID real = product("하 평범한 상품");
        unansweredInquiry(bucket);
        unansweredInquiry(bucket);
        evidenceFor(bucket, 50, false);

        assertThat(ids(catalogue.catalog(org, 20))).containsExactly(real, bucket);
    }

    @Test
    @DisplayName("세 키가 모두 같으면 이름으로 갈린다 — 꼬리가 흔들리지 않는다")
    void theTailIsStable() {
        UUID b = product("나 상품");
        UUID a = product("가 상품");
        UUID c = product("다 상품");

        assertThat(ids(catalogue.catalog(org, 20))).containsExactly(a, b, c);
        assertThat(ids(catalogue.catalog(org, 20))).containsExactly(a, b, c);
    }

    @Test
    @DisplayName("만들어 낸 리뷰는 어떤 상품도 앞에 세우지 못한다")
    void syntheticReviewsRankNothing() {
        UUID fabricated = product("가 만들어 낸 리뷰 40건");
        UUID genuine = product("나 실제 리뷰 1건");
        for (int i = 0; i < 40; i += 1) {
            review(fabricated, true, DataOrigin.DEMO_SEED);
        }
        review(genuine, false);

        assertThat(ids(catalogue.catalog(org, 20))).containsExactly(genuine, fabricated);
    }

    private List<UUID> ids(ProductCatalogView page) {
        return page.rows().stream().map(ProductSummaryView::id).toList();
    }

    private UUID product(String name) {
        Product p = new Product();
        p.setOrgId(org);
        p.setName(name);
        p.setSku("SKU-" + UUID.randomUUID());
        p.setStatus("ACTIVE");
        return products.save(p).getId();
    }

    private void unansweredInquiry(UUID productId) {
        Inquiry i = new Inquiry();
        i.setOrgId(org);
        i.setChannelId(channelId);
        i.setExternalId("inq-" + UUID.randomUUID());
        i.setProductId(productId);
        i.setTitle("답을 기다리는 질문");
        i.setBody("언제 오나요");
        i.setStatus("UNANSWERED");
        i.setReceivedAt(Instant.now());
        i.setDataOrigin(DataOrigin.REAL);
        inquiries.save(i);
    }

    private UUID review(UUID productId, boolean negative) {
        return review(productId, negative, DataOrigin.REAL);
    }

    private UUID review(UUID productId, boolean negative, DataOrigin origin) {
        Review r = new Review();
        r.setOrgId(org);
        r.setChannelId(channelId);
        r.setProductId(productId);
        r.setExternalId("rev-" + UUID.randomUUID());
        r.setBody("리뷰 문장");
        r.setRating(negative ? 1 : 5);
        r.setNegative(negative);
        r.setReceivedAt(Instant.now());
        r.setDataOrigin(origin);
        return reviews.save(r).getId();
    }

    /** One issue with {@code count} evidence rows against this product. */
    private void evidenceFor(UUID productId, int count, boolean dismissed) {
        ReviewIssue issue = new ReviewIssue();
        issue.setOrgId(org);
        issue.setSignatureKey("접착:부족:" + UUID.randomUUID());
        issue.setTitle("접착 부족");
        issue.setAspect("접착");
        issue.setProblem("부족");
        issue.setSeverity(IssueSeverity.NORMAL);
        issue.setLifecycleState(IssueLifecycleState.OBSERVING);
        issue.setExtractorKind("RULE_BASED");
        issue.setExtractorVersion("issue-rules-v2");
        issue.setDismissed(dismissed);
        UUID issueId = issues.save(issue).getId();

        for (int i = 0; i < count; i += 1) {
            ReviewIssueEvidence row = new ReviewIssueEvidence();
            row.setOrgId(org);
            row.setIssueId(issueId);
            row.setReviewId(review(productId, true));
            row.setUnitOrdinal(0);
            row.setProductId(productId);
            row.setOccurredOn(LocalDate.of(2026, 9, 1));
            row.setMatchConfidence(MatchConfidence.EXACT_SIGNATURE);
            evidence.save(row);
        }
    }
}
