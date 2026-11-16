package com.sellerops.inquiry.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sellerops.knowledge.schedule.KnowledgeSchedule;
import com.sellerops.knowledge.schedule.KnowledgeScheduleRepository;
import com.sellerops.product.ProductVariantRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>An option the seller's own schedule names is an option of the product</b> (2026-11-15 final eval q03: 「민트 색상 언제
 * 다시 들어오나요?」 on a listing with no channel variant rows was asked back which product · 규격, though the schedule has a
 * 민트 row for exactly this product). A customer who names no option is still unresolved — the protection stays.
 */
class ScheduledOptionTest {

    private final UUID org = UUID.randomUUID();
    private final UUID tumbler = UUID.randomUUID();
    private final UUID bedding = UUID.randomUUID();

    private static KnowledgeSchedule row(UUID org, UUID product, String option, boolean active) {
        KnowledgeSchedule s = new KnowledgeSchedule();
        s.setId(UUID.randomUUID());
        s.setOrgId(org);
        s.setProductId(product);
        s.setOptionName(option);
        s.setActive(active);
        return s;
    }

    private InquiryKnowledgeAssessor assessor(List<KnowledgeSchedule> rows) {
        ProductVariantRepository variants = mock(ProductVariantRepository.class);
        when(variants.findByOrgIdAndProductId(org, tumbler)).thenReturn(List.of());
        KnowledgeScheduleRepository schedules = mock(KnowledgeScheduleRepository.class);
        when(schedules.findAllByOrgIdOrderByCreatedAtAsc(org)).thenReturn(rows);
        InquiryKnowledgeAssessor a = InquiryKnowledgeAssessor.withoutContext(mock(InquiryEvidenceRetriever.class), variants);
        a.setSchedules(schedules);
        return a;
    }

    @Test
    @DisplayName("the customer names the schedule's option: named, not asked back")
    void aScheduledOptionIsNamed() {
        InquiryKnowledgeAssessor a = assessor(List.of(row(org, tumbler, "민트", true), row(org, bedding, "그레이", true)));
        List<SpecApplicability.Option> options = a.optionsFor(org, tumbler);
        assertThat(options).extracting(SpecApplicability.Option::name).containsExactly("민트");
        assertThat(SpecApplicability.classify(null, "민트 색상 언제 다시 들어오나요?", options).applicability())
                .isEqualTo(SpecApplicability.Applicability.VARIANT_NAMED);
    }

    @Test
    @DisplayName("kept: a customer who names no option is unresolved; another product's, a retired and a whole-product row name none")
    void theProtectionStays() {
        InquiryKnowledgeAssessor a = assessor(List.of(row(org, tumbler, "민트", true), row(org, bedding, "그레이", true),
                row(org, tumbler, "블랙", false), row(org, tumbler, "전체", true)));
        List<SpecApplicability.Option> options = a.optionsFor(org, tumbler);
        assertThat(options).extracting(SpecApplicability.Option::name).containsExactly("민트");
        assertThat(SpecApplicability.classify(null, "이 색상 언제 다시 들어오나요?", options).applicability())
                .isEqualTo(SpecApplicability.Applicability.VARIANT_UNRESOLVED);
        assertThat(SpecApplicability.classify(null, "그레이 색상 언제 들어와요?", options).applicability())
                .as("another product's option is not this one's").isEqualTo(SpecApplicability.Applicability.VARIANT_UNRESOLVED);
        assertThat(SpecApplicability.classify(null, "블랙 색상 언제 들어와요?", options).applicability())
                .as("a retired row names nothing").isEqualTo(SpecApplicability.Applicability.VARIANT_UNRESOLVED);
        assertThat(assessor(List.of()).optionsFor(org, tumbler)).as("no schedule: as before").isEmpty();
    }

    private static final String TABLE = "[합성] 생산·입고·출고 일정 (작성일 2026-11-10)\n\n"
            + "| 상품 | 옵션 | 상태 | 날짜 | 구분 |\n|---|---|---|---|---|\n"
            + "| 면 침구 세트(퀸) | 그레이 | 품절, 재입고 대기 | 2026-11-25 입고 | 예정(공장 회신 전, 바뀔 수 있음) |\n"
            + "| 스테인리스 텀블러 500ml | 민트 | 품절, 재입고 | 2026-11-18 입고, 2026-11-19부터 출고 | 확정 |\n"
            + "| 원목 접이식 테이블 | 전체 | 생산 대기 | 11월 둘째 주 주문분 생산 시작 2026-11-17 | 예정 |\n";

