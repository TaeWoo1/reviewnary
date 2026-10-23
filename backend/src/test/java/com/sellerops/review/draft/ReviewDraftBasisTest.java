package com.sellerops.review.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sellerops.attention.reply.ReviewReplyTemplate;
import com.sellerops.attention.reply.ReviewReplyTemplateKey;
import com.sellerops.attention.reply.ReviewReplyTemplateRepository;
import com.sellerops.attention.reply.ReviewReplyTemplateService;
import com.sellerops.channel.ChannelRepository;
import com.sellerops.knowledge.memory.AnswerMemoryRepository;
import com.sellerops.knowledge.memory.AnswerMemoryService;
import com.sellerops.knowledge.memory.AnswerMemoryStrength;
import com.sellerops.knowledge.org.OrgKnowledgeChunkRepository;
import com.sellerops.knowledge.org.OrgKnowledgeSource;
import com.sellerops.knowledge.org.OrgKnowledgeSourceRepository;
import com.sellerops.knowledge.org.OrgKnowledgeType;
import com.sellerops.organization.OrganizationRepository;
import com.sellerops.product.Product;
import com.sellerops.product.ProductRepository;
import com.sellerops.product.library.KnowledgeSourceType;
import com.sellerops.product.library.ProductKnowledgeChunkRepository;
import com.sellerops.product.library.ProductKnowledgeSource;
import com.sellerops.product.library.ProductKnowledgeSourceRepository;
import com.sellerops.review.draft.ReviewDraftBasis.Fingerprints;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * <b>Each fingerprint moves when, and only when, what it covers changes</b> — the knowledge with the product library,
 * the policy with the company's rules, the voice with the reply wording; a new past reply moves none of them, and
 * another seller's rows never reach this one's.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class ReviewDraftBasisTest {

    @Autowired ProductKnowledgeSourceRepository productSources;
    @Autowired ProductKnowledgeChunkRepository productChunks;
    @Autowired OrgKnowledgeSourceRepository orgSources;
    @Autowired OrgKnowledgeChunkRepository orgChunks;
    @Autowired ReviewReplyTemplateRepository templateRows;
    @Autowired AnswerMemoryRepository memoryRows;
    @Autowired ProductRepository products;
    @Autowired ChannelRepository channels;
    @Autowired OrganizationRepository organizations;

    private ReviewDraftBasis basis;
    private PrecedentFixture fixture;
    private UUID org;
    private UUID product;

    @BeforeEach
    void setUp() {
        basis = new ReviewDraftBasis(productSources, orgSources, new ReviewReplyTemplateService(templateRows));
        fixture = new PrecedentFixture(channels, organizations);
        org = fixture.organization("지문 시험 상점");
        Product p = new Product();
        p.setOrgId(org);
        p.setName("합성 상품");
        p.setStatus("ACTIVE");
        product = products.save(p).getId();
    }

    @Test
    void aNewBanChangesTheVoiceFingerprintAndNoBanLeavesItAsItWas() {
        Fingerprints before = basis.of(org);
        com.sellerops.knowledge.style.AnswerStyleService styles =
                mock(com.sellerops.knowledge.style.AnswerStyleService.class);
        when(styles.profileFor(org)).thenReturn(com.sellerops.knowledge.style.AnswerStyleProfile.defaults());
        basis.setStyleGuard(new ReviewStyleGuard(styles));
        assertThat(basis.of(org)).as("a company with no ban keeps its fingerprint").isEqualTo(before);
        when(styles.profileFor(org)).thenReturn(new com.sellerops.knowledge.style.AnswerStyleProfile(
                null, null, null, null, null, null, java.util.List.of(), java.util.List.of("환불"), null, 1));
        assertThat(basis.of(org)).as("a bare topic word is not enforced, so it is not a change").isEqualTo(before);
        when(styles.profileFor(org)).thenReturn(new com.sellerops.knowledge.style.AnswerStyleProfile(
                null, null, null, null, null, null, java.util.List.of(), java.util.List.of("고객님 탓"), null, 1));
        Fingerprints after = basis.of(org);
        assertThat(after.voice()).isNotEqualTo(before.voice());
        assertThat(after.knowledge()).isEqualTo(before.knowledge());
        assertThat(after.policy()).isEqualTo(before.policy());
    }

    @Test
    void theSameRowsGiveTheSameFingerprintsInTheServersForm() {
        rule("배송 안내", "평일 오후 2시 전 주문은 당일 출고합니다.");
        Fingerprints first = basis.of(org);
        assertThat(basis.of(org)).isEqualTo(first);
        assertThat(first.combined().split("\\|")).hasSize(3)
                .allMatch(fp -> fp.matches("v1:[0-9a-f]{32}") && fp.matches("[A-Za-z0-9._:-]{1,64}"));
    }

    @Test
    void aCompanyRuleMovesThePolicyAlone() {
        OrgKnowledgeSource rule = rule("배송 안내", "평일 오후 2시 전 주문은 당일 출고합니다.");
        Fingerprints before = basis.of(org);

        rule.setBody("평일 오전 11시 전 주문만 당일 출고합니다.");
        orgSources.saveAndFlush(rule);
        Fingerprints edited = basis.of(org);
        assertThat(edited.policy()).isNotEqualTo(before.policy());
        assertThat(edited.knowledge()).isEqualTo(before.knowledge());
        assertThat(edited.voice()).isEqualTo(before.voice());

        rule.setActive(false);
        orgSources.saveAndFlush(rule);
        assertThat(basis.of(org).policy()).as("retired is a change").isNotEqualTo(edited.policy());
    }

    @Test
    void theProductLibraryMovesTheKnowledgeAlone() {
        Fingerprints before = basis.of(org);
        ProductKnowledgeSource note = new ProductKnowledgeSource();
        note.setOrgId(org);
        note.setProductId(product);
        note.setSourceType(KnowledgeSourceType.USAGE);
        note.setTitle("설치 방법");
        note.setBody("벽면의 먼지를 닦은 뒤 붙입니다.");
        productSources.saveAndFlush(note);

        Fingerprints after = basis.of(org);
        assertThat(after.knowledge()).isNotEqualTo(before.knowledge());
        assertThat(after.policy()).isEqualTo(before.policy());
        assertThat(after.voice()).isEqualTo(before.voice());

        note.setBody("벽면의 먼지와 기름기를 닦은 뒤 붙입니다.");
        productSources.saveAndFlush(note);
        assertThat(basis.of(org).knowledge()).as("an edited note is a change").isNotEqualTo(after.knowledge());
    }

    @Test
    void theSellersReplyWordingMovesTheVoiceAlone() {
        Fingerprints before = basis.of(org);
        ReviewReplyTemplate own = new ReviewReplyTemplate();
        own.setOrgId(org);
        own.setTemplateKey(ReviewReplyTemplateKey.POSITIVE.category());
        own.setBody("찾아 주셔서 고맙습니다. 또 뵙겠습니다.");
        templateRows.saveAndFlush(own);

        Fingerprints after = basis.of(org);
        assertThat(after.voice()).isNotEqualTo(before.voice());
        assertThat(after.knowledge()).isEqualTo(before.knowledge());
        assertThat(after.policy()).isEqualTo(before.policy());
    }

    @Test
    void anotherApprovedReplyMovesNothing() {
        Fingerprints before = basis.of(org);
        new AnswerMemoryService(memoryRows, orgChunks, productChunks).remember(
                new AnswerMemoryService.RememberCommand(org, "review-approved:" + UUID.randomUUID() + ":1",
                        AnswerMemoryStrength.USER_APPROVED, "배송이 빨라요", "리뷰 답글 · 별점 5점",
                        "빠르게 받아 보셨다니 다행입니다.", null, "NAVER", null, null, null, null, 1, null, null, null,
                        null, null));
        assertThat(basis.of(org)).isEqualTo(before);
    }

    @Test
    void anotherSellersRowsAreNotThisSellers() {
        Fingerprints before = basis.of(org);
        UUID other = fixture.organization("다른 상점");
        OrgKnowledgeSource theirs = new OrgKnowledgeSource();
        theirs.setOrgId(other);
        theirs.setKnowledgeType(OrgKnowledgeType.SHIPPING_POLICY);
        theirs.setTitle("배송 안내");
        theirs.setBody("주문 다음 날 출고합니다.");
        orgSources.saveAndFlush(theirs);
        assertThat(basis.of(org)).isEqualTo(before);
    }

    private OrgKnowledgeSource rule(String title, String body) {
        OrgKnowledgeSource rule = new OrgKnowledgeSource();
        rule.setOrgId(org);
        rule.setKnowledgeType(OrgKnowledgeType.SHIPPING_POLICY);
        rule.setTitle(title);
        rule.setBody(body);
        return orgSources.saveAndFlush(rule);
    }
}
