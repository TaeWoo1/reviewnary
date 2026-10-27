package com.sellerops.knowledge.workbook;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.attention.reply.ReviewReplyTemplateKey;
import com.sellerops.attention.reply.ReviewReplyTemplateRepository;
import com.sellerops.attention.reply.ReviewReplyTemplateService;
import com.sellerops.knowledge.KnowledgeScope;
import com.sellerops.knowledge.candidate.KnowledgeCandidate;
import com.sellerops.knowledge.candidate.KnowledgeCandidateRepository;
import com.sellerops.knowledge.candidate.KnowledgeCandidateService;
import com.sellerops.knowledge.memory.AnswerMemoryRepository;
import com.sellerops.knowledge.org.OrgKnowledgeChunkRepository;
import com.sellerops.knowledge.org.OrgKnowledgeSourceRepository;
import com.sellerops.knowledge.org.SellerOperationsKnowledgeService;
import com.sellerops.knowledge.style.AnswerStyleService;
import com.sellerops.knowledge.style.AnswerTone;
import com.sellerops.knowledge.style.OrganizationAnswerStyleRepository;
import com.sellerops.organization.Organization;
import com.sellerops.organization.OrganizationRepository;
import com.sellerops.product.ChannelProductRepository;
import com.sellerops.product.Product;
import com.sellerops.product.ProductRepository;
import com.sellerops.product.ProductVariantRepository;
import com.sellerops.product.library.KnowledgeSourceType;
import com.sellerops.product.library.ProductKnowledgeChunkRepository;
import com.sellerops.product.library.ProductKnowledgeIndexer;
import com.sellerops.product.library.ProductKnowledgeLibraryService;
import com.sellerops.product.library.ProductKnowledgeSource;
import com.sellerops.product.library.ProductKnowledgeSourceRepository;
import com.sellerops.product.library.dto.KnowledgeSourceRequest;
import com.sellerops.review.draft.CustomerPhraseLane;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 말투 · 답변 템플릿 · 안내 매뉴얼 as one workbook, and 「고객은 이렇게 말해요」 reaching a review (owner, 2026-10-27).
 *
 * <p>The two properties the owner asked for in one sentence each: nothing 확인 전 reaches a reply, and a review that
 * says what customers say finds the guidance written for it.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class KnowledgeWorkbookIT {

    @Autowired OrganizationRepository organizations;
    @Autowired ProductRepository products;
    @Autowired ProductVariantRepository variants;
    @Autowired ProductKnowledgeSourceRepository productSources;
    @Autowired ProductKnowledgeChunkRepository productChunks;
    @Autowired OrgKnowledgeSourceRepository orgSources;
    @Autowired OrgKnowledgeChunkRepository orgChunks;
    @Autowired AnswerMemoryRepository memories;
    @Autowired KnowledgeCandidateRepository candidateRows;
    @Autowired OrganizationAnswerStyleRepository styleRows;
    @Autowired ReviewReplyTemplateRepository templateRows;
    @Autowired ChannelProductRepository listings;

    private KnowledgeCandidateService candidates;
    private ProductKnowledgeLibraryService library;
    private AnswerStyleService styles;
    private ReviewReplyTemplateService templates;
    private KnowledgeWorkbookService workbook;
    private CustomerPhraseLane lane;
    private UUID org;
    private UUID productId;

    @BeforeEach
    void setUp() {
        SellerOperationsKnowledgeService orgKnowledge = new SellerOperationsKnowledgeService(orgSources, orgChunks);
        library = new ProductKnowledgeLibraryService(products, productSources, productChunks, variants);
        candidates = new KnowledgeCandidateService(candidateRows, memories, productSources,
                new ProductKnowledgeIndexer(productChunks), products, orgSources, orgKnowledge, variants);
        styles = new AnswerStyleService(styleRows);
        templates = new ReviewReplyTemplateService(templateRows);
        workbook = new KnowledgeWorkbookService(styles, templates, library, productSources, orgKnowledge, orgSources,
                candidates, candidateRows, products, listings);
        lane = new CustomerPhraseLane(productSources, orgSources);

        Organization o = new Organization();
        o.setName("엑셀 QA");
        org = organizations.save(o).getId();
        Product p = new Product();
        p.setOrgId(org);
        p.setName("일체형 전선몰딩");
        p.setSku("SKU-" + UUID.randomUUID());
        p.setStatus("ACTIVE");
        productId = products.save(p).getId();
    }

    @Test
    @DisplayName("an accepted proposal carries its customer phrases, and a review that says one finds it — a denial does not")
    void customerPhrasesTravelFromProposalToReview() {
        UUID id = candidates.suggestGuidance(org, productId, "접착 — 실크벽지", "실크벽지에서는 폼 양면테이프를 덧대 주세요.", 9,
                List.of("떨어져요", "떨어졌", "들떠")).getId();
        assertThat(lane.hits(org, productId, "하루만에 다 떨어졌어요")).as("확인 전 is not knowledge").isEmpty();

        candidates.accept(org, id, null, null, KnowledgeSourceType.USAGE, null, null, null, "판매자");

        assertThat(productSources.findAll()).singleElement().extracting(ProductKnowledgeSource::getCustomerPhrases)
                .isEqualTo("떨어져요\n떨어졌\n들떠");
        assertThat(lane.hits(org, productId, "하루만에 다 떨어졌어요")).singleElement().satisfies(hit -> {
            assertThat(hit.scope()).isEqualTo(KnowledgeScope.PRODUCT);
            assertThat(hit.text()).contains("폼 양면테이프");
        });
        assertThat(lane.hits(org, productId, "한 달째 안 떨어져요 최고")).isEmpty();
        assertThat(lane.hits(org, UUID.randomUUID(), "하루만에 다 떨어졌어요")).as("another product's review").isEmpty();
    }

    @Test
    @DisplayName("a document that still carries a 【확인】 blank is never a hit, whatever its phrases")
    void aBlankIsNeverAHit() {
        library.create(org, productId, new KnowledgeSourceRequest(KnowledgeSourceType.FAQ, "마감캡",
                "마감캡은 【확인: 수량】개 들어 있습니다.", null, null, List.of("마감캡")), null, "판매자");
        assertThat(lane.hits(org, productId, "마감캡이 하나만 왔어요")).isEmpty();
    }

    @Test
    @DisplayName("the workbook round-trips: read first changes nothing, 사용 applies, 확인 전 files a proposal, a blank is refused")
    void theWorkbookRoundTrips() throws IOException {
        library.create(org, productId, new KnowledgeSourceRequest(KnowledgeSourceType.USAGE, "자르기",
                "커터칼로 자르세요.", null, null, List.of("자르기 어려워요")), null, "판매자");
        candidates.suggestGuidance(org, productId, "마감캡 구성", "한 세트에 【확인: 수량】개 들어 있습니다.", 5,
                List.of("마감캡"));

        byte[] file;
        try (Workbook book = new XSSFWorkbook(new ByteArrayInputStream(workbook.export(org)))) {
            assertThat(book.getSheet(KnowledgeWorkbookService.SHEET_GUIDE)).isNotNull();
            Sheet style = book.getSheet(KnowledgeWorkbookService.SHEET_STYLE);
            Row tone = rowWith(style, 0, "말투");
            tone.getCell(2).setCellValue("친근하게");
            tone.getCell(3).setCellValue("사용");
            Row greeting = rowWith(style, 0, "첫 인사");
            greeting.getCell(2).setCellValue("안녕하세요, 선바로입니다.");
            greeting.getCell(3).setCellValue("확인 전");

            Sheet tpl = book.getSheet(KnowledgeWorkbookService.SHEET_TEMPLATES);
            Row positive = rowWith(tpl, 5, ReviewReplyTemplateKey.POSITIVE.category());
            positive.getCell(3).setCellValue("안녕하세요, 고객님. 마음에 드셨다니 기쁩니다. 감사합니다.");
            positive.getCell(4).setCellValue("사용");
            Row pricing = rowWith(tpl, 5, ReviewReplyTemplateKey.PRICING.category());
            pricing.getCell(3).setCellValue("【확인: 할인 안내】");
            pricing.getCell(4).setCellValue("사용");

            Sheet manual = book.getSheet(KnowledgeWorkbookService.SHEET_MANUAL);
            Row cutting = rowWith(manual, 5, "자르기");
            assertThat(cutting.getCell(0).getStringCellValue()).startsWith("P-");
            assertThat(cutting.getCell(8).getStringCellValue()).isEqualTo("사용");
            cutting.getCell(6).setCellValue("단단한 편이라 커터칼로 여러 번 그어 자르세요.");
            Row caps = rowWith(manual, 5, "마감캡 구성");
            assertThat(caps.getCell(0).getStringCellValue()).startsWith("C-");
            assertThat(caps.getCell(8).getStringCellValue()).isEqualTo("확인 전");
            caps.getCell(8).setCellValue("사용");
            Row fresh = manual.getRow(caps.getRowNum() + 1);
            fresh.getCell(1).setCellValue("상품");
            fresh.getCell(3).setCellValue("일체형 전선몰딩");
            fresh.getCell(5).setCellValue("실크벽지 부착");
            fresh.getCell(6).setCellValue("실크벽지에서는 폼 양면테이프를 덧대 주세요.");
            fresh.getCell(7).setCellValue("떨어져요\n들떠");
            fresh.getCell(8).setCellValue("사용");
            Row proposal = manual.getRow(caps.getRowNum() + 2);
            proposal.getCell(1).setCellValue("회사 전체");
            proposal.getCell(5).setCellValue("포장 방식");
            proposal.getCell(6).setCellValue("【확인: 포장 방식】");
            proposal.getCell(8).setCellValue("확인 전");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            book.write(out);
            file = out.toByteArray();
        }

        KnowledgeWorkbookService.Result preview = workbook.read(org, new ByteArrayInputStream(file), false, null, "판매자");
        assertThat(preview.applied()).isFalse();
        assertThat(preview.changes()).extracting(KnowledgeWorkbookService.Line::text).containsExactlyInAnyOrder(
                "말투 바꿈 — 말투", "「별점 4~5점 기본 문구」 문구 바꿈", "「자르기」 안내 고침", "「실크벽지 부착」 새 안내 등록",
                "「포장 방식」 확인 필요에 제안으로 넣음 (확인 전)");
        assertThat(preview.problems()).extracting(KnowledgeWorkbookService.Line::text).anyMatch(t -> t.contains("【확인"))
                .hasSize(2);
        assertThat(styles.profileFor(org).tone()).as("reading changes nothing").isEqualTo(AnswerTone.POLITE);
        assertThat(productSources.findAll()).hasSize(1);

        KnowledgeWorkbookService.Result done = workbook.read(org, new ByteArrayInputStream(file), true, null, "판매자");
        assertThat(done.applied()).isTrue();
        assertThat(done.changes()).hasSize(5);
        assertThat(styles.profileFor(org).tone()).isEqualTo(AnswerTone.FRIENDLY);
        assertThat(styles.profileFor(org).greeting()).as("확인 전 is left in the file").isNull();
        assertThat(templates.bodyFor(org, ReviewReplyTemplateKey.POSITIVE)).contains("기쁩니다");
        assertThat(templates.isCustomized(org, ReviewReplyTemplateKey.PRICING)).isFalse();
        assertThat(productSources.findAll()).extracting(ProductKnowledgeSource::getBody).containsExactlyInAnyOrder(
                "단단한 편이라 커터칼로 여러 번 그어 자르세요.", "실크벽지에서는 폼 양면테이프를 덧대 주세요.");
        assertThat(lane.hits(org, productId, "벽에 붙였더니 들떠요")).singleElement()
                .satisfies(hit -> assertThat(hit.heading()).isEqualTo("실크벽지 부착"));
        List<KnowledgeCandidate> open = candidateRows.findAllByOrgIdAndStateOrderByEvidenceCountDescCreatedAtDesc(org,
                "OPEN");
        assertThat(open).extracting(KnowledgeCandidate::getSubject).containsExactlyInAnyOrder("마감캡 구성", "포장 방식");

        // The same file again changes nothing that is already true.
        KnowledgeWorkbookService.Result again = workbook.read(org, new ByteArrayInputStream(file), false, null, "판매자");
        assertThat(again.changes()).extracting(KnowledgeWorkbookService.Line::text)
                .doesNotContain("「자르기」 안내 고침", "「실크벽지 부착」 새 안내 등록", "「별점 4~5점 기본 문구」 문구 바꿈");
    }

    @Test
    @DisplayName("사용 안 함 turns a guidance off without deleting it, and it stops being a hit")
    void offTurnsAGuidanceOff() throws IOException {
        library.create(org, productId, new KnowledgeSourceRequest(KnowledgeSourceType.USAGE, "자르기",
                "커터칼로 자르세요.", null, null, List.of("자르기 어려워요")), null, "판매자");
        byte[] file;
        try (Workbook book = new XSSFWorkbook(new ByteArrayInputStream(workbook.export(org)))) {
            rowWith(book.getSheet(KnowledgeWorkbookService.SHEET_MANUAL), 5, "자르기").getCell(8).setCellValue("사용안함");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            book.write(out);
            file = out.toByteArray();
        }
        workbook.read(org, new ByteArrayInputStream(file), true, null, "판매자");
        assertThat(productSources.findAll()).singleElement().extracting(ProductKnowledgeSource::isActive).isEqualTo(false);
        assertThat(lane.hits(org, productId, "자르기 어려워요")).isEmpty();
    }

    @Test
    @DisplayName("the preview says where each change applies and what the text is now and will be")
    void thePreviewShowsBeforeAndAfter() throws IOException {
        library.create(org, productId, new KnowledgeSourceRequest(KnowledgeSourceType.USAGE, "자르기",
                "커터칼로 자르세요.", null, null, List.of()), null, "판매자");
        String positiveNow = templates.bodyFor(org, ReviewReplyTemplateKey.POSITIVE);
        byte[] file = edited(book -> {
            Row tone = rowWith(book.getSheet(KnowledgeWorkbookService.SHEET_STYLE), 0, "말투");
            tone.getCell(2).setCellValue("친근하게");
            tone.getCell(3).setCellValue("사용");
            Row positive = rowWith(book.getSheet(KnowledgeWorkbookService.SHEET_TEMPLATES), 5,
                    ReviewReplyTemplateKey.POSITIVE.category());
            positive.getCell(3).setCellValue("마음에 드셨다니 기쁩니다.");
            positive.getCell(4).setCellValue("사용");
            Row cutting = rowWith(book.getSheet(KnowledgeWorkbookService.SHEET_MANUAL), 5, "자르기");
            cutting.getCell(6).setCellValue("커터칼로 여러 번 그어 자르세요.");
            cutting.getCell(7).setCellValue("잘 안 잘려요");
        });

        List<KnowledgeWorkbookService.Line> changes = workbook.read(org, new ByteArrayInputStream(file), false, null,
                "판매자").changes();

        assertThat(changes).filteredOn(l -> l.text().startsWith("말투")).singleElement().satisfies(l -> {
            assertThat(l.before()).isEqualTo("말투: 정중하게");
            assertThat(l.after()).isEqualTo("말투: 친근하게");
            assertThat(l.where()).isEqualTo("문의 답변 · 리뷰 답글");
        });
        assertThat(changes).filteredOn(l -> l.text().contains("별점 4~5점")).singleElement().satisfies(l -> {
            assertThat(l.before()).isEqualTo(positiveNow.strip());
            assertThat(l.after()).isEqualTo("마음에 드셨다니 기쁩니다.");
            assertThat(l.where()).isEqualTo("별점이 4~5점인 리뷰에 씁니다.");
        });
        assertThat(changes).filteredOn(l -> l.text().contains("자르기")).singleElement().satisfies(l -> {
            assertThat(l.where()).isEqualTo("상품 · 일체형 전선몰딩");
            assertThat(l.before()).isEqualTo("커터칼로 자르세요.");
            assertThat(l.after()).isEqualTo("커터칼로 여러 번 그어 자르세요.\n고객은 이렇게 말해요: 잘 안 잘려요");
        });
    }

    @Test
    @DisplayName("an old file does not overwrite what was changed on a screen after it was downloaded")
    void anOldFileDoesNotOverwriteANewerScreen() throws IOException {
        ProductKnowledgeSource cutting = productSources.findById(library.create(org, productId,
                new KnowledgeSourceRequest(KnowledgeSourceType.USAGE, "자르기", "커터칼로 자르세요.", null, null,
                        List.of()), null, "판매자").id()).orElseThrow();
        byte[] old = edited(book -> {
            Row tone = rowWith(book.getSheet(KnowledgeWorkbookService.SHEET_STYLE), 0, "말투");
            tone.getCell(2).setCellValue("간결하게");
            tone.getCell(3).setCellValue("사용");
            Row positive = rowWith(book.getSheet(KnowledgeWorkbookService.SHEET_TEMPLATES), 5,
                    ReviewReplyTemplateKey.POSITIVE.category());
            positive.getCell(3).setCellValue("파일에서 고친 문구입니다.");
            positive.getCell(4).setCellValue("사용");
            rowWith(book.getSheet(KnowledgeWorkbookService.SHEET_MANUAL), 5, "자르기").getCell(6)
                    .setCellValue("파일에서 고친 안내입니다.");
            Row pricing = rowWith(book.getSheet(KnowledgeWorkbookService.SHEET_TEMPLATES), 5,
                    ReviewReplyTemplateKey.PRICING.category());
            pricing.getCell(3).setCellValue("가격 리뷰에 감사드립니다.");
            pricing.getCell(4).setCellValue("사용");
        });
        // After the download, on the screens:
        styles.save(org, new com.sellerops.knowledge.style.dto.AnswerStyleRequest(AnswerTone.FRIENDLY, null, null,
                null, null, null, List.of(), List.of(), null), null);
        templates.save(org, ReviewReplyTemplateKey.POSITIVE.category(), "화면에서 고친 문구입니다.", null);
        library.update(org, cutting.getId(), new KnowledgeSourceRequest(KnowledgeSourceType.USAGE, "자르기",
                "화면에서 고친 안내입니다.", null, null, null));

        KnowledgeWorkbookService.Result done = workbook.read(org, new ByteArrayInputStream(old), true, null, "판매자");

        assertThat(done.changes()).as("only the row nobody touched since").extracting(KnowledgeWorkbookService.Line::text)
                .containsExactly("「가격 리뷰」 문구 바꿈");
        assertThat(done.problems()).filteredOn(l -> l.text().contains(KnowledgeWorkbookService.STALE)).hasSize(3)
                .extracting(KnowledgeWorkbookService.Line::before)
                .containsExactlyInAnyOrder("친근하게", "화면에서 고친 문구입니다.", "화면에서 고친 안내입니다.");
        assertThat(styles.profileFor(org).tone()).isEqualTo(AnswerTone.FRIENDLY);
        assertThat(templates.bodyFor(org, ReviewReplyTemplateKey.POSITIVE)).isEqualTo("화면에서 고친 문구입니다.");
        assertThat(productSources.findById(cutting.getId()).orElseThrow().getBody()).isEqualTo("화면에서 고친 안내입니다.");
    }

    /** The org's workbook as downloaded now, edited by {@code edit}, as the bytes a seller would upload. */
    private byte[] edited(java.util.function.Consumer<Workbook> edit) throws IOException {
        try (Workbook book = new XSSFWorkbook(new ByteArrayInputStream(workbook.export(org)))) {
            edit.accept(book);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            book.write(out);
            return out.toByteArray();
        }
    }

    private static Row rowWith(Sheet sheet, int column, String value) {
        for (Row row : sheet) {
            if (row.getCell(column) != null && value.equals(row.getCell(column).getStringCellValue())) {
                return row;
            }
        }
        throw new AssertionError("no row with " + value + " in " + sheet.getSheetName());
    }
}
