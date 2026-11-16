package com.sellerops.knowledge.foldersync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sellerops.common.ApiException;
import com.sellerops.knowledge.document.KnowledgeDocumentService;
import com.sellerops.knowledge.org.OrgKnowledgeChunkRepository;
import com.sellerops.knowledge.org.OrgKnowledgeRevisionRepository;
import com.sellerops.knowledge.org.OrgKnowledgeSource;
import com.sellerops.knowledge.org.OrgKnowledgeSourceRepository;
import com.sellerops.knowledge.org.OrgKnowledgeType;
import com.sellerops.knowledge.org.SellerOperationsKnowledgeService;
import com.sellerops.knowledge.org.dto.OrgKnowledgeRequest;
import com.sellerops.organization.Organization;
import com.sellerops.organization.OrganizationRepository;
import com.sellerops.product.Product;
import com.sellerops.product.ProductRepository;
import com.sellerops.product.library.ProductKnowledgeChunkRepository;
import com.sellerops.product.library.ProductKnowledgeIndexer;
import com.sellerops.product.library.ProductKnowledgeRevisionRepository;
import com.sellerops.product.library.ProductKnowledgeSource;
import com.sellerops.product.library.ProductKnowledgeSourceRepository;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 자료 폴더 동기화 (owner decision 2026-11-17), driven the way the browser drives it: scan with fingerprints, send what
 * the plan says, look again. Every rule the owner set has a test here.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class KnowledgeFolderSyncServiceTest {

    @Autowired KnowledgeFolderLinkRepository links;
    @Autowired KnowledgeFolderFileRepository folderFiles;
    @Autowired ProductKnowledgeSourceRepository productSources;
    @Autowired ProductKnowledgeChunkRepository productChunks;
    @Autowired ProductKnowledgeRevisionRepository productRevisions;
    @Autowired ProductRepository products;
    @Autowired OrgKnowledgeSourceRepository orgSources;
    @Autowired OrgKnowledgeChunkRepository orgChunks;
    @Autowired OrgKnowledgeRevisionRepository orgRevisions;
    @Autowired OrganizationRepository organizations;

    private KnowledgeDocumentService documents;
    private SellerOperationsKnowledgeService orgKnowledge;
    private KnowledgeFolderSyncService sync;
    private UUID org;
    private UUID user = UUID.randomUUID();
    private UUID tumbler;

    /** The folder on the seller's PC: path → text. */
    private final Map<String, byte[]> folder = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        orgKnowledge = new SellerOperationsKnowledgeService(orgSources, orgChunks);
        orgKnowledge.setRevisions(orgRevisions);
        documents = new KnowledgeDocumentService(productSources, new ProductKnowledgeIndexer(productChunks), products,
                orgSources, orgChunks, orgKnowledge);
        documents.setProductRevisions(productRevisions);
        sync = new KnowledgeFolderSyncService(links, folderFiles, documents, orgSources, productSources, products);
        Organization o = new Organization();
        o.setName("폴더 시험 가게");
        org = organizations.save(o).getId();
        tumbler = product("[합성] 스테인리스 텀블러");
    }

    private UUID product(String name) {
        Product p = new Product();
        p.setOrgId(org);
        p.setName(name);
        p.setStatus("ACTIVE");
        return products.save(p).getId();
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private void put(String path, String text) {
        folder.put(path, utf8(text));
    }

    /** One round as the browser runs it: scan, then send every file the plan asks for. */
    private KnowledgeFolderViews.ScanResult round(boolean complete) {
        List<KnowledgeFolderViews.Entry> entries = new ArrayList<>();
        folder.forEach((p, b) -> entries.add(new KnowledgeFolderViews.Entry(p, b.length,
                KnowledgeFolderSyncService.sha256(b))));
        KnowledgeFolderViews.ScanResult r = sync.scan(org, user,
                new KnowledgeFolderViews.ScanRequest("나누리 자료", KnowledgeFolderLink.Mode.WATCH, complete, false, entries));
        for (KnowledgeFolderViews.Plan plan : r.plans()) {
            if (plan.action().equals("UPLOAD")) {
                byte[] b = folder.entrySet().stream()
                        .filter(e -> KnowledgeFolderSyncService.normalize(e.getKey()).equals(plan.path()))
                        .findFirst().orElseThrow().getValue();
                sync.upload(org, user, "판매자", plan.path(), KnowledgeFolderSyncService.sha256(b), b);
            }
        }
        return r;
    }

    private String action(KnowledgeFolderViews.ScanResult r, String path) {
        return r.plans().stream().filter(p -> p.path().equals(path)).findFirst().orElseThrow().action();
    }

    private KnowledgeFolderFile row(String path) {
        return folderFiles.findByOrgIdAndPath(org, path).orElseThrow();
    }

    private OrgKnowledgeSource orgDoc(String title) {
        return orgSources.findByOrgIdAndTitle(org, title).orElseThrow();
    }

    @Test
    @DisplayName("맨 위 파일은 회사 전체, 상품 이름과 똑같은 하위 폴더는 그 상품, 다른 이름은 「상품 확인 필요」로 멈추고 회사 전체로 가지 않는다")
    void firstSyncFilesEachFileWhereTheOwnerSaid() {
        put("배송 안내.txt", "평일 오후 3시 이전 결제 주문은 당일 출고합니다. 택배는 CJ대한통운입니다.");
        put("[합성] 스테인리스 텀블러/사용 설명.md", "뚜껑은 손으로 씻어 주세요. 식기세척기는 쓰지 마세요.");
        put("없는 상품/설명.txt", "이 폴더 이름과 같은 상품은 없습니다. 회사 전체 자료가 되면 안 됩니다.");
        put("사진.jpg", "jpg");
        put(".DS_Store", "x");

        KnowledgeFolderViews.ScanResult r = round(true);

        assertThat(action(r, "배송 안내.txt")).isEqualTo("UPLOAD");
        assertThat(action(r, "[합성] 스테인리스 텀블러/사용 설명.md")).isEqualTo("UPLOAD");
        assertThat(action(r, "없는 상품/설명.txt")).isEqualTo("HELD");
        assertThat(action(r, "사진.jpg")).isEqualTo("IGNORED");
        assertThat(action(r, ".DS_Store")).isEqualTo("IGNORED");

        OrgKnowledgeSource shipping = orgDoc("배송 안내");
        assertThat(shipping.getDocumentName()).isEqualTo("배송 안내.txt");
        assertThat(shipping.isActive()).isTrue();
        ProductKnowledgeSource manual = productSources.findByOrgIdAndProductIdAndTitle(org, tumbler, "사용 설명").orElseThrow();
        assertThat(manual.getBody()).contains("식기세척기");
        assertThat(orgSources.findByOrgIdAndTitle(org, "설명")).isEmpty();
        assertThat(row("없는 상품/설명.txt").getHeldReason()).isEqualTo(KnowledgeFolderFile.Reason.PRODUCT_UNMATCHED);
        assertThat(sync.status(org).held()).singleElement()
                .satisfies(h -> assertThat(h.message()).contains("상품 확인 필요"));
        assertThat(sync.status(org).synced()).isEqualTo(2);
    }

    @Test
    @DisplayName("다시 보면 안 바뀐 파일은 그대로, 바뀐 파일은 같은 자료의 새 판이 되고 이전 판은 남는다")
    void changedFileBecomesANewVersion() {
        put("배송 안내.txt", "평일 오후 3시 이전 결제 주문은 당일 출고합니다.");
        round(true);
        UUID id = orgDoc("배송 안내").getId();

        assertThat(action(round(true), "배송 안내.txt")).isEqualTo("UNCHANGED");

        put("배송 안내.txt", "평일 오후 2시 이전 결제 주문은 당일 출고합니다.");
        assertThat(action(round(true), "배송 안내.txt")).isEqualTo("UPLOAD");
        OrgKnowledgeSource after = orgDoc("배송 안내");
        assertThat(after.getId()).isEqualTo(id);
        assertThat(after.getVersion()).isEqualTo(2);
        assertThat(after.getBody()).contains("오후 2시");
        assertThat(orgRevisions.findAll()).anySatisfy(rev -> assertThat(rev.getBody()).contains("오후 3시"));
    }

    @Test
    @DisplayName("폴더에서 지운 파일은 사용 중지(삭제 아님), 다시 넣으면 다시 켜진다 — 일부만 읽은 목록·빈 목록으로는 끄지 않는다")
    void removedFileSwitchesItsDocumentOff() {
        put("배송 안내.txt", "평일 오후 3시 이전 결제 주문은 당일 출고합니다.");
        put("교환 안내.txt", "교환은 상품을 받은 날부터 7일 안에 신청해 주세요.");
        round(true);
        byte[] kept = folder.remove("교환 안내.txt");

        round(false);
        assertThat(orgDoc("교환 안내").isActive()).as("a partial listing switches nothing off").isTrue();

        KnowledgeFolderViews.ScanResult r = round(true);
        assertThat(r.deactivated()).isEqualTo(1);
        assertThat(orgDoc("교환 안내").isActive()).isFalse();
        assertThat(orgSources.findByOrgIdAndTitle(org, "교환 안내")).as("never deleted").isPresent();
        assertThat(row("교환 안내.txt").getState()).isEqualTo(KnowledgeFolderFile.State.MISSING);
        assertThat(sync.status(org).missing()).singleElement()
                .satisfies(m -> assertThat(m.path()).isEqualTo("교환 안내.txt"));

        Map<String, byte[]> all = new LinkedHashMap<>(folder);
        folder.clear();
        KnowledgeFolderViews.ScanResult empty = round(true);
        assertThat(empty.warning()).isEqualTo("EMPTY_FOLDER");
        assertThat(orgDoc("배송 안내").isActive()).as("an empty listing switches nothing off").isTrue();
        folder.putAll(all);

        folder.put("교환 안내.txt", kept);
        KnowledgeFolderViews.ScanResult back = round(true);
        assertThat(action(back, "교환 안내.txt")).isEqualTo("REACTIVATED");
        assertThat(orgDoc("교환 안내").isActive()).isTrue();
    }

    @Test
    @DisplayName("암호 PDF·읽을 수 없는 파일은 그 파일만 멈추고, 같은 파일이면 다시 보내지 않으며, 다른 파일은 계속 동기화된다")
    void unreadableFilesStopAlone() throws Exception {
        folder.put("잠긴 안내.pdf", lockedPdf());
        put("깨진 안내.pdf", "this is not a pdf");
        put("배송 안내.txt", "평일 오후 3시 이전 결제 주문은 당일 출고합니다.");
        round(true);

        assertThat(row("잠긴 안내.pdf").getHeldReason()).isEqualTo(KnowledgeFolderFile.Reason.ENCRYPTED);
        assertThat(row("잠긴 안내.pdf").getHeldMessage()).contains("암호");
        assertThat(row("깨진 안내.pdf").getHeldReason()).isEqualTo(KnowledgeFolderFile.Reason.READ_FAILED);
        assertThat(orgDoc("배송 안내").isActive()).isTrue();

        KnowledgeFolderViews.ScanResult again = round(true);
        assertThat(action(again, "잠긴 안내.pdf")).isEqualTo("HELD");

        put("깨진 안내.pdf", "다시 저장한 안내: 평일 오후 3시 이전 결제 주문은 당일 출고합니다.");
        folder.put("깨진 안내.txt", folder.remove("깨진 안내.pdf"));
        round(true);
        assertThat(orgDoc("깨진 안내").isActive()).isTrue();
        assertThat(folderFiles.findByOrgIdAndPath(org, "깨진 안내.pdf")).as("the stopped file left the folder").isEmpty();
    }

    @Test
    @DisplayName("직접 쓴 기준과 이름이 같으면 멈추고 폴더로 덮어쓸 수 없다; 같은 파일 이름으로 손수 올린 자료는 폴더가 이어받는다")
    void titleConflicts() {
        orgKnowledge.create(org, new OrgKnowledgeRequest(OrgKnowledgeType.SHIPPING_POLICY, "배송 안내",
                "직접 쓴 배송 기준", null), user, "판매자");
        documents.importDocument(org, KnowledgeDocumentService.Scope.ORG, null, null, null, "교환 안내.txt",
                utf8("교환은 상품을 받은 날부터 7일 안에 신청해 주세요."), user, "판매자");
        put("배송 안내.txt", "평일 오후 3시 이전 결제 주문은 당일 출고합니다.");
        put("교환 안내.txt", "교환은 상품을 받은 날부터 14일 안에 신청해 주세요.");
        round(true);

        KnowledgeFolderFile shipping = row("배송 안내.txt");
        assertThat(shipping.getHeldReason()).isEqualTo(KnowledgeFolderFile.Reason.CONFLICT_TITLE);
        assertThat(orgDoc("배송 안내").getBody()).isEqualTo("직접 쓴 배송 기준");
        assertThat(sync.status(org).held()).singleElement().satisfies(h -> assertThat(h.canUseFolder()).isFalse());
        assertThatThrownBy(() -> sync.useFolder(org, shipping.getId())).isInstanceOf(ApiException.class);

        OrgKnowledgeSource exchange = orgDoc("교환 안내");
        assertThat(exchange.getBody()).contains("14일");
        assertThat(exchange.getVersion()).isEqualTo(2);
        assertThat(row("교환 안내.txt").getState()).isEqualTo(KnowledgeFolderFile.State.SYNCED);
    }

    @Test
    @DisplayName("이름만 같은 다른 파일의 자료는 멈추고, 판매자가 폴더 쪽을 고르면 다음 확인 때 그 자료의 새 판이 된다")
    void sameTitleOtherFileNeedsTheSellersChoice() {
        documents.importDocument(org, KnowledgeDocumentService.Scope.ORG, null, null, null, "반품 안내.docx-old.txt",
                utf8("반품은 상품을 받은 날부터 7일 안에 신청해 주세요."), user, "판매자");
        // titleFor strips the last extension only; make a same-title file with a different name.
        String title = KnowledgeDocumentService.titleFor("반품 안내.docx-old.txt");
        put(title + ".md", "반품은 상품을 받은 날부터 10일 안에 신청해 주세요.");
        round(true);
        KnowledgeFolderFile r = row(title + ".md");
        assertThat(r.getHeldReason()).isEqualTo(KnowledgeFolderFile.Reason.CONFLICT_TITLE);
        assertThat(sync.status(org).held()).singleElement().satisfies(h -> assertThat(h.canUseFolder()).isTrue());

        sync.useFolder(org, r.getId());
        assertThat(action(round(true), title + ".md")).isEqualTo("UPLOAD");
        assertThat(orgDoc(title).getBody()).contains("10일");
        assertThat(row(title + ".md").getState()).isEqualTo(KnowledgeFolderFile.State.SYNCED);
    }

    @Test
    @DisplayName("폴더가 쓴 자료를 웹에서 고친 뒤 파일이 바뀌면 덮어쓰지 않고 멈춘다; 폴더 쪽을 고르면 덮어쓴다")
    void editedOnTheWebIsNotOverwrittenSilently() {
        put("배송 안내.txt", "평일 오후 3시 이전 결제 주문은 당일 출고합니다.");
        round(true);
        UUID id = orgDoc("배송 안내").getId();
        documents.replaceFile(org, id, "배송 안내.txt", utf8("웹에서 바꾼 안내: 평일 오후 4시 이전 결제 주문은 당일 출고합니다."));

        put("배송 안내.txt", "평일 오후 2시 이전 결제 주문은 당일 출고합니다.");
        round(true);
        KnowledgeFolderFile r = row("배송 안내.txt");
        assertThat(r.getHeldReason()).isEqualTo(KnowledgeFolderFile.Reason.CONFLICT_EDITED);
        assertThat(orgDoc("배송 안내").getBody()).contains("오후 4시");

        sync.useFolder(org, r.getId());
        round(true);
        assertThat(orgDoc("배송 안내").getBody()).contains("오후 2시");
        assertThat(row("배송 안내.txt").getState()).isEqualTo(KnowledgeFolderFile.State.SYNCED);
    }

    @Test
    @DisplayName("macOS가 쓰는 NFD 이름도 상품 이름과 맞고, 나중에 상품을 만들면 멈췄던 폴더가 올라간다")
    void nfdNamesAndLateProducts() {
        String nfd = Normalizer.normalize("[합성] 스테인리스 텀블러/세척.txt", Normalizer.Form.NFD);
        put(nfd, "뚜껑 패킹은 분리해서 미지근한 물로 씻어 주세요.");
        put("보온병/세척.txt", "보온병은 처음 쓰기 전에 뜨거운 물로 헹궈 주세요.");
        round(true);
        assertThat(productSources.findByOrgIdAndProductIdAndTitle(org, tumbler, "세척")).isPresent();
        assertThat(row("보온병/세척.txt").getHeldReason()).isEqualTo(KnowledgeFolderFile.Reason.PRODUCT_UNMATCHED);

        UUID bottle = product("보온병");
        round(true);
        assertThat(productSources.findByOrgIdAndProductIdAndTitle(org, bottle, "세척")).isPresent();
        assertThat(row("보온병/세척.txt").getState()).isEqualTo(KnowledgeFolderFile.State.SYNCED);
    }

    @Test
    @DisplayName("다른 폴더를 연결하려면 먼저 바꾸기를 골라야 하고, 폴더 밖 경로는 받지 않는다")
    void folderMismatchAndPathSafety() {
        put("배송 안내.txt", "평일 오후 3시 이전 결제 주문은 당일 출고합니다.");
        round(true);
        KnowledgeFolderViews.ScanRequest other = new KnowledgeFolderViews.ScanRequest("다른 폴더",
                KnowledgeFolderLink.Mode.UPLOAD, true, false, List.of());
        assertThatThrownBy(() -> sync.scan(org, user, other)).isInstanceOf(ApiException.class)
                .hasMessageContaining("나누리 자료");
        assertThatThrownBy(() -> KnowledgeFolderSyncService.normalize("../밖/비밀.txt"))
                .isInstanceOf(ApiException.class);
        assertThat(KnowledgeFolderSyncService.normalize("\\하위\\\\파일.txt")).isEqualTo("하위/파일.txt");
        assertThatThrownBy(() -> sync.upload(org, user, "판매자", "배송 안내.txt", "0".repeat(64),
                utf8("평일 오후 3시 이전 결제 주문은 당일 출고합니다."))).isInstanceOf(ApiException.class)
                .hasMessageContaining("바뀌었어요");
    }

    private static byte[] lockedPdf() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 700);
                content.showText("Ships the same day when paid before 3 pm on a weekday.");
                content.endText();
            }
            org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy policy =
                    new org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy("owner-pw", "user-pw",
                            new org.apache.pdfbox.pdmodel.encryption.AccessPermission());
            policy.setEncryptionKeyLength(128);
            document.protect(policy);
            document.save(out);
        }
        return out.toByteArray();
    }
}
