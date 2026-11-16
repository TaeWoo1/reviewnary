package com.sellerops.inquiry.draft;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.inquiry.Inquiry;
import com.sellerops.inquiry.InquiryProductBinding;
import com.sellerops.inquiry.InquiryRepository;
import com.sellerops.knowledge.memory.AnswerMemoryRepository;
import com.sellerops.knowledge.memory.AnswerMemoryService;
import com.sellerops.knowledge.org.OrgKnowledgeChunkRepository;
import com.sellerops.knowledge.org.OrgKnowledgeSourceRepository;
import com.sellerops.knowledge.org.SellerOperationsKnowledgeService;
import com.sellerops.knowledge.spine.KnowledgeSpineService;
import com.sellerops.knowledge.spine.SourceRefResolver;
import com.sellerops.order.fact.OrderFactLookup;
import com.sellerops.organization.Organization;
import com.sellerops.organization.OrganizationRepository;
import com.sellerops.product.Product;
import com.sellerops.product.ProductRepository;
import com.sellerops.product.ProductVariant;
import com.sellerops.product.ProductVariantRepository;
import com.sellerops.product.library.ProductKnowledgeChunkRepository;
import com.sellerops.product.library.ProductKnowledgeLibraryService;
import com.sellerops.product.library.ProductKnowledgeSourceRepository;
import com.sellerops.product.library.dto.KnowledgeSourceRequest;
import com.sellerops.product.library.KnowledgeSourceType;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * <b>The shared-name check costs nothing where it cannot matter, and reads names — not products — where it can</b>
 * (2026-11-16 review B176: every inquiry with a product loaded the org's whole catalogue as entities, before anyone
 * knew whether a seller table would be read at all).
 *
 * <p>Counted through the repository the assessor holds, and through Hibernate's own statistics. The protections of
 * B172 · B174 stay: a twin name in the same org makes the option unresolved; another org's twin, a part and a
 * different bracketed size do not.
 */
@DataJpaTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class SharedNameCheckCostTest {

    static final String TUMBLER = "[합성] 스테인리스 텀블러 500ml";
    static final String TABLE = "[합성] 색상별 재입고 일정\n\n| 상품 | 색상 | 상태 | 재입고 날짜 |\n|---|---|---|---|\n"
            + "| 스테인리스 텀블러 500ml | 민트 | 품절, 재입고 예정 | 2026-11-18 재입고 |\n";

    @Autowired EntityManager em;
    @Autowired OrganizationRepository organizations;
    @Autowired ProductRepository products;
    @Autowired ProductVariantRepository variants;
    @Autowired ProductKnowledgeSourceRepository productSources;
    @Autowired ProductKnowledgeChunkRepository productChunks;
    @Autowired OrgKnowledgeSourceRepository orgSources;
    @Autowired OrgKnowledgeChunkRepository orgChunks;
    @Autowired AnswerMemoryRepository memories;
    @Autowired InquiryRepository inquiries;
    @Autowired com.sellerops.order.ChannelOrderRepository channelOrders;
    @Autowired com.sellerops.channel.ChannelRepository channels;

    private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    private UUID org;
    private UUID tumbler;
    private ProductKnowledgeLibraryService library;
    private InquiryKnowledgeAssessor assessor;

    @BeforeEach
    void seed() {
        org = org("이름 확인 QA");
        tumbler = product(org, TUMBLER);
        product(org, "스테인리스 텀블러 500ml 전용 뚜껑");
        product(org, "[350ml] 스테인리스 텀블러");
        library = new ProductKnowledgeLibraryService(products, productSources, productChunks, variants);
        library.create(org, tumbler, new KnowledgeSourceRequest(KnowledgeSourceType.DESCRIPTION, "입고 일정", TABLE, null),
                UUID.randomUUID(), "판매자");
        library.create(org, tumbler, new KnowledgeSourceRequest(KnowledgeSourceType.POLICY, "배송",
                "주문 후 1~2일 안에 출고합니다. 제주·도서산간은 하루 더 걸립니다.", null), UUID.randomUUID(), "판매자");
        ProductRepository counted = counting(products);
        InquiryEvidenceRetriever retriever = new InquiryEvidenceRetriever(products, library,
                new SellerOperationsKnowledgeService(orgSources, orgChunks),
                new AnswerMemoryService(memories, orgChunks, productChunks),
                com.sellerops.order.fact.StoredOnlyOrderFacts.reader(channelOrders, channels,
                        (o, c, a, r) -> com.sellerops.coverage.ChannelDataState.OBSERVED_FRESH));
        KnowledgeSpineService spine = new KnowledgeSpineService(List.of(new com.sellerops.knowledge.spine.adapter.SellerKnowledgeAdapter(orgSources, orgChunks,
                productSources, productChunks)), products, new SourceRefResolver(em), retriever);
        assessor = new InquiryKnowledgeAssessor(retriever, spine, variants, counted);
        em.flush();
        em.clear();
    }

    @Test
    @DisplayName("not needed — no option asked, or a channel option list decides: 0 name reads, 0 shared-name reads")
    void theUnneededPathsReadNothing() {
        assess(tumbler, "배송은 언제 출발하나요?");
        assertThat(count("findOtherNamesByOrgId")).isZero();
        assertThat(count("findAllByOrgId")).isZero();
        assertThat(count("findById")).as("at most this product's own name, for the gap wording: one key read")
                .isLessThanOrEqualTo(1);

        ProductVariant black = new ProductVariant();
        black.setOrgId(org);
        black.setProductId(tumbler);
        black.setOptionName("색상: 블랙");
        black.setSource("NAVER:PRODUCT_API:v1");
        black.setObservedAt(Instant.parse("2026-11-15T00:00:00Z"));
        variants.save(black);
        calls.clear();
        assess(tumbler, "민트 색상 재입고 언제 되나요?");
        assertThat(count("findOtherNamesByOrgId")).as("a registered option list is the authority").isZero();
        assertThat(count("findAllByOrgId")).isZero();
    }

    @Test
    @DisplayName("needed — the seller's table names the option: one name read, one shared-name read, no entity of the catalogue")
    void theNeededPathReadsNamesOnce() {
        Statistics stats = stats();
        stats.clear();
        InquiryKnowledgeAssessor.Assessment a = assess(tumbler, "민트 색상 재입고 언제 되나요?");
        assertThat(a.verdict().applicability()).isEqualTo(SpecApplicability.Applicability.VARIANT_NAMED);
        assertThat(count("findOtherNamesByOrgId")).isEqualTo(1);
        assertThat(count("findAllByOrgId")).isZero();
        assertThat(count("findById")).as("this product's name, read once and shared").isEqualTo(1);
    }

    @Test
    @DisplayName("kept: a twin in this org leaves it unresolved; another org's twin, a part and another bracketed size do not")
    void theBoundariesStay() {
        UUID other = org("다른 판매자");
        product(other, "스테인리스 텀블러 500ml");
        em.flush();
        em.clear();
        assertThat(assess(tumbler, "민트 색상 재입고 언제 되나요?").verdict().applicability())
                .as("another org's product is not this seller's twin").isEqualTo(SpecApplicability.Applicability.VARIANT_NAMED);
        product(org, "[합성][데모] 스테인리스  텀블러 500ml");
        em.flush();
        em.clear();
        assertThat(assess(tumbler, "민트 색상 재입고 언제 되나요?").verdict().applicability())
                .as("a twin in this org").isEqualTo(SpecApplicability.Applicability.VARIANT_UNRESOLVED);
    }

    @Test
    @DisplayName("a failed read is not 「unique」")
    void aFailedReadIsNotUnique() {
        ProductRepository broken = (ProductRepository) Proxy.newProxyInstance(ProductRepository.class.getClassLoader(),
                new Class<?>[] {ProductRepository.class}, (proxy, method, args) -> {
                    if (method.getName().equals("findOtherNamesByOrgId")) {
                        throw new org.springframework.dao.QueryTimeoutException("timeout");
                    }
                    try {
                        return method.invoke(products, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        InquiryKnowledgeAssessor a = new InquiryKnowledgeAssessor(null, null, variants, broken);
        assertThat(a.noOtherProductNamed(org, tumbler, TUMBLER)).isFalse();
        assertThat(a.unambiguousName(org, tumbler)).isNull();
        assertThat(assessor.unambiguousName(org, tumbler)).isEqualTo(TUMBLER);
        assertThat(assessor.unambiguousName(UUID.randomUUID(), tumbler)).as("another org's id resolves no name").isNull();
    }

    /**
     * Volume and latency on a large catalogue: the entity load this replaced against the name projection, on the same
     * in-memory H2 the suite runs on (a Postgres over a network costs more per row; the ratio is the point, not the
     * milliseconds). Sizes come from {@code SELLEROPS_NAME_CHECK_SIZES} (default 2000); with
     * {@code SELLEROPS_NAME_CHECK_OUT} set the table is written there.
     */
    @Test
    @DisplayName("on a large catalogue: names only, no entity loaded, one statement")
    void onALargeCatalogue() throws IOException {
        String sizes = System.getenv().getOrDefault("SELLEROPS_NAME_CHECK_SIZES", "2000");
        StringBuilder out = new StringBuilder("size\tway\tstatements\tentities\trows\tmedian_ms\n");
        int seeded = 3;
        for (String s : sizes.split(",")) {
            int size = Integer.parseInt(s.strip());
            seeded = fill(seeded, size);
            Statistics stats = stats();
            for (String way : List.of("entities(before)", "names(now)")) {
                long[] ms = new long[5];
                long statements = 0;
                long entities = 0;
                int rows = 0;
                for (int i = 0; i < ms.length + 1; i++) {
                    em.clear();
                    stats.clear();
                    long t0 = System.nanoTime();
                    if (way.startsWith("entities")) {
                        String key = SpecApplicability.sameProductKey(TUMBLER);
                        List<Product> all = products.findAllByOrgId(org);
                        rows = all.size();
                        all.stream().filter(p -> !tumbler.equals(p.getId()))
                                .map(com.sellerops.product.OperatorProductName::displayNameOrNull)
                                .noneMatch(n -> n != null && key.equals(SpecApplicability.sameProductKey(n)));
                    } else {
                        assessor.noOtherProductNamed(org, tumbler, TUMBLER);
                        rows = size - 1;
                    }
                    long t = System.nanoTime() - t0;
                    if (i > 0) {
                        ms[i - 1] = t;
                    }
                    statements = stats.getPrepareStatementCount();
                    entities = stats.getEntityLoadCount();
                }
                Arrays.sort(ms);
                out.append(size).append('\t').append(way).append('\t').append(statements).append('\t').append(entities)
                        .append('\t').append(rows).append('\t').append(String.format("%.1f", ms[2] / 1e6)).append('\n');
                if (way.startsWith("names")) {
                    assertThat(entities).as("no Product entity is built").isZero();
                    assertThat(statements).as("one statement").isEqualTo(1);
                } else {
                    assertThat(entities).isEqualTo(size);
                    assertThat(statements).isEqualTo(1);
                }
            }
        }
        String target = System.getenv("SELLEROPS_NAME_CHECK_OUT");
        if (target != null && !target.isBlank()) {
            Files.writeString(Path.of(target), out.toString(), StandardCharsets.UTF_8);
        }
        System.out.print(out);
    }

    private int fill(int have, int size) {
        List<Product> batch = new ArrayList<>();
        for (int i = have; i < size; i++) {
            Product p = new Product();
            p.setOrgId(org);
            p.setName("합성 상품 " + i + " 면 " + (i % 7) + "호");
            p.setSku("SKU-" + i);
            p.setStatus("ACTIVE");
            batch.add(p);
            if (batch.size() == 500) {
                products.saveAll(batch);
                em.flush();
                em.clear();
                batch.clear();
            }
        }
        products.saveAll(batch);
        em.flush();
        em.clear();
        return Math.max(have, size);
    }

    private InquiryKnowledgeAssessor.Assessment assess(UUID productId, String body) {
        Inquiry q = new Inquiry();
        q.setOrgId(org);
        q.setChannelId(channels.findByCode("NAVER").map(com.sellerops.channel.Channel::getId).orElseGet(() -> {
            com.sellerops.channel.Channel c = new com.sellerops.channel.Channel();
            c.setCode("NAVER");
            c.setNameKo("네이버");
            c.setStatus(com.sellerops.channel.ChannelStatus.CONNECTED);
            return channels.save(c).getId();
        }));
        q.setTitle("문의");
        q.setBody(body);
        q.setStatus("UNANSWERED");
        q.setProductId(productId);
        q.setProductBinding(InquiryProductBinding.SOURCE_EXACT.name());
        q.setReceivedAt(Instant.parse("2026-11-16T00:00:00Z"));
        Inquiry saved = inquiries.save(q);
        calls.clear();
        return assessor.assess(org, saved, OrderFactLookup.STORED_ONLY);
    }

    private int count(String method) {
        AtomicInteger n = calls.get(method);
        return n == null ? 0 : n.get();
    }

    private Statistics stats() {
        return em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
    }

    private ProductRepository counting(ProductRepository real) {
        return (ProductRepository) Proxy.newProxyInstance(ProductRepository.class.getClassLoader(),
                new Class<?>[] {ProductRepository.class}, (proxy, method, args) -> {
                    calls.computeIfAbsent(method.getName(), k -> new AtomicInteger()).incrementAndGet();
                    try {
                        return method.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private UUID org(String name) {
        Organization o = new Organization();
        o.setName(name);
        return organizations.save(o).getId();
    }

    private UUID product(UUID orgId, String name) {
        Product p = new Product();
        p.setOrgId(orgId);
        p.setName(name);
        p.setStatus("ACTIVE");
        return products.save(p).getId();
    }
}
