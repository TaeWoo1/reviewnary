package com.sellerops.knowledge.inventory;

import com.sellerops.inquiry.draft.InquiryDraftEvidence;
import com.sellerops.knowledge.inventory.dto.KnowledgeInventoryView;
import com.sellerops.knowledge.org.OrgKnowledgeSource;
import com.sellerops.knowledge.org.OrgKnowledgeSourceRepository;
import com.sellerops.product.Product;
import com.sellerops.product.ProductRepository;
import com.sellerops.product.library.ProductKnowledgeSource;
import com.sellerops.product.library.ProductKnowledgeSourceRepository;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>The knowledge workspace's one read.</b>
 *
 * <p><b>Why one read and not five.</b> The screen asks a single question — what do we know, what is
 * missing, where has it been used — and the three answers come from three corpora plus two evidence
 * tables. A frontend that assembled it would make one request per product to learn a product's
 * name; this assembles it in five flat queries, none of them per row:
 *
 * <ol>
 *   <li>every operating rule this org has written,</li>
 *   <li>every product fact this org has written,</li>
 *   <li>those products' names, by id, in one read,</li>
 *   <li>which of them are part of 「상품 n개」, by id, in one read,</li>
 *   <li>how many products the org has at all,</li>
 *   <li>the inquiry lane's citations, grouped by document and kind,</li>
 *   <li>the review lane's citations, the same way.</li>
 * </ol>
 *
 * <p><b>Seven, and seven whatever the company holds</b> — a seller with four documents and a seller
 * with four hundred cost the same reads. Two of the seven are skipped outright when there is no
 * product knowledge at all, so a company that has written nothing costs five. Counted on the demo
 * org with {@code spring.jpa.show-sql} on, 2026-10-06: eight statements for the request, of which
 * the first is the tenant check every authenticated request makes and the remaining seven are these.
 *
 * <p><b>The ordering is the server's, exactly as the catalogue's is.</b> 상품 목록 was shipped with
 * the server choosing the top twenty by one quantity while the screen re-ranked them by another, so
 * page membership and page order were decided by different numbers and one of them appeared nowhere
 * on screen. The rule that came out of that is carried here: this service orders, the screen renders
 * what it was sent, and {@code KnowledgeInventoryContractTest} and the frontend's own contract test
 * each hold one half of it.
 *
 * <p><b>Why the sort cannot be an {@code order by}.</b> The key is a count that lives in two other
 * tables and is assembled here, so the corpus is read whole and ordered in memory — the same shape,
 * and the same bound, as {@code ProductCatalogService}: the page is cut AFTER the whole corpus is
 * ranked, never before.
 */
@Service
public class KnowledgeInventoryService {

    private final OrgKnowledgeSourceRepository rules;
    private final ProductKnowledgeSourceRepository facts;
    private final ProductRepository products;
    private final KnowledgeUsageReader usage;

    public KnowledgeInventoryService(OrgKnowledgeSourceRepository rules,
                                     ProductKnowledgeSourceRepository facts,
                                     ProductRepository products,
                                     KnowledgeUsageReader usage) {
        this.rules = rules;
        this.facts = facts;
        this.products = products;
        this.usage = usage;
    }

