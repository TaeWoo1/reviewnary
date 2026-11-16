package com.sellerops.knowledge.workbook;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.attention.reply.ReviewReplyTemplateRepository;
import com.sellerops.attention.reply.ReviewReplyTemplateService;
import com.sellerops.knowledge.candidate.KnowledgeCandidateRepository;
import com.sellerops.knowledge.candidate.KnowledgeCandidateService;
import com.sellerops.knowledge.memory.AnswerMemoryRepository;
import com.sellerops.knowledge.org.OrgKnowledgeChunkRepository;
import com.sellerops.knowledge.org.OrgKnowledgeSourceRepository;
import com.sellerops.knowledge.org.SellerOperationsKnowledgeService;
import com.sellerops.knowledge.schedule.KnowledgeSchedule;
import com.sellerops.knowledge.schedule.KnowledgeScheduleRepository;
import com.sellerops.knowledge.schedule.KnowledgeScheduleService;
import com.sellerops.knowledge.style.AnswerStyleService;
import com.sellerops.knowledge.style.OrganizationAnswerStyleRepository;
import com.sellerops.organization.Organization;
import com.sellerops.organization.OrganizationRepository;
import com.sellerops.product.ChannelProductRepository;
import com.sellerops.product.Product;
import com.sellerops.product.ProductRepository;
import com.sellerops.product.ProductVariantRepository;
import com.sellerops.product.library.ProductKnowledgeChunkRepository;
import com.sellerops.product.library.ProductKnowledgeIndexer;
import com.sellerops.product.library.ProductKnowledgeLibraryService;
import com.sellerops.product.library.ProductKnowledgeSourceRepository;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
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
 * <b>Schedules through the workbook the seller already keeps</b> (owner decision 4, 2026-11-15): a 「입고·출고 일정」 sheet,
 * read first and applied second like every other sheet, with an old file refused over a newer screen.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class KnowledgeWorkbookScheduleIT {

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
    @Autowired KnowledgeScheduleRepository schedules;

    private KnowledgeWorkbookService workbook;
    private UUID org;

    @BeforeEach
    void setUp() {
        SellerOperationsKnowledgeService orgKnowledge = new SellerOperationsKnowledgeService(orgSources, orgChunks);
        ProductKnowledgeLibraryService library = new ProductKnowledgeLibraryService(products, productSources,
                productChunks, variants);
        KnowledgeCandidateService candidates = new KnowledgeCandidateService(candidateRows, memories, productSources,
                new ProductKnowledgeIndexer(productChunks), products, orgSources, orgKnowledge, variants);
        workbook = new KnowledgeWorkbookService(new AnswerStyleService(styleRows),
                new ReviewReplyTemplateService(templateRows), library, productSources, orgKnowledge, orgSources,
                candidates, candidateRows, products, listings);
        workbook.setSchedules(schedules, new KnowledgeScheduleService(schedules, products));
        Organization o = new Organization();
        o.setName("일정 QA");
        org = organizations.save(o).getId();
        Product p = new Product();
        p.setOrgId(org);
        p.setName("면 침구 세트(퀸)");
        p.setSku("SKU-" + UUID.randomUUID());
        p.setStatus("ACTIVE");
        products.save(p);
    }

    private byte[] edit(java.util.function.Consumer<Sheet> change) throws IOException {
        try (Workbook book = new XSSFWorkbook(new ByteArrayInputStream(workbook.export(org)))) {
            Sheet sheet = book.getSheet(KnowledgeWorkbookService.SHEET_SCHEDULE);
            assertThat(sheet).isNotNull();
            change.accept(sheet);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            book.write(out);
            return out.toByteArray();
        }
    }

    private static void line(Row row, String name, String option, String kind, String status, String date,
                             String scope, String checked, String state) {
        String[] v = {null, "", name, option, kind, status, date, scope, checked, state};
        for (int c = 1; c < v.length; c++) {
            row.getCell(c).setCellValue(v[c]);
        }
    }

    @Test
    @DisplayName("a new line is previewed, then applied; a bad one is refused on its row; an old file cannot overwrite")
    void schedulesRoundTrip() throws IOException {
        byte[] file = edit(sheet -> {
            line(sheet.getRow(1), "면 침구 세트(퀸)", "그레이", "입고", "예정", "2026-11-25", "", "2026-11-10", "사용");
            line(sheet.getRow(2), "(전체 상품)", "", "생산", "확정", "", "", "", "사용");
            line(sheet.getRow(3), "없는 상품", "", "입고", "확정", "2026-11-18", "", "", "사용");
        });
        KnowledgeWorkbookService.Result preview = workbook.read(org, new ByteArrayInputStream(file), false, null, "판매자");
        assertThat(preview.applied()).isFalse();
        assertThat(schedules.findAll()).as("reading changes nothing").isEmpty();
        assertThat(preview.changes()).singleElement().satisfies(l -> {
            assertThat(l.text()).isEqualTo("새 일정 등록");
            assertThat(l.where()).isEqualTo("면 침구 세트(퀸) · 그레이");
            assertThat(l.after()).contains("입고 예정 2026-11-25");
        });
        assertThat(preview.problems()).extracting(KnowledgeWorkbookService.Line::text)
                .anySatisfy(t -> assertThat(t).contains("확정 일정에는 날짜가 있어야 해요"))
                .anySatisfy(t -> assertThat(t).contains("상품을 찾지 못했어요"));

        workbook.read(org, new ByteArrayInputStream(file), true, null, "판매자");
        KnowledgeSchedule saved = schedules.findAll().get(0);
        assertThat(saved.getStatus()).isEqualTo(KnowledgeSchedule.Status.PLANNED);
        assertThat(saved.getScheduleDate()).isEqualTo(LocalDate.of(2026, 11, 25));
        assertThat(saved.getLastCheckedOn()).isEqualTo(LocalDate.of(2026, 11, 10));

        // Downloaded now, then the line changes on the server, then the old file comes back.
        byte[] old = edit(sheet -> sheet.getRow(1).getCell(5).setCellValue("확정"));
        saved.setScheduleDate(LocalDate.of(2026, 11, 27));
        schedules.saveAndFlush(saved);
        KnowledgeWorkbookService.Result stale = workbook.read(org, new ByteArrayInputStream(old), true, null, "판매자");
        assertThat(stale.changes()).isEmpty();
        assertThat(stale.problems()).singleElement().satisfies(l -> assertThat(l.text()).contains("내려받은 뒤"));

        byte[] fresh = edit(sheet -> {
            sheet.getRow(1).getCell(5).setCellValue("확정");
            sheet.getRow(1).getCell(9).setCellValue("사용 안 함");
        });
        KnowledgeWorkbookService.Result done = workbook.read(org, new ByteArrayInputStream(fresh), true, null, "판매자");
        assertThat(done.changes()).singleElement().satisfies(l -> {
            assertThat(l.text()).isEqualTo("일정 고침");
            assertThat(l.before()).contains("예정");
            assertThat(l.after()).contains("확정").contains("사용 안 함");
        });
        KnowledgeSchedule now = schedules.findById(saved.getId()).orElseThrow();
        assertThat(now.getStatus()).isEqualTo(KnowledgeSchedule.Status.CONFIRMED);
        assertThat(now.isActive()).isFalse();
    }
}
