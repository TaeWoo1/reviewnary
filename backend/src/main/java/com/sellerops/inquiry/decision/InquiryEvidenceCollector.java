package com.sellerops.inquiry.decision;

import com.sellerops.channel.ChannelRepository;
import com.sellerops.common.DataOrigin;
import com.sellerops.inquiry.Inquiry;
import com.sellerops.inquiry.draft.InquiryEvidenceRetriever;
import com.sellerops.knowledge.KnowledgeScope;
import com.sellerops.knowledge.RetrievalQuery;
import com.sellerops.knowledge.org.OrgKnowledgeChunk;
import com.sellerops.knowledge.org.OrgKnowledgeChunkRepository;
import com.sellerops.knowledge.org.OrgKnowledgeSource;
import com.sellerops.knowledge.org.OrgKnowledgeSourceRepository;
import com.sellerops.order.fact.OrderFactLookup;
import com.sellerops.product.ChannelProduct;
import com.sellerops.product.ChannelProductRepository;
import com.sellerops.product.FactKeys;
import com.sellerops.product.ProductFact;
import com.sellerops.product.ProductFactRepository;
import com.sellerops.product.ProductVariant;
import com.sellerops.product.ProductVariantRepository;
import com.sellerops.product.catalogue.CatalogueInvestigator;
import com.sellerops.product.library.KnowledgeVariantScope;
import com.sellerops.product.library.ProductKnowledgeChunk;
import com.sellerops.product.library.ProductKnowledgeChunkRepository;
import com.sellerops.product.library.ProductKnowledgeSource;
import com.sellerops.product.library.ProductKnowledgeSourceRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>Everything current this system holds that could answer an inquiry's needs</b> (Inquiry Decision v2) — collected
 * without a model, bounded, and handed to one coverage judge.
 *
 * <p>Relevance is the judge's question, so this class casts wide and ranks nothing semantically:
 * <ol>
 *   <li>the whole-question lanes the assessment already ran (product knowledge, company rules — with F5 when an org
 *       has it on, so both retrieval paths pass the same gate);</li>
 *   <li>the same lanes once per need, with the planner's short search phrase — a message with three questions is three
 *       searches, and a lexical scorer weighing three questions at once finds the one it can;</li>
 *   <li><b>a small library whole</b>: when this listing's notes or this company's rules are only a handful of passages,
 *       all of them — a judge reading twelve passages is cheaper than a retrieval miss;</li>
 *   <li><b>the stored catalogue</b>: this listing's facts, its options with their selling state, its 추가상품 — what
 *       Catalogue Bootstrap wrote and no lane read before — plus what the catalogue investigation found across the
 *       seller's other listings;</li>
 *   <li>the stored order fact, when one was observed.</li>
 * </ol>
 * Past answers go to a separate list: they are precedents the judge may propose for a prefill, never evidence.
 *
 * <p>It starts no acquisition: a NAVER listing whose detail was never read is reported as
 * {@link DetailCapability#NOT_ACQUIRED} and the plan is kept on the decision; nothing here reaches a channel.
 */
@Component
public class InquiryEvidenceCollector {

    /** A library at or under this many passages is read whole. */
    static final int WHOLE_LIBRARY_PASSAGES = 12;
    static final int MAX_CATALOGUE_CHARS = 1500;
    static final int MAX_PRECEDENT_CHARS = 500;

    private final InquiryEvidenceRetriever retriever;
    private final ProductKnowledgeSourceRepository productSources;
    private final ProductKnowledgeChunkRepository productChunks;
    private final OrgKnowledgeSourceRepository orgSources;
    private final OrgKnowledgeChunkRepository orgChunks;
    private final ProductFactRepository facts;
    private final ProductVariantRepository variants;
    private final ChannelProductRepository listings;
    private final ChannelRepository channels;
    private final com.sellerops.knowledge.memory.AnswerMemoryRepository memories;
    private final com.sellerops.inquiry.InquiryRepository inquiries;
    /** Where a past answer's reuse scope is read from — the stored column; an evaluation may substitute annotations. */
    private java.util.function.Function<com.sellerops.knowledge.memory.AnswerMemory,
            com.sellerops.knowledge.memory.AnswerMemoryReuseScope> scopeSource =
            com.sellerops.knowledge.memory.AnswerMemory::getReuseScope;

    public InquiryEvidenceCollector(InquiryEvidenceRetriever retriever,
                                    ProductKnowledgeSourceRepository productSources,
                                    ProductKnowledgeChunkRepository productChunks,
                                    OrgKnowledgeSourceRepository orgSources, OrgKnowledgeChunkRepository orgChunks,
                                    ProductFactRepository facts, ProductVariantRepository variants,
                                    ChannelProductRepository listings, ChannelRepository channels,
                                    com.sellerops.knowledge.memory.AnswerMemoryRepository memories,
                                    com.sellerops.inquiry.InquiryRepository inquiries) {
        this.retriever = retriever;
        this.productSources = productSources;
        this.productChunks = productChunks;
        this.orgSources = orgSources;
        this.orgChunks = orgChunks;
        this.facts = facts;
        this.variants = variants;
        this.listings = listings;
        this.channels = channels;
        this.memories = memories;
        this.inquiries = inquiries;
    }

    private com.sellerops.inquiry.sellercheck.InquirySellerCheckRepository sellerChecks;

    /** What the seller found on the seller centre for an inquiry (2026-11-02). Absent, nothing of the kind is offered. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setSellerChecks(com.sellerops.inquiry.sellercheck.InquirySellerCheckRepository sellerChecks) {
        this.sellerChecks = sellerChecks;
    }

    private com.sellerops.knowledge.currency.KnowledgeCurrencyService currency;
    private com.sellerops.knowledge.schedule.KnowledgeScheduleRepository scheduleRows;
    private com.sellerops.knowledge.schedule.KnowledgeScheduleService scheduleService;

    /**
     * What the seller's material says now, value by value (2026-11-15, conflict steps 2–5). Optional: without it the pool
     * is collected exactly as before — no document added for the asked attribute, no schedule line, no mark.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setCurrency(com.sellerops.knowledge.currency.KnowledgeCurrencyService currency,
                            com.sellerops.knowledge.schedule.KnowledgeScheduleRepository scheduleRows,
                            com.sellerops.knowledge.schedule.KnowledgeScheduleService scheduleService) {
        this.currency = currency;
        this.scheduleRows = scheduleRows;
        this.scheduleService = scheduleService;
    }

    /** Evaluation only: read reuse scopes from human annotations instead of the column (Eval v1's 23). */
    public void setScopeSource(java.util.function.Function<com.sellerops.knowledge.memory.AnswerMemory,
            com.sellerops.knowledge.memory.AnswerMemoryReuseScope> source) {
        this.scopeSource = source == null ? com.sellerops.knowledge.memory.AnswerMemory::getReuseScope : source;
    }

    @Transactional(readOnly = true)
    public InquiryDecisionEngine.Pool collect(UUID orgId, Inquiry inquiry, UUID productId,
                                              InquiryEvidenceRetriever.InquiryEvidence whole,
                                              CatalogueInvestigator.Finding catalogue, KnowledgeVariantScope scope,
                                              List<InquiryNeed> needs) {
        Map<String, EvidenceCandidate> evidence = new LinkedHashMap<>();
        Map<UUID, PrecedentCandidate> precedents = new LinkedHashMap<>();
        // The seller's own record first (2026-11-02 seller-check review): it is the one thing the seller did for THIS
        // inquiry, and the pool is cut at its ceiling from the end.
        addSellerCheck(orgId, inquiry, evidence);
        addLanes(whole, evidence, precedents);
        for (InquiryNeed need : needs) {
            addLanes(retriever.retrieve(orgId, inquiry, RetrievalQuery.ofText(need.search()), OrderFactLookup.STORED_ONLY,
                    scope), evidence, precedents);
        }
        // What the seller's material says now (2026-11-15). Every document that states a value the question asks about is
        // a candidate, whether or not a word of the question matched it — so two documents that disagree on the asked
        // value are both in front of the judge, with the disagreement (q14: 「교환은 받고 며칠 안에」 found 「7일 이내에
        // 교환·반품」 and never 「교환은 수령 후 14일 이내」).
        java.time.LocalDate reference = com.sellerops.knowledge.currency.KnowledgeCurrencyService.dayOf(
                inquiry == null ? null : inquiry.getReceivedAt());
        com.sellerops.knowledge.currency.KnowledgeState state = currency == null ? null
                : currency.stateFor(orgId, productId, reference, orderDateOf(whole));
        if (state != null) {
            addStating(orgId, productId, scope, state, askedBy(inquiry, needs), evidence);
            addSchedules(orgId, productId, inquiry, needs, reference, evidence);
        }
        addWholeLibraries(orgId, productId, scope, evidence);
        addCatalogue(orgId, productId, catalogue, evidence);
        if (whole != null && whole.order() != null && String.valueOf(whole.order().state()).startsWith("OBSERVED")) {
            // The stored fact was read through THIS inquiry's own order binding, so it is attributed to that order.
            put(evidence, new EvidenceCandidate(null, EvidenceCandidate.Kind.ORDER_FACT, "주문 상태",
                    whole.order().messageKo(), null, null, null,
                    EvidenceScope.order(EvidenceScope.orderKey(PrecedentReuse.OrderKey.of(inquiry)))));
        }
        List<EvidenceCandidate> pool = withoutPastSchedules(new ArrayList<>(evidence.values()), inquiry, needs, reference);
        if (state != null) {
            // Each passage carries what its values are now — not settled, replaced, or current. Wide reading stays wide;
            // what it can no longer do is bring back a value the material does not stand behind without saying so.
            pool.replaceAll(c -> marked(state, c));
        }
        return new InquiryDecisionEngine.Pool(List.copyOf(pool), admissible(inquiry, precedents.values()));
    }

    /**
     * A document table that is all history is not ground for the current plan (2026-11-16 §7.57,
     * {@link com.sellerops.knowledge.schedule.ScheduleCurrency}) — the same rule a structured schedule line has always had
     * here ({@link #addSchedules}). Only for a question about the plan: one about what happened before keeps the old
     * table, and nothing that is not a dated schedule table is touched.
     */
    static List<EvidenceCandidate> withoutPastSchedules(List<EvidenceCandidate> pool, Inquiry inquiry,
                                                        List<InquiryNeed> needs, java.time.LocalDate reference) {
        String question = inquiry == null ? "" : (inquiry.getTitle() == null ? "" : inquiry.getTitle()) + " "
                + (inquiry.getBody() == null ? "" : inquiry.getBody());
        boolean plan = !com.sellerops.knowledge.schedule.ScheduleCurrency.asksHistory(question)
                && (com.sellerops.knowledge.schedule.ScheduleCurrency.asksCurrentSchedule(question)
                || (needs == null ? List.<InquiryNeed>of() : needs).stream().anyMatch(n -> n.type() == NeedType.CATALOGUE_AVAILABILITY
                        || com.sellerops.knowledge.schedule.ScheduleCurrency.asksCurrentSchedule(n.ask() + " " + n.search())));
        if (!plan || reference == null) {
            return pool;
        }
        pool.removeIf(c -> (c.kind() == EvidenceCandidate.Kind.ORG_KNOWLEDGE || c.kind() == EvidenceCandidate.Kind.PRODUCT_KNOWLEDGE)
                && com.sellerops.knowledge.schedule.ScheduleCurrency.pastOnly(c.text(), reference));
        return pool;
    }

    /** The attributes of the closed list this inquiry asks about — by the whole message and by each need. */
    public static java.util.Set<com.sellerops.knowledge.claim.ClaimAttribute> askedBy(Inquiry inquiry,
                                                                                    List<InquiryNeed> needs) {
        java.util.Set<com.sellerops.knowledge.claim.ClaimAttribute> asked =
                java.util.EnumSet.noneOf(com.sellerops.knowledge.claim.ClaimAttribute.class);
        if (inquiry != null) {
            asked.addAll(com.sellerops.knowledge.claim.ClaimReader.asked(
                    (inquiry.getTitle() == null ? "" : inquiry.getTitle()) + " "
                            + (inquiry.getBody() == null ? "" : inquiry.getBody())));
        }
        for (InquiryNeed need : needs == null ? List.<InquiryNeed>of() : needs) {
            asked.addAll(com.sellerops.knowledge.claim.ClaimReader.asked(need.ask() + " " + need.search()));
        }
        return asked;
    }

    private static java.time.LocalDate orderDateOf(InquiryEvidenceRetriever.InquiryEvidence whole) {
        return whole == null || whole.order() == null ? null : whole.order().orderedAt();
    }

    private static EvidenceCandidate marked(com.sellerops.knowledge.currency.KnowledgeState state, EvidenceCandidate c) {
        if (c.sourceId() == null || c.kind() != EvidenceCandidate.Kind.ORG_KNOWLEDGE
                && c.kind() != EvidenceCandidate.Kind.PRODUCT_KNOWLEDGE) {
            return c;
        }
        com.sellerops.knowledge.currency.KnowledgeState.Mark mark = state.markFor(c.sourceId(), c.text());
        return mark.isEmpty() ? c : c.withMark(mark);
    }

    /** The passages of every document in use that states a current value of an asked attribute. */
    private void addStating(UUID orgId, UUID productId, KnowledgeVariantScope scope,
                            com.sellerops.knowledge.currency.KnowledgeState state,
                            java.util.Set<com.sellerops.knowledge.claim.ClaimAttribute> asked,
                            Map<String, EvidenceCandidate> evidence) {
        List<com.sellerops.knowledge.currency.KnowledgeState.Source> stating = state.stating(asked);
        if (stating.isEmpty()) {
            return;
        }
        List<OrgKnowledgeChunk> orgChunkRows = null;
        List<ProductKnowledgeChunk> productChunkRows = null;
        for (com.sellerops.knowledge.currency.KnowledgeState.Source s : stating) {
            if ("ORG".equals(s.kind())) {
                if (orgChunkRows == null) {
                    orgChunkRows = orgChunks.findAllByOrgId(orgId).stream()
                            .sorted(Comparator.comparing(OrgKnowledgeChunk::getOrdinal)).toList();
                }
                for (OrgKnowledgeChunk c : orgChunkRows) {
                    if (s.id().equals(c.getSourceId()) && states(c.getContent(), asked)) {
                        put(evidence, new EvidenceCandidate(null, EvidenceCandidate.Kind.ORG_KNOWLEDGE, s.title(),
                                c.getContent(), s.id(), null, null).withChunk(c.getId()));
                    }
                }
            } else if (productId != null && productId.equals(s.productId())) {
                ProductKnowledgeSource row = productSources.findById(s.id()).orElse(null);
                if (row == null || scope != null && !scope.admits(row.getVariantId())) {
                    continue;
                }
                if (productChunkRows == null) {
                    productChunkRows = productChunks.findAllByOrgIdAndProductId(orgId, productId).stream()
                            .sorted(Comparator.comparing(ProductKnowledgeChunk::getOrdinal)).toList();
                }
                for (ProductKnowledgeChunk c : productChunkRows) {
                    if (s.id().equals(c.getSourceId()) && states(c.getContent(), asked)) {
                        put(evidence, new EvidenceCandidate(null, EvidenceCandidate.Kind.PRODUCT_KNOWLEDGE, s.title(),
                                c.getContent(), s.id(), null, null).withChunk(c.getId()));
                    }
                }
            }
        }
    }

    private static boolean states(String text, java.util.Set<com.sellerops.knowledge.claim.ClaimAttribute> asked) {
        return com.sellerops.knowledge.claim.ClaimReader.read(text).stream()
                .anyMatch(k -> !k.historical() && asked.contains(k.attribute()));
    }

    /** Words of a question a product's schedule can answer — one list with the document tables' rule. */
    private static final java.util.regex.Pattern SCHEDULE_WORDS =
            com.sellerops.knowledge.schedule.ScheduleCurrency.SCHEDULE_WORDS;

    /**
     * The product's schedule lines (owner decision 4), for a question about when stock comes in, goes out or is made. A
     * line whose date has passed is history, not the plan, and is not offered; a 예정 line says it can move; none of them
     * is about this customer's order.
     */
    private void addSchedules(UUID orgId, UUID productId, Inquiry inquiry, List<InquiryNeed> needs,
                              java.time.LocalDate reference, Map<String, EvidenceCandidate> evidence) {
        if (scheduleRows == null || productId == null) {
            return;
        }
        String question = (inquiry == null ? "" : (inquiry.getTitle() == null ? "" : inquiry.getTitle()) + " "
                + (inquiry.getBody() == null ? "" : inquiry.getBody()));
        boolean asked = SCHEDULE_WORDS.matcher(question).find() || needs.stream().anyMatch(n ->
                n.type() == NeedType.CATALOGUE_AVAILABILITY || SCHEDULE_WORDS.matcher(n.ask() + " " + n.search()).find());
        if (!asked) {
            return;
        }
        String productName = scheduleService == null ? null : scheduleService.nameOf(orgId, productId);
        for (com.sellerops.knowledge.schedule.KnowledgeSchedule s : scheduleRows.findAllByOrgIdOrderByCreatedAtAsc(orgId)) {
            if (s.getProductId() != null && !s.getProductId().equals(productId)
                    || !com.sellerops.knowledge.schedule.KnowledgeScheduleService.current(s, reference)) {
                continue;
            }
            String label = "입고·출고 일정 · " + (s.getOptionName() == null || s.getOptionName().isBlank() ? ""
                    : s.getOptionName().strip() + " ") + s.getKind().labelKo() + " " + s.getStatus().labelKo()
                    + (s.getScheduleDate() == null ? "" : " " + s.getScheduleDate());
            put(evidence, new EvidenceCandidate(null, EvidenceCandidate.Kind.SCHEDULE, label,
                    com.sellerops.knowledge.schedule.KnowledgeScheduleService.evidenceText(s,
                            s.getProductId() == null ? null : productName, reference),
                    s.getId(), productId, com.sellerops.knowledge.schedule.KnowledgeScheduleService.fingerprint(s)));
        }
    }

    private void addSellerCheck(UUID orgId, Inquiry inquiry, Map<String, EvidenceCandidate> evidence) {
        if (sellerChecks == null || inquiry == null || inquiry.getId() == null) {
            return;
        }
        sellerChecks.findCurrentByInquiry(orgId, inquiry.getId()).stream().findFirst().ifPresent(c ->
                put(evidence, new EvidenceCandidate(null, EvidenceCandidate.Kind.SELLER_CHECK,
                        com.sellerops.inquiry.sellercheck.InquirySellerCheckService.evidenceLabel(c),
                        com.sellerops.inquiry.sellercheck.InquirySellerCheckService.evidenceText(c), c.getId(), null,
                        c.getOutcome(), EvidenceScope.order(EvidenceScope.caseOrderKey(inquiry)))));
    }

    /**
     * Only the past answers whose DECLARED reuse scope admits this Case reach the judge ({@link PrecedentReuse}). The
     * judge is no longer asked to guess whether an answer was about one order: an answer nobody declared general is not
     * offered as one. Without the memory store there is no provenance to read, and nothing is offered.
     */
    public List<PrecedentCandidate> admissible(Inquiry inquiry, java.util.Collection<PrecedentCandidate> offered) {
        if (offered.isEmpty() || memories == null) {
            return List.of();
        }
        Map<UUID, com.sellerops.knowledge.memory.AnswerMemory> rows = new LinkedHashMap<>();
        memories.findAllById(offered.stream().map(PrecedentCandidate::memoryId).toList())
                .forEach(m -> rows.put(m.getId(), m));
        PrecedentReuse.OrderKey current = PrecedentReuse.OrderKey.of(inquiry);
        List<PrecedentCandidate> out = new java.util.ArrayList<>();
        for (PrecedentCandidate p : offered) {
            com.sellerops.knowledge.memory.AnswerMemory m = rows.get(p.memoryId());
            if (m == null) {
                continue;
            }
            com.sellerops.knowledge.memory.AnswerMemoryReuseScope scope = scopeSource.apply(m);
            PrecedentReuse.OrderKey origin = scope == com.sellerops.knowledge.memory.AnswerMemoryReuseScope.ORDER_ONLY
                    && current != null && inquiries != null && m.getOriginInquiryId() != null
                    ? inquiries.findById(m.getOriginInquiryId()).map(PrecedentReuse.OrderKey::of).orElse(null) : null;
            if (PrecedentReuse.admits(scope, m.getOriginInquiryId(), origin, inquiry == null ? null : inquiry.getId(),
                    current)) {
                out.add(p);
            }
        }
        return List.copyOf(out);
    }

    /** Whether this system can read the listing's 상세페이지 — from stored facts and listing channels only. */
    @Transactional(readOnly = true)
    public DetailCapability detail(UUID orgId, UUID productId) {
        if (productId == null) {
            return DetailCapability.NOT_APPLICABLE;
        }
        Set<String> codes = new LinkedHashSet<>();
        for (ChannelProduct cp : listings.findByOrgIdAndProductId(orgId, productId)) {
            channels.findById(cp.getChannelId()).ifPresent(ch -> codes.add(ch.getCode()));
        }
        String shape = facts.findByOrgIdAndProductId(orgId, productId).stream()
                .filter(f -> FactKeys.DETAIL_PAGE.equals(f.getFactKey())).map(ProductFact::getFactValue)
                .findFirst().orElse(null);
        if (shape != null) {
            return "IMAGE_ONLY".equals(shape) ? DetailCapability.IMAGE_ONLY : DetailCapability.READABLE;
        }
        if (codes.contains("NAVER")) {
            return DetailCapability.NOT_ACQUIRED;
        }
        return codes.isEmpty() ? DetailCapability.NOT_APPLICABLE : DetailCapability.NOT_COLLECTED;
    }

    private static void addLanes(InquiryEvidenceRetriever.InquiryEvidence lanes, Map<String, EvidenceCandidate> evidence,
                                 Map<UUID, PrecedentCandidate> precedents) {
        if (lanes == null) {
            return;
        }
        for (InquiryEvidenceRetriever.ScopedPassage p : lanes.passages()) {
            if (p.scope() == KnowledgeScope.PAST_ANSWER) {
                if (p.sourceId() != null && !precedents.containsKey(p.sourceId())) {
                    precedents.put(p.sourceId(), new PrecedentCandidate(null, p.sourceId(), bound(p.text(),
                            MAX_PRECEDENT_CHARS)));
                }
            } else if (p.scope().current()) {
                EvidenceCandidate.Kind kind = p.scope() == KnowledgeScope.ORG_OPERATIONS
                        ? EvidenceCandidate.Kind.ORG_KNOWLEDGE : EvidenceCandidate.Kind.PRODUCT_KNOWLEDGE;
                put(evidence, new EvidenceCandidate(null, kind, p.heading(), p.text(), p.sourceId(), null, null)
                        .withChunk(p.chunkId()));
            }
        }
    }

    private void addWholeLibraries(UUID orgId, UUID productId, KnowledgeVariantScope scope,
                                   Map<String, EvidenceCandidate> evidence) {
        if (productId != null) {
            Map<UUID, ProductKnowledgeSource> byId = new HashMap<>();
            for (ProductKnowledgeSource s : productSources.findAllByOrgIdAndProductIdOrderByCreatedAtAsc(orgId, productId)) {
                if (s.isActive() && s.getDataOrigin() == DataOrigin.REAL
                        && (scope == null || scope.admits(s.getVariantId()))) {
                    byId.put(s.getId(), s);
                }
            }
            List<ProductKnowledgeChunk> chunks = productChunks.findAllByOrgIdAndProductId(orgId, productId).stream()
                    .filter(c -> byId.containsKey(c.getSourceId()))
                    .sorted(Comparator.comparing(ProductKnowledgeChunk::getOrdinal)).toList();
            if (!chunks.isEmpty() && chunks.size() <= WHOLE_LIBRARY_PASSAGES) {
                for (ProductKnowledgeChunk c : chunks) {
                    put(evidence, new EvidenceCandidate(null, EvidenceCandidate.Kind.PRODUCT_KNOWLEDGE,
                            byId.get(c.getSourceId()).getTitle(), c.getContent(), c.getSourceId(), null, null)
                            .withChunk(c.getId()));
                }
            }
        }
        Map<UUID, OrgKnowledgeSource> rules = new HashMap<>();
        for (OrgKnowledgeSource s : orgSources.findAllByOrgIdOrderByCreatedAtAsc(orgId)) {
            if (s.isActive() && s.getDataOrigin() == DataOrigin.REAL) {
                rules.put(s.getId(), s);
            }
        }
        List<OrgKnowledgeChunk> ruleChunks = orgChunks.findAllByOrgId(orgId).stream()
                .filter(c -> rules.containsKey(c.getSourceId()))
                .sorted(Comparator.comparing(OrgKnowledgeChunk::getOrdinal)).toList();
        if (!ruleChunks.isEmpty() && ruleChunks.size() <= WHOLE_LIBRARY_PASSAGES) {
            for (OrgKnowledgeChunk c : ruleChunks) {
                put(evidence, new EvidenceCandidate(null, EvidenceCandidate.Kind.ORG_KNOWLEDGE,
                        rules.get(c.getSourceId()).getTitle(), c.getContent(), c.getSourceId(), null, null)
                        .withChunk(c.getId()));
            }
        }
    }

    /**
     * The stored catalogue of this listing, as three candidates: its facts, its options with their selling state and
     * its 추가상품 — one candidate each, so the judge reads a list as a list. Detail-page bookkeeping is not a fact.
     */
    private void addCatalogue(UUID orgId, UUID productId, CatalogueInvestigator.Finding catalogue,
                              Map<String, EvidenceCandidate> evidence) {
        if (productId != null) {
            List<String> factLines = new ArrayList<>();
            List<String> addons = new ArrayList<>();
            for (ProductFact f : facts.findByOrgIdAndProductId(orgId, productId)) {
                if (f.getFactKey() == null || f.getFactValue() == null || f.getFactValue().isBlank()
                        || FactKeys.DETAIL_PAGE.equals(f.getFactKey())) {
                    continue;
                }
                if (f.getFactKey().startsWith(FactKeys.SUPPLEMENT_PREFIX)) {
                    addons.add(f.getFactValue().strip());
                } else {
                    factLines.add(f.getFactKey() + ": " + f.getFactValue().strip()
                            + (f.getUnit() == null ? "" : " " + f.getUnit()));
                }
            }
            if (!factLines.isEmpty()) {
                put(evidence, new EvidenceCandidate(null, EvidenceCandidate.Kind.PRODUCT_FACTS, "상품 등록 정보",
                        bound(String.join("\n", factLines.stream().sorted().toList()), MAX_CATALOGUE_CHARS), null,
                        productId, null));
            }
            List<String> options = new ArrayList<>();
            for (ProductVariant v : variants.findByOrgIdAndProductId(orgId, productId)) {
                if (v.getOptionName() != null && !v.getOptionName().isBlank()) {
                    options.add(v.getOptionName().strip() + " (" + sellingKo(v.getSellingStatus()) + ")");
                }
            }
            if (!options.isEmpty()) {
                put(evidence, new EvidenceCandidate(null, EvidenceCandidate.Kind.OPTIONS, "옵션 목록",
                        bound(String.join("\n", options.stream().sorted().toList()), MAX_CATALOGUE_CHARS), null,
                        productId, null));
            }
            if (!addons.isEmpty()) {
                put(evidence, new EvidenceCandidate(null, EvidenceCandidate.Kind.ADDONS, "추가상품 목록",
                        bound(String.join("\n", addons.stream().sorted().toList()), MAX_CATALOGUE_CHARS), null,
                        productId, null));
            }
        }
        if (catalogue != null) {
            for (CatalogueInvestigator.Statement s : catalogue.matches()) {
                put(evidence, new EvidenceCandidate(null, EvidenceCandidate.Kind.PRODUCT_FACTS,
                        s.productName() == null ? "다른 상품" : s.productName(), s.text(), s.sourceId(), s.productId(),
                        s.factKey()));
            }
        }
    }

    private static String sellingKo(String status) {
        if (status == null) {
            return "상태 미확인";
        }
        return switch (status) {
            case "SELLING", "SALE" -> "판매 중";
            case "SUSPENDED", "SUSPENSION" -> "판매 중지";
            case "OUTOFSTOCK", "OUT_OF_STOCK", "SOLD_OUT" -> "품절";
            default -> status;
        };
    }

    private static void put(Map<String, EvidenceCandidate> evidence, EvidenceCandidate c) {
        if (c.text() == null || c.text().isBlank()) {
            return;
        }
        evidence.putIfAbsent(c.kind() + "|" + c.sourceId() + "|" + c.text().strip(), c);
    }

    private static String bound(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