    @Transactional(readOnly = true)
    public KnowledgeInventoryView of(UUID orgId) {
        List<KnowledgeCitation> citations = usage.citations(orgId);
        Map<UUID, KnowledgeUsage> used = new HashMap<>();
        for (KnowledgeCitation c : citations) {
            used.merge(c.sourceId(), new KnowledgeUsage(c.citations(), c.lastUsedAt()), KnowledgeUsage::plus);
        }

        List<OrgKnowledgeSource> written = rules.findAllByOrgIdOrderByCreatedAtAsc(orgId);
        Set<UUID> ruleIds = written.stream().map(OrgKnowledgeSource::getId).collect(Collectors.toSet());
        List<KnowledgeInventoryView.OperatingRule> ruleRows = written.stream()
                .map(r -> rule(r, used.getOrDefault(r.getId(), KnowledgeUsage.NONE)))
                .sorted(BY_TOPIC_THEN_USE)
                .toList();
        // 지금의 어떤 기준과도 맞지 않는 운영 기준 인용. 어떤 기준이었는지는 묻지 않는다 — locator를 읽어
        // 이름을 되찾는 것은 외래 키가 받쳐 주지 않는 주장이고, 화면이 말하는 것은 수뿐이다.
        long orphanRuleCitations = citations.stream()
                .filter(c -> InquiryDraftEvidence.KIND_ORG_POLICY.equals(c.kind()))
                .filter(c -> !ruleIds.contains(c.sourceId()))
                .mapToLong(KnowledgeCitation::citations)
                .sum();

        List<ProductKnowledgeSource> factSources = facts.findAllByOrgId(orgId);
        Set<UUID> productIds = factSources.stream()
                .map(ProductKnowledgeSource::getProductId)
                .collect(Collectors.toSet());

        // 이름과 셈은 서로 다른 질문이고, 그래서 서로 다른 읽기다.
        //
        // 이름은 제조된 상품까지 포함해 가져온다. 판매자가 쓴 지식은 한 상품에 붙어 있고, 그 상품을 부르지
        // 못하는 줄은 읽을 수 없는 줄이다 — 바로 아래 「자료」 목록도 같은 이름을 같은 방식으로 이미 부르고
        // 있으므로, 한 화면이 같은 질문에 두 가지로 답하면 안 된다.
        Map<UUID, String> names = new HashMap<>();
        if (!productIds.isEmpty()) {
            for (Object[] row : products.namesOfAnyOrigin(orgId, productIds)) {
                names.put(UUID.fromString((String) row[0]), (String) row[1]);
            }
        }
        // 셈은 다르다. 분모인 「상품 294개」는 걸름망을 지난 수이므로, 거기 없는 상품이 분자에 들어가면
        // 「294개 가운데 5개」는 아무 뜻도 없는 문장이 된다. 그래서 세는 쪽만 보통의 걸러진 읽기를 쓴다.
        Set<UUID> covered = new HashSet<>();
        if (!productIds.isEmpty()) {
            for (Product p : products.findAllById(productIds)) {
                covered.add(p.getId());
            }
        }
        List<KnowledgeInventoryView.ProductFact> factRows = factSources.stream()
                .map(f -> fact(f, names.get(f.getProductId()), covered.contains(f.getProductId()),
                        used.getOrDefault(f.getId(), KnowledgeUsage.NONE)))
                .sorted(BY_USE)
                .limit(KnowledgeInventoryView.PAGE)
                .toList();

        return new KnowledgeInventoryView(ruleRows, factRows, factSources.size(),
                products.countByOrgId(orgId), covered.size(), orphanRuleCitations);
    }

    /**
     * <b>The canonical order of product knowledge — 답변 근거 → 마지막 사용 → 제목.</b>
     *
     * <p>Most-cited first, because the question the list answers is which of these the company is
     * actually answering from. A tie is broken by the more recent use, and then by title so the tail
     * — every document nobody has cited, which is the other half of the screen's job — is stable
     * rather than reordering itself between two reads of the same data.
     */
    private static final Comparator<KnowledgeInventoryView.ProductFact> BY_USE = Comparator
            .comparingLong((KnowledgeInventoryView.ProductFact f) -> -f.citations())
            .thenComparing(KnowledgeInventoryView.ProductFact::lastUsedAt,
                    Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(KnowledgeInventoryView.ProductFact::title,
                    Comparator.nullsLast(Comparator.naturalOrder()));

    /**
     * Rules are ordered by TOPIC first, and that is not the same decision as the list above.
     *
     * <p>The screen draws one row per topic the product has — including the topics nothing is written
     * for, which is the only way 「세금계산서는 비어 있다」 becomes visible — so the rows have to arrive
     * in the topic's own order for the screen to lay them over that list without re-sorting. Within
     * one topic, the most-cited rule leads, by the same reasoning as the product list.
     */
    private static final Comparator<KnowledgeInventoryView.OperatingRule> BY_TOPIC_THEN_USE = Comparator
            .comparing((KnowledgeInventoryView.OperatingRule r) -> r.knowledgeType() == null ? "" : r.knowledgeType())
            .thenComparingLong(r -> -r.citations())
            .thenComparing(KnowledgeInventoryView.OperatingRule::title,
                    Comparator.nullsLast(Comparator.naturalOrder()));

    private static KnowledgeInventoryView.OperatingRule rule(OrgKnowledgeSource r, KnowledgeUsage u) {
        return new KnowledgeInventoryView.OperatingRule(
                r.getId(),
                r.getKnowledgeType() == null ? null : r.getKnowledgeType().name(),
                r.getTitle(),
                r.getDocumentName(),
                r.isActive(),
                u.citations(),
                u.lastUsedAt());
    }

    private static KnowledgeInventoryView.ProductFact fact(ProductKnowledgeSource f, String productName,
                                                           boolean reachable, KnowledgeUsage u) {
        return new KnowledgeInventoryView.ProductFact(
                f.getId(),
                f.getProductId(),
                productName,
                reachable,
                f.getSourceType() == null ? null : f.getSourceType().name(),
                f.getTitle(),
                f.getDocumentName(),
                f.isActive(),
                u.citations(),
                u.lastUsedAt());
    }
}
