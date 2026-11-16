package com.sellerops.knowledge.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sellerops.common.ApiException;
import com.sellerops.knowledge.document.dto.KnowledgeDocumentView;
import com.sellerops.knowledge.org.OrgKnowledgeChunk;
import com.sellerops.knowledge.org.OrgKnowledgeChunkRepository;
import com.sellerops.knowledge.org.OrgKnowledgeRevisionRepository;
import com.sellerops.knowledge.org.OrgKnowledgeSourceRepository;
import com.sellerops.knowledge.org.OrgKnowledgeType;
import com.sellerops.knowledge.org.SellerOperationsKnowledgeService;
import com.sellerops.organization.Organization;
import com.sellerops.organization.OrganizationRepository;
import com.sellerops.product.Product;
import com.sellerops.product.ProductRepository;
import com.sellerops.product.library.KnowledgeSourceType;
import com.sellerops.product.library.ProductKnowledgeChunk;
import com.sellerops.product.library.ProductKnowledgeChunkRepository;
import com.sellerops.product.library.ProductKnowledgeIndexer;
import com.sellerops.product.library.ProductKnowledgeRevisionRepository;
import com.sellerops.product.library.ProductKnowledgeSourceRepository;
import java.nio.charset.StandardCharsets;
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
 * 파일 바꾸기 (2026-11-16 integration rehearsal): an uploaded document takes the text of a new file and stays the same
 * document — id, kind, scope and 사용 여부 — while its version, kept revision and passages move. A file that cannot be
 * read, or an id from another company, changes nothing.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class KnowledgeDocumentReplaceTest {

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
    private UUID otherOrg;
    private UUID product;

    @BeforeEach
    void setUp() {
        SellerOperationsKnowledgeService orgKnowledge = new SellerOperationsKnowledgeService(orgSources, orgChunks);
        orgKnowledge.setRevisions(orgRevisions);
        service = new KnowledgeDocumentService(productSources, new ProductKnowledgeIndexer(productChunks), products,
                orgSources, orgChunks, orgKnowledge);
        service.setProductRevisions(productRevisions);
        org = org("파일 바꾸기 상점");
        otherOrg = org("다른 상점");
        Product p = new Product();
        p.setOrgId(org);
        p.setName("[합성] 스테인리스 텀블러 500ml");
        p.setStatus("ACTIVE");
        product = products.save(p).getId();
    }

    private UUID org(String name) {
        Organization o = new Organization();
        o.setName(name);
        return organizations.save(o).getId();
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private KnowledgeDocumentView orgDoc() {
        return service.importDocument(org, KnowledgeDocumentService.Scope.ORG, null, null,
                OrgKnowledgeType.SHIPPING_POLICY, "01_배송정책.txt",
                utf8("배송비는 3,000원이며, 5만원 이상 구매 시 무료입니다. 평일 오후 3시 이전 결제는 당일 출고합니다."),
                null, "판매자");
    }

    private KnowledgeDocumentView productDoc() {
        return service.importDocument(org, KnowledgeDocumentService.Scope.PRODUCT, product,
                KnowledgeSourceType.DESCRIPTION, null, "12_텀블러_상세.md",
                utf8("# 텀블러 안내\n\n보온 6시간, 보냉 12시간입니다. 식기세척기 사용은 권하지 않습니다."), null, "판매자");
    }

    @Test
    @DisplayName("ORG: the same document takes the new file — id, kind and scope kept; version, revision and passages move")
    void anOrgDocumentIsReplacedInPlace() {
        KnowledgeDocumentView before = orgDoc();
        List<UUID> oldChunks = orgChunks.findAllBySourceId(before.sourceId()).stream().map(OrgKnowledgeChunk::getId).toList();

        KnowledgeDocumentView after = service.replaceFile(org, before.sourceId(), "01_배송정책_개정.txt",
                utf8("배송비는 3,000원이며, 7만원 이상 구매 시 무료입니다. 평일 오후 3시 이전 결제는 당일 출고합니다."));

        assertThat(after.sourceId()).isEqualTo(before.sourceId());
        assertThat(after.scope()).isEqualTo("ORG");
        assertThat(after.kind()).isEqualTo("SHIPPING_POLICY");
        assertThat(after.title()).isEqualTo(before.title());
        assertThat(after.fileName()).isEqualTo("01_배송정책_개정.txt");
        assertThat(after.active()).isTrue();
        assertThat(after.version()).isEqualTo(before.version() + 1);
        assertThat(orgRevisions.findAllByOrgIdAndSourceIdOrderByVersionAsc(org, before.sourceId()))
                .singleElement().satisfies(r -> {
                    assertThat(r.getVersion()).isEqualTo(1);
                    assertThat(r.getBody()).contains("5만원");
                });
        assertThat(orgSources.findById(before.sourceId()).orElseThrow().getBody()).contains("7만원").doesNotContain("5만원");
        // The passages a draft cited are gone: the existing recheck reads that as «the source changed».
        assertThat(orgChunks.findAllBySourceId(before.sourceId())).extracting(OrgKnowledgeChunk::getId)
                .doesNotContainAnyElementsOf(oldChunks);
        assertThat(orgChunks.findAllBySourceId(before.sourceId())).extracting(OrgKnowledgeChunk::getContent)
                .anyMatch(c -> c.contains("7만원"));
    }

    @Test
    @DisplayName("PRODUCT: the same product document, same product and kind; version and kept revision move")
    void aProductDocumentIsReplacedInPlace() {
        KnowledgeDocumentView before = productDoc();
        List<UUID> oldChunks = productChunks.findAllBySourceId(before.sourceId()).stream()
                .map(ProductKnowledgeChunk::getId).toList();

        KnowledgeDocumentView after = service.replaceFile(org, before.sourceId(), "12_텀블러_상세.txt",
                utf8("텀블러 안내\n\n보온 8시간, 보냉 16시간입니다. 식기세척기 사용은 권하지 않습니다."));

        assertThat(after.sourceId()).isEqualTo(before.sourceId());
        assertThat(after.scope()).isEqualTo("PRODUCT");
        assertThat(after.productId()).isEqualTo(product);
        assertThat(after.kind()).isEqualTo("DESCRIPTION");
        assertThat(after.version()).isEqualTo(2);
        assertThat(productRevisions.findAllByOrgIdAndSourceIdOrderByVersionAsc(org, before.sourceId()))
                .singleElement().satisfies(r -> {
                    assertThat(r.getBody()).contains("보온 6시간");
                    assertThat(r.getDocumentName()).isEqualTo("12_텀블러_상세.md");
                });
        assertThat(productChunks.findAllBySourceId(before.sourceId())).extracting(ProductKnowledgeChunk::getId)
                .doesNotContainAnyElementsOf(oldChunks);
    }

    @Test
    @DisplayName("a document turned off stays off when its file is replaced — replacing is not switching on")
    void anOffDocumentStaysOff() {
        KnowledgeDocumentView doc = productDoc();
        service.setActive(org, doc.sourceId(), false);

        KnowledgeDocumentView after = service.replaceFile(org, doc.sourceId(), "12_텀블러_상세.txt",
                utf8("텀블러 안내\n\n보온 8시간, 보냉 16시간입니다. 식기세척기 사용은 권하지 않습니다."));

        assertThat(after.active()).isFalse();
        assertThat(productSources.findById(doc.sourceId()).orElseThrow().isActive()).isFalse();
    }

    @Test
    @DisplayName("a file that cannot be read — scan, wrong format, empty — changes nothing at all")
    void anUnreadableFileChangesNothing() {
        KnowledgeDocumentView doc = orgDoc();
        String body = orgSources.findById(doc.sourceId()).orElseThrow().getBody();
        List<UUID> chunks = orgChunks.findAllBySourceId(doc.sourceId()).stream().map(OrgKnowledgeChunk::getId).toList();

        assertThatThrownBy(() -> service.replaceFile(org, doc.sourceId(), "스캔.txt", utf8("   \n  ")))
                .isInstanceOf(ApiException.class).hasMessageContaining("스캔");
        assertThatThrownBy(() -> service.replaceFile(org, doc.sourceId(), "재고표.xlsx", utf8("PK".repeat(40))))
                .isInstanceOf(ApiException.class).hasMessageContaining("엑셀로 관리");
        assertThatThrownBy(() -> service.replaceFile(org, doc.sourceId(), "빈.txt", new byte[0]))
                .isInstanceOf(ApiException.class);

        var source = orgSources.findById(doc.sourceId()).orElseThrow();
        assertThat(source.getBody()).isEqualTo(body);
        assertThat(source.getVersion()).isEqualTo(1);
        assertThat(source.getDocumentName()).isEqualTo("01_배송정책.txt");
        assertThat(orgRevisions.findAllByOrgIdAndSourceIdOrderByVersionAsc(org, doc.sourceId())).isEmpty();
        assertThat(orgChunks.findAllBySourceId(doc.sourceId())).extracting(OrgKnowledgeChunk::getId)
                .containsExactlyInAnyOrderElementsOf(chunks);
    }

    @Test
    @DisplayName("another company's document id resolves to nothing, and that document is untouched")
    void anotherCompanysDocumentIsNotFound() {
        KnowledgeDocumentView doc = productDoc();

        assertThatThrownBy(() -> service.replaceFile(otherOrg, doc.sourceId(), "12_텀블러_상세.txt",
                utf8("텀블러 안내\n\n보온 1시간입니다. 아무 말이나 적어 둔 다른 회사의 파일입니다.")))
                .isInstanceOf(ApiException.class).hasMessageContaining("찾을 수 없습니다");
        var source = productSources.findById(doc.sourceId()).orElseThrow();
        assertThat(source.getBody()).contains("보온 6시간");
        assertThat(source.getVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("the same text again keeps the version — a version says which text a citation stood on")
    void theSameTextKeepsTheVersion() {
        KnowledgeDocumentView doc = orgDoc();
        KnowledgeDocumentView again = service.replaceFile(org, doc.sourceId(), "01_배송정책_사본.txt",
                utf8("배송비는 3,000원이며, 5만원 이상 구매 시 무료입니다. 평일 오후 3시 이전 결제는 당일 출고합니다."));
        assertThat(again.version()).isEqualTo(1);
        assertThat(again.fileName()).isEqualTo("01_배송정책_사본.txt");
        assertThat(orgRevisions.findAllByOrgIdAndSourceIdOrderByVersionAsc(org, doc.sourceId())).isEmpty();
    }
}
