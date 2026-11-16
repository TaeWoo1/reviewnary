package com.sellerops.knowledge.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.sellerops.common.ApiException;
import com.sellerops.knowledge.document.dto.KnowledgeDocumentView;
import com.sellerops.knowledge.org.OrgKnowledgeChunkRepository;
import com.sellerops.knowledge.org.OrgKnowledgeRevisionRepository;
import com.sellerops.knowledge.org.OrgKnowledgeSource;
import com.sellerops.knowledge.org.OrgKnowledgeSourceRepository;
import com.sellerops.knowledge.org.OrgKnowledgeType;
import com.sellerops.knowledge.org.SellerOperationsKnowledgeService;
import com.sellerops.organization.Organization;
import com.sellerops.organization.OrganizationRepository;
import com.sellerops.product.Product;
import com.sellerops.product.ProductRepository;
import com.sellerops.product.library.KnowledgeAuthorship;
import com.sellerops.product.library.KnowledgeSourceType;
import com.sellerops.product.library.ProductKnowledgeChunkRepository;
import com.sellerops.product.library.ProductKnowledgeIndexer;
import com.sellerops.product.library.ProductKnowledgeRevisionRepository;
import com.sellerops.product.library.ProductKnowledgeSourceRepository;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

/**
 * 같은 이름 자료 다시 올리기 (2026-11-16 final review): a company-wide title is unique per org (V72), and uploading a
 * file of the same name used to reach that index as an unhandled 500. It is now a 409 that sends the seller to the
 * existing document's 「파일 바꾸기」 — and the existing document is not touched: body, version, kept revisions,
 * 사용 여부 and file name stay exactly as they were. A product document meets the same thing within its product (V64
 * uq_pk_sources_title on org, product, title) and gets the same answer; another product may use the same title.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class KnowledgeDocumentSameNameTest {

    @Autowired ProductKnowledgeSourceRepository productSources;
    @Autowired ProductKnowledgeChunkRepository productChunks;
    @Autowired ProductKnowledgeRevisionRepository productRevisions;
    @Autowired ProductRepository products;
    @Autowired OrgKnowledgeSourceRepository orgSources;
    @Autowired OrgKnowledgeChunkRepository orgChunks;
    @Autowired OrgKnowledgeRevisionRepository orgRevisions;
    @Autowired OrganizationRepository organizations;

    private KnowledgeDocumentService service;
    private UUID org;
    private UUID product;

    @BeforeEach
    void setUp() {
        SellerOperationsKnowledgeService orgKnowledge = new SellerOperationsKnowledgeService(orgSources, orgChunks);
        orgKnowledge.setRevisions(orgRevisions);
        service = new KnowledgeDocumentService(productSources, new ProductKnowledgeIndexer(productChunks), products,
                orgSources, orgChunks, orgKnowledge);
        service.setProductRevisions(productRevisions);
        Organization o = new Organization();
        o.setName("같은 이름 상점");
        org = organizations.save(o).getId();
        Product p = new Product();
        p.setOrgId(org);
        p.setName("[합성] 스테인리스 텀블러 500ml");
        p.setStatus("ACTIVE");
        product = products.save(p).getId();
    }

    /** Long enough for the reader's floor (a near-empty file is a scan and is refused first, with 400). */
    private static byte[] utf8(String s) {
        return (s + " 문의는 평일 오전 10시부터 오후 5시까지 고객센터 게시판으로 남겨 주세요.").getBytes(StandardCharsets.UTF_8);
    }

    private KnowledgeDocumentView upload(String filename, String text) {
        return service.importDocument(org, KnowledgeDocumentService.Scope.ORG, null, null,
                OrgKnowledgeType.SHIPPING_POLICY, filename, utf8(text), null, "판매자");
    }

    private String snapshot(UUID sourceId) {
        OrgKnowledgeSource s = orgSources.findById(sourceId).orElseThrow();
        return s.getBody() + "|" + s.getVersion() + "|" + s.isActive() + "|" + s.getDocumentName() + "|"
                + s.getKnowledgeType() + "|" + orgRevisions.findAllByOrgIdAndSourceIdOrderByVersionAsc(org, sourceId).size()
                + "|" + orgChunks.countBySourceId(sourceId);
    }

    private ApiException refused(Runnable upload) {
        ApiException e = catchThrowableOfType(upload::run, ApiException.class);
        assertThat(e).as("the upload is refused, not a 500").isNotNull();
        assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(e.getCode()).isEqualTo(KnowledgeDocumentService.DOCUMENT_EXISTS);
        return e;
    }

    @Test
    @DisplayName("ORG: the same file name again → 409 to 「파일 바꾸기」, the existing document exactly as it was")
    void theSameNameIsRefusedAndTheExistingDocumentIsKept() {
        KnowledgeDocumentView first = upload("01_배송정책.txt", "배송비는 3,000원이며, 7만원 이상 구매 시 무료입니다.");
        service.replaceFile(org, first.sourceId(), "01_배송정책.txt", utf8("배송비는 3,000원이며, 7만원 이상 구매 시 무료입니다. 제주는 3,000원 추가."));
        String before = snapshot(first.sourceId());

        ApiException e = refused(() -> upload("01_배송정책.txt", "배송비는 무료입니다."));

        assertThat(e.getMessage()).isEqualTo("이미 있는 자료입니다(「01_배송정책」). 기존 자료의 「파일 바꾸기」를 이용해 주세요.");
        assertThat(snapshot(first.sourceId())).isEqualTo(before).contains("|2|true|01_배송정책.txt|");
        assertThat(orgSources.findAllByOrgIdOrderByCreatedAtAsc(org)).hasSize(1);
    }

    @Test
    @DisplayName("ORG: a switched-off document stays off; another extension of the same name is the same title")
    void anOffDocumentStaysOffAndTheExtensionDoesNotMatter() {
        KnowledgeDocumentView first = upload("03_옛공지.txt", "교환은 수령 후 14일 이내에 신청해 주세요.");
        service.setActive(org, first.sourceId(), false);
        String before = snapshot(first.sourceId());

        refused(() -> upload("03_옛공지.md", "교환은 수령 후 7일 이내에 신청해 주세요."));

        assertThat(snapshot(first.sourceId())).isEqualTo(before).contains("|false|03_옛공지.txt|");
        assertThat(orgSources.findAllByOrgIdOrderByCreatedAtAsc(org)).hasSize(1);
    }

    @Test
    @DisplayName("ORG: a typed rule of the same title is not a file — the sentence says rename or edit, not 파일 바꾸기")
    void aTypedRuleOfTheSameTitleNamesTheRule() {
        OrgKnowledgeSource typed = new OrgKnowledgeSource();
        typed.setOrgId(org);
        typed.setKnowledgeType(OrgKnowledgeType.SHIPPING_POLICY);
        typed.setAuthoredOrigin(KnowledgeAuthorship.SELLER_ENTERED_KNOWLEDGE);
        typed.setTitle("배송 안내");
        typed.setBody("평일 오후 3시 이전 결제는 당일 출고합니다.");
        UUID id = orgSources.saveAndFlush(typed).getId();
        String before = snapshot(id);

        ApiException e = refused(() -> upload("배송 안내.txt", "주말에도 출고합니다."));

        assertThat(e.getMessage()).contains("같은 제목의 운영 기준").doesNotContain("파일 바꾸기");
        assertThat(snapshot(id)).isEqualTo(before);
    }

    private KnowledgeDocumentView productUpload(UUID productId, String filename, String text) {
        return service.importDocument(org, KnowledgeDocumentService.Scope.PRODUCT, productId,
                KnowledgeSourceType.DESCRIPTION, null, filename, utf8(text), null, "판매자");
    }

    private String productSnapshot(UUID sourceId) {
        var s = productSources.findById(sourceId).orElseThrow();
        return s.getBody() + "|" + s.getVersion() + "|" + s.isActive() + "|" + s.getDocumentName() + "|"
                + s.getSourceType() + "|" + productRevisions.findAllByOrgIdAndSourceIdOrderByVersionAsc(org, sourceId).size()
                + "|" + productChunks.findAllBySourceId(sourceId).size();
    }

    @Test
    @DisplayName("PRODUCT: the same name on the same product → the same 409, the existing document exactly as it was")
    void theSameNameOnTheSameProductIsRefused() {
        KnowledgeDocumentView first = productUpload(product, "12_텀블러_상세.md", "보온 6시간, 보냉 12시간입니다.");
        service.replaceFile(org, first.sourceId(), "12_텀블러_상세.md", utf8("보온 6시간, 보냉 12시간입니다. 식기세척기는 권하지 않습니다."));
        service.setActive(org, first.sourceId(), false);
        String before = productSnapshot(first.sourceId());

        ApiException e = refused(() -> productUpload(product, "12_텀블러_상세.txt", "보온 8시간입니다."));

        assertThat(e.getMessage()).isEqualTo("이미 있는 자료입니다(「12_텀블러_상세」). 기존 자료의 「파일 바꾸기」를 이용해 주세요.");
        assertThat(productSnapshot(first.sourceId())).isEqualTo(before).contains("|2|false|12_텀블러_상세.md|");
        assertThat(productSources.findAll()).filteredOn(x -> x.getOrgId().equals(org)).hasSize(1);
    }

    @Test
    @DisplayName("PRODUCT: another product may carry the same title")
    void anotherProductMayUseTheSameTitle() {
        Product p = new Product();
        p.setOrgId(org);
        p.setName("[합성] 실리콘 이유식 용기 4P");
        p.setStatus("ACTIVE");
        UUID other = products.save(p).getId();
        KnowledgeDocumentView first = productUpload(product, "상세.md", "보온 6시간입니다.");

        KnowledgeDocumentView second = productUpload(other, "상세.md", "전자레인지 사용이 가능합니다.");

        assertThat(second.sourceId()).isNotEqualTo(first.sourceId());
        assertThat(second.productId()).isEqualTo(other);
        assertThat(productSources.findById(first.sourceId()).orElseThrow().getBody()).startsWith("보온 6시간입니다.");
    }

    @Test
    @DisplayName("Two uploads at once: only the title index becomes the 409; any other integrity failure stays itself")
    void onlyTheTitleIndexIsTranslated() {
        DataIntegrityViolationException title = new DataIntegrityViolationException("could not execute statement",
                new org.hibernate.exception.ConstraintViolationException("duplicate key",
                        new SQLException("ERROR: duplicate key value violates unique constraint"), "uq_org_knowledge_sources_title"));
        DataIntegrityViolationException other = new DataIntegrityViolationException("could not execute statement",
                new org.hibernate.exception.ConstraintViolationException("null value",
                        new SQLException("ERROR: null value in column \"body\""), "org_knowledge_sources_body_not_null"));

        DataIntegrityViolationException productTitle = new DataIntegrityViolationException("could not execute statement",
                new org.hibernate.exception.ConstraintViolationException("duplicate key",
                        new SQLException("ERROR: duplicate key value violates unique constraint"), "uq_pk_sources_title"));

        assertThat(KnowledgeDocumentService.violates(title, KnowledgeDocumentService.ORG_TITLE_UNIQUE)).isTrue();
        assertThat(KnowledgeDocumentService.violates(other, KnowledgeDocumentService.ORG_TITLE_UNIQUE)).isFalse();
        assertThat(KnowledgeDocumentService.violates(productTitle, KnowledgeDocumentService.PRODUCT_TITLE_UNIQUE)).isTrue();
        assertThat(KnowledgeDocumentService.violates(productTitle, KnowledgeDocumentService.ORG_TITLE_UNIQUE)).isFalse();
        assertThat(KnowledgeDocumentService.violates(other, KnowledgeDocumentService.PRODUCT_TITLE_UNIQUE)).isFalse();
    }
}