    private static SpecApplicability.Verdict read(String question, String product, List<SpecApplicability.Option> options,
                                                  List<String> passages) {
        return SpecApplicability.namedInSellerTable(SpecApplicability.classify(null, question, options), null, question,
                product, passages);
    }

    @Test
    @DisplayName("q03: no channel option list, and the seller's table names this product with the option the customer wrote")
    void theSellersTableNamesTheOption() {
        assertThat(read("민트 색상 언제 다시 들어오나요?", "[합성] 스테인리스 텀블러 500ml", List.of(), List.of(TABLE)).applicability())
                .isEqualTo(SpecApplicability.Applicability.VARIANT_NAMED);
    }

    @Test
    @DisplayName("kept: no option written, another product's row, a whole-product row, prose, or a channel list — still unresolved")
    void theTableIsReadNarrowly() {
        String tumbler = "[합성] 스테인리스 텀블러 500ml";
        assertThat(read("이 색상 언제 다시 들어오나요?", tumbler, List.of(), List.of(TABLE)).applicability())
                .as("the customer wrote no option").isEqualTo(SpecApplicability.Applicability.VARIANT_UNRESOLVED);
        assertThat(read("그레이 색상 언제 들어와요?", tumbler, List.of(), List.of(TABLE)).applicability())
                .as("그레이 is the bedding's row").isEqualTo(SpecApplicability.Applicability.VARIANT_UNRESOLVED);
        assertThat(read("전체 색상 다 품절인가요?", "원목 접이식 테이블", List.of(), List.of(TABLE)).applicability())
                .as("「전체」 names no option").isEqualTo(SpecApplicability.Applicability.VARIANT_UNRESOLVED);
        assertThat(read("민트 색상 언제 다시 들어오나요?", tumbler, List.of(),
                List.of("민트 색상은 11월 18일 입고 확정입니다.")).applicability())
                .as("prose is not read").isEqualTo(SpecApplicability.Applicability.VARIANT_UNRESOLVED);
        assertThat(read("민트 색상 언제 다시 들어오나요?", tumbler,
                List.of(new SpecApplicability.Option(UUID.randomUUID(), "블랙"), new SpecApplicability.Option(UUID.randomUUID(), "화이트")),
                List.of(TABLE)).applicability())
                .as("a registered channel list is the authority").isEqualTo(SpecApplicability.Applicability.VARIANT_UNRESOLVED);
    }

    @Test
    @DisplayName("B172: a part, a set or another size of the product is another product — its row names no option of this one")
    void anotherProductsRowNamesNothing() {
        String tumbler = "[합성] 스테인리스 텀블러 500ml";
        for (String row : List.of("스테인리스 텀블러 500ml 전용 뚜껑", "스테인리스 텀블러 500ml 2개 세트", "스테인리스 텀블러 350ml",
                "스테인리스 텀블러")) {
            String table = "| 상품 | 옵션 | 상태 |\n|---|---|---|\n| " + row + " | 민트 | 품절, 재입고 |\n";
            assertThat(read("민트 색상 언제 다시 들어오나요?", tumbler, List.of(), List.of(table)).applicability()).as(row)
                    .isEqualTo(SpecApplicability.Applicability.VARIANT_UNRESOLVED);
        }
        assertThat(SpecApplicability.sameProductKey("[합성] 스테인리스 텀블러 500ml"))
                .isEqualTo(SpecApplicability.sameProductKey("스테인리스  텀블러 500ml")).as("only a leading tag and spacing go");
        assertThat(SpecApplicability.sameProductKey("면 침구 세트(퀸)"))
                .isNotEqualTo(SpecApplicability.sameProductKey("면 침구 세트(싱글)"));
    }

