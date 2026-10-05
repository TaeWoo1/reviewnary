package com.sellerops.product;

import com.sellerops.inquiry.InquiryRepository;
import com.sellerops.product.dto.ProductCatalogView;
import com.sellerops.product.dto.ProductSummaryView;
import com.sellerops.review.ReviewRepository;
import com.sellerops.reviewissue.ReviewIssueEvidenceRepository;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The 상품 screen's page: the products carrying operational weight first, and the org's real total.
 *
 * <p><b>Why this is not {@link ProductQueryService#search}.</b> That one resolves a seller's own
 * words — 「A상품」, part of a name, a SKU — and its empty-query head is alphabetical and capped at ten.
 * That is the right answer to 「이 이름의 상품이 있나」 and the wrong one to 「지금 무엇을 봐야 하나」,
 * and the 상품 screen was asking it the second question. On 2026-09-04 the demo org held 308 products
 * and the screen showed ten by name — six of them with no inquiry and no review at all — while the
 * product carrying 1,761 reviews was not on the page, under a heading that read 「상품 10개」.
 * {@code search} is untouched; the screen simply stops asking it a question it was not built for.
 *
 * <p><b>Weight is what the seller owes, then what customers complained about: 답변 대기 → 문제 근거 →
 * 리뷰, then name so the tail is stable.</b> This is the canonical ordering, and it is canonical
 * because it is the ONLY one — the 상품 screen renders what this returns, in this order, and no longer
 * re-sorts it. It used to, by its own rule, and the two rules disagreed on the second key: this one
 * broke ties on NEGATIVE REVIEW COUNT while the screen broke them on 문제 근거. So which twenty
 * products reached the page was decided by one quantity and the order they stood in by another, and
 * neither was the number the row displayed. A seller comparing the 문제 근거 column down the page was
 * reading a column that did not explain its own ordering, and a product with heavy evidence but few
 * negative reviews could be kept off the page entirely by a key nothing on screen showed.
 *
 * <p><b>The keys are the quantities the row prints.</b> Every one of the three is a column on the 상품
 * list and a relation on 상품 상세, counted here exactly as those screens count it — in particular
 * 문제 근거 drops dismissed issues, because that is what the column shows
 * ({@code ReviewIssueEvidenceRepository.activeEvidenceCountsByProduct}). An ordering key that cannot
 * be read off the screen it orders is not a ranking, it is a secret.
 *
 * <p><b>Ingest's {@code (미지정 상품)} bucket sorts last whatever it holds.</b> It is an artifact of
 * unattributed rows rather than a product ({@link OperatorProductName#UNSPECIFIED_PRODUCT_NAME}), and
 * the screen used to push it down itself — the one piece of its own ordering that was not a tie-break.
 * It moves here with the rest, so the rule survives the screen giving up sorting.
 *
 * <p><b>Four reads, never one per row.</b> Three grouped counts and the catalogue, then the ranking in
 * memory. Asking the per-product signals endpoint instead would be 308 requests to choose twenty.
 *
 * <p><b>Two different synthetic rules, on purpose.</b> WHICH products exist follows the auto-enabled
 * {@code realDataOnly} filter, so the page and {@code countByOrgId} agree in every deployment. WHAT
 * ranks them is REAL only and says so in the queries: ranking a catalogue by manufactured complaints
 * is how a demo screen came to name an invented product as the shop's worst.
 */
@Service
public class ProductCatalogService {

    /** The screen reads facts for every row it shows, so the page it shows is bounded. */
    public static final int CATALOG_PAGE = 20;

    private final ProductRepository products;
    private final InquiryRepository inquiries;
    private final ReviewRepository reviews;
    private final ReviewIssueEvidenceRepository evidence;

    public ProductCatalogService(ProductRepository products, InquiryRepository inquiries,
                                 ReviewRepository reviews, ReviewIssueEvidenceRepository evidence) {
        this.products = products;
        this.inquiries = inquiries;
        this.reviews = reviews;
        this.evidence = evidence;
    }

    @Transactional(readOnly = true)
    public ProductCatalogView catalog(UUID orgId, int limit) {
        int cap = Math.min(Math.max(limit <= 0 ? CATALOG_PAGE : limit, 1), CATALOG_PAGE);
        List<Product> all = products.findAllByOrgId(orgId);

        Map<UUID, Long> unanswered = new HashMap<>();
        for (Object[] row : inquiries.countUnansweredOperationalByProduct(orgId)) {
            unanswered.put((UUID) row[0], ((Number) row[1]).longValue());
        }
        Map<UUID, Long> reviewCount = new HashMap<>();
        for (Object[] row : reviews.countOperationalByProduct(orgId)) {
            reviewCount.put((UUID) row[0], ((Number) row[1]).longValue());
        }
        Map<UUID, Long> issueEvidence = new HashMap<>();
        for (Object[] row : evidence.activeEvidenceCountsByProduct(orgId)) {
            issueEvidence.put((UUID) row[0], ((Number) row[1]).longValue());
        }

        Comparator<Product> byWeight = Comparator
                .comparingInt((Product p) -> isUnspecifiedBucket(p) ? 1 : 0)
                .thenComparingLong(p -> -unanswered.getOrDefault(p.getId(), 0L))
                .thenComparingLong(p -> -issueEvidence.getOrDefault(p.getId(), 0L))
                .thenComparingLong(p -> -reviewCount.getOrDefault(p.getId(), 0L))
                .thenComparing(Product::getName, Comparator.nullsLast(Comparator.naturalOrder()));

        List<ProductSummaryView> rows = all.stream()
                .sorted(byWeight)
                .limit(cap)
                .map(p -> new ProductSummaryView(p.getId(), p.getName(), p.getSku(), p.getStatus(),
                        ProductMatchSurface.CATALOG_HEAD, null))
                .toList();
        return new ProductCatalogView(all.size(), rows);
    }

    /** Ingest's shared bucket for rows it could not attribute — a row, not a product. */
    private static boolean isUnspecifiedBucket(Product p) {
        return p.getName() != null
                && OperatorProductName.UNSPECIFIED_PRODUCT_NAME.equals(p.getName().trim());
    }
}
