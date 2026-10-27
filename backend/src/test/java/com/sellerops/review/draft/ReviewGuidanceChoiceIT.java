package com.sellerops.review.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sellerops.common.ApiException;
import com.sellerops.knowledge.org.OrgKnowledgeChunkRepository;
import com.sellerops.knowledge.org.OrgKnowledgeSourceRepository;
import com.sellerops.knowledge.org.OrgKnowledgeType;
import com.sellerops.knowledge.org.SellerOperationsKnowledgeService;
import com.sellerops.knowledge.org.dto.OrgKnowledgeRequest;
import com.sellerops.organization.Organization;
import com.sellerops.organization.OrganizationRepository;
import com.sellerops.product.Product;
import com.sellerops.product.ProductRepository;
import com.sellerops.product.ProductVariantRepository;
import com.sellerops.product.library.KnowledgeSourceType;
import com.sellerops.product.library.ProductKnowledgeChunkRepository;
import com.sellerops.product.library.ProductKnowledgeLibraryService;
import com.sellerops.product.library.ProductKnowledgeSourceRepository;
import com.sellerops.product.library.dto.KnowledgeSourceRequest;
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
 * 「이 리뷰에 맞는 안내」 (2026-10-27): a phrase match is a candidate until the seller confirms it, a refusal keeps a
 * guidance out of that review, and a guidance the customer's words never reached can be picked.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class ReviewGuidanceChoiceIT {

    @Autowired OrganizationRepository organizations;
    @Autowired ProductRepository products;
    @Autowired ProductVariantRepository variants;
    @Autowired ProductKnowledgeSourceRepository productSources;
    @Autowired ProductKnowledgeChunkRepository productChunks;
    @Autowired OrgKnowledgeSourceRepository orgSources;
    @Autowired OrgKnowledgeChunkRepository orgChunks;
    @Autowired ReviewGuidanceChoiceRepository choices;

    private CustomerPhraseLane lane;
    private ProductKnowledgeLibraryService library;
    private SellerOperationsKnowledgeService rules;
    private UUID org;
    private UUID product;
    private UUID review;
    private UUID adhesion;
    private UUID cutting;
    private UUID packaging;

    @BeforeEach
    void setUp() {
        library = new ProductKnowledgeLibraryService(products, productSources, productChunks, variants);
        rules = new SellerOperationsKnowledgeService(orgSources, orgChunks);
        lane = new CustomerPhraseLane(productSources, orgSources);
        lane.setChoices(choices);
        Organization o = new Organization();
        o.setName("안내 선택 QA");
        org = organizations.save(o).getId();
        product = product("일체형 전선몰딩");
        review = UUID.randomUUID();
        adhesion = library.create(org, product, new KnowledgeSourceRequest(KnowledgeSourceType.USAGE, "실크벽지 부착",
                "실크벽지에서는 폼 양면테이프를 덧대 주세요.", null, null, List.of("떨어져요")), null, "판매자").id();
        cutting = library.create(org, product, new KnowledgeSourceRequest(KnowledgeSourceType.USAGE, "자르기",
                "커터칼로 여러 번 그어 자르세요.", null, null, List.of()), null, "판매자").id();
        packaging = rules.create(org, new OrgKnowledgeRequest(OrgKnowledgeType.SHIPPING_POLICY, "포장",
                "상품 상자에 송장을 붙여 보냅니다.", null, List.of()), null, "판매자").id();
    }

    @Test
    @DisplayName("a phrase match is a candidate; 맞아요 makes it the seller's, 아니에요 keeps it out, undo restores it")
    void aPhraseMatchIsACandidateUntilTheSellerSays() {
        String body = "하루 만에 떨어져요";
        CustomerPhraseLane.ForReview found = lane.forReview(org, review, product, body);
        assertThat(found.passages()).extracting(p -> p.sourceId()).containsExactly(adhesion);
        assertThat(found.candidates()).containsExactly(adhesion);
        assertThat(lane.options(org, review, product, body).attached()).singleElement()
                .satisfies(o -> {
                    assertThat(o.state()).isEqualTo(CustomerPhraseLane.Hit.PHRASE);
                    assertThat(o.phrase()).isEqualTo("떨어져요");
                });

        lane.choose(org, review, product, body, adhesion, ReviewGuidanceChoice.CONFIRMED, null, null);
        assertThat(lane.forReview(org, review, product, body).candidates()).as("confirmed is not a candidate").isEmpty();
        assertThat(choices.findByOrgIdAndReviewIdAndSourceId(org, review, adhesion)).get()
                .extracting(ReviewGuidanceChoice::getMatchedPhrase).isEqualTo("떨어져요");

        lane.choose(org, review, product, body, adhesion, ReviewGuidanceChoice.REJECTED, null, null);
        CustomerPhraseLane.ForReview refused = lane.forReview(org, review, product, body);
        assertThat(refused.passages()).isEmpty();
        assertThat(refused.rejected()).containsExactly(adhesion);
        assertThat(lane.options(org, review, product, body).rejected()).extracting(CustomerPhraseLane.Option::sourceId)
                .containsExactly(adhesion);
        assertThat(lane.forReview(org, UUID.randomUUID(), product, body).passages())
                .as("a refusal is about one review").hasSize(1);

        lane.clear(org, review, adhesion);
        assertThat(lane.forReview(org, review, product, body).candidates()).containsExactly(adhesion);
    }

    @Test
    @DisplayName("a guidance the customer's words never reached can be picked, and comes first")
    void aPickedGuidanceComesFirst() {
        String body = "떨어져요. 그리고 자를 때 가루가 많이 나요";
        CustomerPhraseLane.Options before = lane.options(org, review, product, body);
        assertThat(before.pickable()).extracting(CustomerPhraseLane.Option::sourceId)
                .containsExactly(cutting, packaging);

        lane.choose(org, review, product, body, cutting, ReviewGuidanceChoice.PICKED, null, null);

        CustomerPhraseLane.ForReview found = lane.forReview(org, review, product, body);
        assertThat(found.passages()).extracting(p -> p.sourceId()).containsExactly(cutting, adhesion);
        assertThat(found.candidates()).containsExactly(adhesion);
        assertThat(lane.options(org, review, product, body).pickable()).extracting(CustomerPhraseLane.Option::sourceId)
                .containsExactly(packaging);
    }

    @Test
    @DisplayName("only a guidance this review could be given can be chosen — not another product's, not a blank one")
    void onlyThisReviewsGuidanceCanBeChosen() {
        UUID other = product("디스펜서");
        UUID elsewhere = library.create(org, other, new KnowledgeSourceRequest(KnowledgeSourceType.USAGE, "자석",
                "철판에 붙여 주세요.", null, null, List.of()), null, "판매자").id();
        UUID blank = library.create(org, product, new KnowledgeSourceRequest(KnowledgeSourceType.FAQ, "마감캡",
                "마감캡은 【확인: 수량】개입니다.", null, null, List.of()), null, "판매자").id();

        assertThatThrownBy(() -> lane.choose(org, review, product, "x", elsewhere, ReviewGuidanceChoice.PICKED, null, null))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> lane.choose(org, review, product, "x", blank, ReviewGuidanceChoice.PICKED, null, null))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> lane.choose(org, review, product, "x", cutting, "MAYBE", null, null))
                .isInstanceOf(ApiException.class);
        assertThat(lane.options(org, review, product, "x").pickable()).extracting(CustomerPhraseLane.Option::sourceId)
                .doesNotContain(elsewhere, blank);
    }

    private UUID product(String name) {
        Product p = new Product();
        p.setOrgId(org);
        p.setName(name);
        p.setSku("SKU-" + UUID.randomUUID());
        p.setStatus("ACTIVE");
        return products.save(p).getId();
    }
}