    @Test
    @DisplayName("B174: a bracket that carries a size, an option or a set keeps it — only a synthetic or demo marker goes")
    void aBracketThatIdentifiesStays() {
        for (String[] pair : new String[][] {
                {"[500ml] 스테인리스 텀블러", "[350ml] 스테인리스 텀블러"},
                {"[민트] 스테인리스 텀블러", "[블랙] 스테인리스 텀블러"},
                {"[2개 세트] 스테인리스 텀블러", "스테인리스 텀블러"},
                {"[합성][500ml] 스테인리스 텀블러", "[합성][350ml] 스테인리스 텀블러"}}) {
            assertThat(SpecApplicability.sameProductKey(pair[0])).as(pair[0] + " vs " + pair[1])
                    .isNotEqualTo(SpecApplicability.sameProductKey(pair[1]));
            String table = "| 상품 | 옵션 | 상태 |\n|---|---|---|\n| " + pair[1] + " | 민트 | 품절, 재입고 |\n";
            assertThat(read("민트 색상 언제 다시 들어오나요?", pair[0], List.of(), List.of(table)).applicability())
                    .as("the other product's row names nothing: " + pair[1])
                    .isEqualTo(SpecApplicability.Applicability.VARIANT_UNRESOLVED);
        }
        assertThat(SpecApplicability.sameProductKey("[500ml] 스테인리스 텀블러"))
                .isEqualTo(SpecApplicability.sameProductKey("[500ml]스테인리스 텀블러")).as("spacing still goes");
        // the markers SellerOps writes are not part of a product's name
        assertThat(SpecApplicability.sameProductKey("[합성] 스테인리스 텀블러 500ml"))
                .isEqualTo(SpecApplicability.sameProductKey("스테인리스 텀블러 500ml"));
        assertThat(SpecApplicability.sameProductKey("[데모][합성] 스테인리스 텀블러 500ml"))
                .isEqualTo(SpecApplicability.sameProductKey("스테인리스 텀블러 500ml"));
        assertThat(SpecApplicability.sameProductKey("[합성][500ml] 스테인리스 텀블러"))
                .isEqualTo(SpecApplicability.sameProductKey("[500ml] 스테인리스 텀블러")).as("the marker goes, the size stays");
        // an unlisted tag is not guessed to be decoration: the names differ, so the option is not confirmed
        assertThat(SpecApplicability.sameProductKey("[무료배송] 스테인리스 텀블러 500ml"))
                .isNotEqualTo(SpecApplicability.sameProductKey("스테인리스 텀블러 500ml"));
        String same = "| 상품 | 옵션 | 상태 |\n|---|---|---|\n| [500ml] 스테인리스 텀블러 | 민트 | 품절, 재입고 |\n";
        assertThat(read("민트 색상 언제 다시 들어오나요?", "[합성][500ml] 스테인리스 텀블러", List.of(), List.of(same)).applicability())
                .as("the same product, with its size, still reads its row").isEqualTo(SpecApplicability.Applicability.VARIANT_NAMED);
    }

    @Test
    @DisplayName("B174: twins only when the size in the bracket matches too")
    void bracketedSizesAreNotTwins() {
        com.sellerops.product.ProductRepository products = mock(com.sellerops.product.ProductRepository.class);
        com.sellerops.product.Product mine = product(tumbler, "[500ml] 스테인리스 텀블러");
        com.sellerops.product.Product other = product(UUID.randomUUID(), "[350ml] 스테인리스 텀블러");
        when(products.findById(tumbler)).thenReturn(java.util.Optional.of(mine));
        when(products.findOtherNamesByOrgId(org, tumbler)).thenReturn(names(other));
        InquiryKnowledgeAssessor a = new InquiryKnowledgeAssessor(mock(InquiryEvidenceRetriever.class),
                mock(com.sellerops.knowledge.spine.KnowledgeSpineService.class), mock(ProductVariantRepository.class), products);
        assertThat(a.unambiguousName(org, tumbler)).isEqualTo("[500ml] 스테인리스 텀블러");
        when(products.findOtherNamesByOrgId(org, tumbler)).thenReturn(names(other, product(UUID.randomUUID(), "[합성][500ml] 스테인리스 텀블러")));
        assertThat(a.unambiguousName(org, tumbler)).as("the same size behind a marker is a twin").isNull();
    }

    @Test
    @DisplayName("B172: two products sharing a name identify neither — the option stays unresolved")
    void aSharedNameIsAmbiguous() {
        com.sellerops.product.ProductRepository products = mock(com.sellerops.product.ProductRepository.class);
        com.sellerops.product.Product mine = product(tumbler, "[합성] 스테인리스 텀블러 500ml");
        com.sellerops.product.Product twin = product(UUID.randomUUID(), "스테인리스 텀블러 500ml");
        com.sellerops.product.Product part = product(UUID.randomUUID(), "스테인리스 텀블러 500ml 전용 뚜껑");
        when(products.findById(tumbler)).thenReturn(java.util.Optional.of(mine));
        InquiryKnowledgeAssessor a = new InquiryKnowledgeAssessor(mock(InquiryEvidenceRetriever.class),
                mock(com.sellerops.knowledge.spine.KnowledgeSpineService.class), mock(ProductVariantRepository.class), products);
        when(products.findOtherNamesByOrgId(org, tumbler)).thenReturn(names(part));
        assertThat(a.unambiguousName(org, tumbler)).as("a part does not share the name").isEqualTo("[합성] 스테인리스 텀블러 500ml");
        when(products.findOtherNamesByOrgId(org, tumbler)).thenReturn(names(twin, part));
        assertThat(a.unambiguousName(org, tumbler)).as("a twin does").isNull();
    }

    @Test
    @DisplayName("B172: the channel's option list is the authority — a structured schedule's names never join one")
    void theChannelListComesFirst() {
        ProductVariantRepository variants = mock(ProductVariantRepository.class);
        com.sellerops.product.ProductVariant black = new com.sellerops.product.ProductVariant();
        black.setId(UUID.randomUUID());
        black.setOptionName("색상: 블랙");
        when(variants.findByOrgIdAndProductId(org, tumbler)).thenReturn(List.of(black));
        KnowledgeScheduleRepository schedules = mock(KnowledgeScheduleRepository.class);
        when(schedules.findAllByOrgIdOrderByCreatedAtAsc(org)).thenReturn(List.of(row(org, tumbler, "민트", true)));
        InquiryKnowledgeAssessor a = InquiryKnowledgeAssessor.withoutContext(mock(InquiryEvidenceRetriever.class), variants);
        a.setSchedules(schedules);
        List<SpecApplicability.Option> options = a.optionsFor(org, tumbler);
        assertThat(options).extracting(SpecApplicability.Option::name).containsExactly("색상: 블랙");
        assertThat(SpecApplicability.classify(null, "민트 색상 언제 다시 들어오나요?", options).applicability())
                .isEqualTo(SpecApplicability.Applicability.VARIANT_UNRESOLVED);
    }

    private static List<com.sellerops.product.ProductNameRow> names(com.sellerops.product.Product... ps) {
        return java.util.Arrays.stream(ps).map(p -> new com.sellerops.product.ProductNameRow(p.getName(), p.getSku()))
                .toList();
    }

    @Test
    @DisplayName("B176: the name and the shared-name check are asked only when the table can decide")
    void askedOnlyWhenItCanMatter() {
        java.util.concurrent.atomic.AtomicInteger nameReads = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger twinReads = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.Supplier<String> name = () -> {
            nameReads.incrementAndGet();
            return "[합성] 스테인리스 텀블러 500ml";
        };
        java.util.function.BooleanSupplier unique = () -> {
            twinReads.incrementAndGet();
            return true;
        };
        String q = "민트 색상 언제 다시 들어오나요?";
        SpecApplicability.Verdict none = SpecApplicability.classify(null, q, List.of());
        SpecApplicability.Verdict listed = SpecApplicability.classify(null, q,
                List.of(new SpecApplicability.Option(UUID.randomUUID(), "블랙")));
        SpecApplicability.Verdict general = SpecApplicability.classify(null, "배송 언제 출발하나요?", List.of());
        String other = "| 상품 | 옵션 |\n|---|---|\n| 면 침구 세트(퀸) | 민트 |\n";
        // nothing for the table to decide: neither is read
        SpecApplicability.namedInSellerTable(general, null, "배송 언제 출발하나요?", name, unique, List.of(TABLE));
        SpecApplicability.namedInSellerTable(listed, null, q, name, unique, List.of(TABLE));
        SpecApplicability.namedInSellerTable(none, null, q, name, unique, List.of("민트는 18일 입고 확정입니다."));
        assertThat(nameReads.get()).isZero();
        assertThat(twinReads.get()).isZero();
        // a table, but no row of this product: the name is read, the shared-name check is not
        assertThat(SpecApplicability.namedInSellerTable(none, null, q, name, unique, List.of(other)).applicability())
                .isEqualTo(SpecApplicability.Applicability.VARIANT_UNRESOLVED);
        assertThat(nameReads.get()).isEqualTo(1);
        assertThat(twinReads.get()).isZero();
        // the row would name the option: asked once; a name that is not unique names nothing
        assertThat(SpecApplicability.namedInSellerTable(none, null, q, name, unique, List.of(TABLE, TABLE)).applicability())
                .isEqualTo(SpecApplicability.Applicability.VARIANT_NAMED);
        assertThat(twinReads.get()).isEqualTo(1);
        assertThat(SpecApplicability.namedInSellerTable(none, null, q, name, () -> false, List.of(TABLE)).applicability())
                .isEqualTo(SpecApplicability.Applicability.VARIANT_UNRESOLVED);
    }

    private com.sellerops.product.Product product(UUID id, String name) {
        com.sellerops.product.Product p = new com.sellerops.product.Product();
        p.setId(id);
        p.setOrgId(org);
        p.setName(name);
        return p;
    }
}
