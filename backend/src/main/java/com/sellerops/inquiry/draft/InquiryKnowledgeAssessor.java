package com.sellerops.inquiry.draft;

import com.sellerops.common.MarkupText;
import com.sellerops.inquiry.Inquiry;
import com.sellerops.inquiry.draft.dto.KnowledgeGapView;
import com.sellerops.knowledge.KnowledgeTopic;
import com.sellerops.knowledge.RetrievalQuery;
import com.sellerops.knowledge.spine.KnowledgeSpineService;
import com.sellerops.knowledge.spine.SpineRetrieval;
import com.sellerops.order.fact.OrderFactLookup;
import com.sellerops.product.ProductVariantRepository;
import com.sellerops.product.catalogue.CatalogueInvestigator;
import com.sellerops.product.library.KnowledgeVariantScope;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * <b>What the company knows about one inquiry, and whether that is enough to answer it — decided once, in one
 * place.</b>
 *
 * <p>The case investigator and the draft writer used to reach this judgement through two different reads: the
 * investigator searched company rules alone and found nothing, while the draft searched product knowledge, rules and
 * past answers and wrote a grounded reply from two seller passages (NAVER product inquiry slice, 2026-09-18,
 * INVESTIGATOR_RETRIEVAL_GAP). Both now call this: the 규격 verdict computed before retrieval, the Knowledge Spine's
 * one retrieval, the answer-basis state, and the knowledge gap. A disagreement between them can only be a difference
 * in the question, never in the code that answered it.
 *
 * <p>Reads only. It files no candidate, writes no draft and calls no model; the only marketplace reach is the order
 * fact, and only when the caller says {@link OrderFactLookup#EXACT_ALLOWED}.
 */
@Component
public class InquiryKnowledgeAssessor {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(InquiryKnowledgeAssessor.class);

    private final InquiryEvidenceRetriever retriever;
    private final KnowledgeSpineService spine;
    private final ProductVariantRepository variants;
    private final com.sellerops.product.ProductRepository products;
    private CatalogueInvestigator catalogue;

    public InquiryKnowledgeAssessor(InquiryEvidenceRetriever retriever, KnowledgeSpineService spine,
                                    ProductVariantRepository variants,
                                    com.sellerops.product.ProductRepository products) {
        this.retriever = retriever;
        this.spine = spine;
        this.variants = variants;
        this.products = products;
    }

    /**
     * @param missingSubject what the seller has not written down, in the customer's own noun — the 규격 classifier's
     *                       word when it named one, otherwise the question's remaining topic word. Null when there is
     *                       no gap or the question named nothing to ask for.
     */
    public record Assessment(UUID productId, SpecApplicability.Verdict verdict, SpineRetrieval spine,
                             AnswerBasisState basis, KnowledgeTopic asked, Set<KnowledgeTopic> named,
                             KnowledgeGapView gap, String missingSubject, CatalogueInvestigator.Finding catalogue,
                             com.sellerops.inquiry.decision.NeedDecision decision) {

        /** Before Inquiry Decision v2: no need-level decision. */
        public Assessment(UUID productId, SpecApplicability.Verdict verdict, SpineRetrieval spine,
                          AnswerBasisState basis, KnowledgeTopic asked, Set<KnowledgeTopic> named,
                          KnowledgeGapView gap, String missingSubject, CatalogueInvestigator.Finding catalogue) {
            this(productId, verdict, spine, basis, asked, named, gap, missingSubject, catalogue, null);
        }

        public Assessment(UUID productId, SpecApplicability.Verdict verdict, SpineRetrieval spine,
                          AnswerBasisState basis, KnowledgeTopic asked, Set<KnowledgeTopic> named,
                          KnowledgeGapView gap, String missingSubject) {
            this(productId, verdict, spine, basis, asked, named, gap, missingSubject, null, null);
        }

        /** Whether the only current basis is another product in the seller's catalogue. */
        public boolean groundedByCatalogue() {
            return catalogue != null && catalogue.grounds()
                    && !AnswerBasisState.of(spine.lanes().state(), verdict.applicability()).mayGenerate();
        }

        public InquiryEvidenceRetriever.InquiryEvidence retrieved() {
            return spine.lanes();
        }
    }

    /**
     * Seller-wide catalogue discovery for catalogue questions. Optional: a context without it assesses exactly as
     * before, and a catalogue read that throws is a catalogue that said nothing.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setCatalogue(CatalogueInvestigator catalogue) {
        this.catalogue = catalogue;
    }

    private com.sellerops.knowledge.schedule.KnowledgeScheduleRepository schedules;

    /**
     * The seller's schedule table, for the options it names (2026-11-15 final eval q03). Optional: without it, options
     * are the channel's variant rows alone, as before.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setSchedules(com.sellerops.knowledge.schedule.KnowledgeScheduleRepository schedules) {
        this.schedules = schedules;
    }

    private com.sellerops.inquiry.decision.InquiryDecisionModel decisionModel;
    private com.sellerops.inquiry.decision.InquiryEvidenceCollector collector;

    /**
     * Inquiry Decision v2 (need-level coverage). Optional: a context without it — or an org the capability is off for —
     * assesses exactly as before.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setDecision(com.sellerops.inquiry.decision.InquiryDecisionModel decisionModel,
                            com.sellerops.inquiry.decision.InquiryEvidenceCollector collector) {
        this.decisionModel = decisionModel;
        this.collector = collector;
    }

    private com.sellerops.inquiry.authority.CapabilityRegistry registry;
    private com.sellerops.inquiry.authority.AuthorityFenceProperties fence;

    private com.sellerops.inquiry.sellercheck.InquiryKindCorrectionRepository kindCorrections;
    private com.sellerops.inquiry.workitem.InquiryWorkItemRepository kindWorkItems;

    /**
     * The seller's correction of what kind of inquiry this is (2026-11-05 seventh review). While one stands, the needs are
     * planned under it ({@link com.sellerops.inquiry.decision.InquiryKind#apply}) here — the one place every caller's
     * decision is made — so the draft, the case reading and the investigation read the same inquiry. Optional: without
     * it nothing is corrected.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setKindCorrections(com.sellerops.inquiry.sellercheck.InquiryKindCorrectionRepository kindCorrections,
                                   com.sellerops.inquiry.workitem.InquiryWorkItemRepository kindWorkItems) {
        this.kindCorrections = kindCorrections;
        this.kindWorkItems = kindWorkItems;
    }

    /** The kind the seller set for this inquiry, or null when none stands. */
    private com.sellerops.inquiry.decision.InquiryKind correctedKind(UUID orgId, Inquiry inquiry) {
        if (kindCorrections == null || kindWorkItems == null || inquiry == null || inquiry.getId() == null) {
            return null;
        }
        return kindWorkItems.findByInquiryId(inquiry.getId()).filter(w -> orgId.equals(w.getOrgId()))
                .flatMap(w -> kindCorrections
                        .findFirstByOrgIdAndWorkItemIdAndSupersededAtIsNullOrderByCorrectedAtDesc(orgId, w.getId()))
                .map(c -> com.sellerops.inquiry.decision.InquiryKind.parse(c.getKind())).orElse(null);
    }

    private com.sellerops.inquiry.resolve.CustomerGoalInterpretation goals;

    /**
     * What this inquiry's customer asked for, when something has already read the message.
     *
     * <p>Read-only by contract ({@code stored} never reaches a vendor) and optional: without it, or before any
     * reading exists, the gap subject is taken from the question as it always was. It is here rather than at the
     * three call sites because the subject is decided here, and a rule about what may be quoted to the seller that
     * holds in one caller and not the others is the defect it fixes wearing a different shape.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setGoals(com.sellerops.inquiry.resolve.CustomerGoalInterpretation goals) {
        this.goals = goals;
    }

    private com.sellerops.inquiry.goal.CustomerGoalSet goalsOf(java.util.UUID orgId, Inquiry inquiry) {
        if (goals == null) {
            return null;
        }
        try {
            return goals.stored(orgId, inquiry).orElse(null);
        } catch (RuntimeException unreadable) {
            return null;   // an unavailable reading is an absence; the question itself is still evidence
        }
    }

    /**
     * Inquiry v3 WP-1: the deterministic authority layer over the v2 verdicts. Optional and default OFF — without it, or
     * with the flag off, the decision is v2.2 exactly.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setAuthority(com.sellerops.inquiry.authority.CapabilityRegistry registry,
                             com.sellerops.inquiry.authority.AuthorityFenceProperties fence) {
        this.registry = registry;
        this.fence = fence;
    }

    /**
     * The assessment without seller context — the lanes alone, attributed from the passages. For hand-wired callers
     * that have a retriever and no Spring context; production wiring always injects the full spine.
     */
    public static InquiryKnowledgeAssessor withoutContext(InquiryEvidenceRetriever retriever,
                                                          ProductVariantRepository variants) {
        return new InquiryKnowledgeAssessor(retriever,
                new KnowledgeSpineService(List.of(), null, null, retriever), variants, null);
    }

    public Assessment assess(UUID orgId, Inquiry inquiry, OrderFactLookup lookup) {
        return assess(orgId, inquiry, lookup, com.sellerops.agent.quota.AgentUsageActor.USER);
    }

    /** The same, with the decision's model calls charged to {@code actor} — who asked for this assessment. */
    public Assessment assess(UUID orgId, Inquiry inquiry, OrderFactLookup lookup,
                             com.sellerops.agent.quota.AgentUsageActor actor) {
        String title = MarkupText.toPlainText(inquiry.getTitle());
        String details = MarkupText.toPlainText(inquiry.getBody());
        // The 규격 is resolved BEFORE the retrieval it scopes (2026-08-27): a document about 2호 is not weak evidence
        // about a customer who said 3호.
        UUID productId = retriever.resolveProductId(orgId, inquiry);
        SpecApplicability.Verdict classified = SpecApplicability.classify(title, details, optionsFor(orgId, productId));
        SpineRetrieval found = spine.retrieveForInquiry(orgId, inquiry, RetrievalQuery.ofCustomer(title, details),
                lookup, KnowledgeVariantScope.of(classified.variantId()));
        // With no channel option list, the seller's own table may still name the option the customer wrote (q03).
        // The name and the shared-name check are read only when the table can decide (B176), never for every inquiry.
        java.util.function.Supplier<String> name = once(() -> productName(orgId, productId));
        SpecApplicability.Verdict verdict = SpecApplicability.namedInSellerTable(classified, title, details, name,
                () -> noOtherProductNamed(orgId, productId, name.get()),
                found.lanes().passages().stream().filter(p -> p.scope().current())
                        .map(InquiryEvidenceRetriever.ScopedPassage::text).toList());
        AnswerBasisState basis = AnswerBasisState.of(found.lanes().state(), verdict.applicability(),
                coversEveryOption(found.lanes(), verdict));
        Set<KnowledgeTopic> named = namedTopics(inquiry);
        KnowledgeTopic asked = named.size() == 1 ? named.iterator().next() : null;
        // Seller-wide catalogue discovery, for a question about what the seller sells (「9oz 디스펜서도 판매하시나요」).
        // It runs after the product's own knowledge and before anything is declared missing: a question whose answer
        // is on another listing of this seller is not a gap in this seller's knowledge. It never widens a basis that
        // the lanes already settled — it only turns NO_ANSWER_BASIS into GROUNDED, and only on a product the channel
        // says is on sale now, stating the asked value in the seller's own catalogue text.
        CatalogueInvestigator.Finding catalogueFinding = investigateCatalogue(orgId, productId, title, details,
                lookup == OrderFactLookup.EXACT_ALLOWED);
        if (basis == AnswerBasisState.NO_ANSWER_BASIS && catalogueFinding != null && catalogueFinding.grounds()) {
            basis = AnswerBasisState.GROUNDED;
        }
        String subject = basis != AnswerBasisState.NO_ANSWER_BASIS ? null
                : catalogueFinding != null ? catalogueFinding.question().subject()
                : GapSubject.of(verdict, title, details, name.get(), asked,
                        goalsOf(orgId, inquiry));
        KnowledgeGapView gap = KnowledgeGapView.of(found.lanes(), verdict, asked, named).asking(subject);
        if (basis == AnswerBasisState.NO_ANSWER_BASIS && catalogueFinding != null) {
            gap = gap.catalogueChecked(catalogueFinding.checkedKo());
        }
        if (basis == AnswerBasisState.NO_ANSWER_BASIS) {
            gap = gap.withPrecedent(precedentOf(found.lanes()));
        }
        if (decisionModel == null || collector == null || !decisionModel.enabledFor(orgId)) {
            return new Assessment(productId, verdict, found, basis, asked, named, gap, subject, catalogueFinding);
        }
        return decide(orgId, inquiry, title, details, productId, verdict, found, asked, named, catalogueFinding, lookup,
                actor);
    }

    /**
     * <b>Inquiry Decision v2</b>: the basis is derived from every need the customer's message carries, not from whether
     * a current passage exists. Relevant evidence for one need no longer completes a Case with three
     * (docs/lineage/inquiry_decision_v2.md). The legacy lanes still run and are still what the draft cites; what changes is who
     * decides whether there is a basis.
     */
    private Assessment decide(UUID orgId, Inquiry inquiry, String title, String details, UUID productId,
                              SpecApplicability.Verdict verdict, SpineRetrieval found, KnowledgeTopic asked,
                              Set<KnowledgeTopic> named, CatalogueInvestigator.Finding catalogueFinding,
                              OrderFactLookup lookup,
                              com.sellerops.agent.quota.AgentUsageActor actor) {
        String question = ((title == null ? "" : title) + "\n" + (details == null ? "" : details)).strip();
        com.sellerops.inquiry.decision.DetailCapability detail = collector.detail(orgId, productId);
        com.sellerops.product.library.KnowledgeVariantScope scope =
                com.sellerops.product.library.KnowledgeVariantScope.of(verdict.variantId());
        String orderKey = com.sellerops.inquiry.decision.EvidenceScope.orderKey(
                com.sellerops.inquiry.decision.PrecedentReuse.OrderKey.of(inquiry));
        com.sellerops.inquiry.authority.AuthorityFence.Context authority = registry == null || fence == null
                || !fence.enabled() ? null
                : new com.sellerops.inquiry.authority.AuthorityFence.Context(
                        registry.snapshot(orgId, inquiry, productId, detail, lookup),
                        found.lanes() == null ? null : found.lanes().order(), orderKey);
        com.sellerops.inquiry.decision.NeedDecision decision = com.sellerops.inquiry.decision.InquiryDecisionEngine
                .decide(orgId, question, com.sellerops.inquiry.decision.InquiryKind.correcting(
                                decisionModel.chargedTo(actor), correctedKind(orgId, inquiry)),
                        needs -> collector.collect(orgId, inquiry, productId, found.lanes(), catalogueFinding, scope,
                                needs), detail, new com.sellerops.inquiry.decision.EvidenceScope.CaseScope(productId,
                        // The order this Case is about — the channel's, or this inquiry's as the seller checked it.
                        com.sellerops.inquiry.decision.EvidenceScope.caseOrderKey(inquiry)), authority,
                com.sellerops.inquiry.decision.InquiryMoment.of(inquiry == null ? null : inquiry.getReceivedAt()));
        AnswerBasisState basis = decision.basis();
        List<com.sellerops.inquiry.decision.NeedResult> open = decision.unresolved();
        // A partial answer still names what is missing, so the seller can add it (tenth review).
        boolean short_ = basis == AnswerBasisState.NO_ANSWER_BASIS || basis == AnswerBasisState.PARTIAL_ANSWER;
        String subject = !short_ ? null
                : !open.isEmpty() ? open.get(0).need().ask()
                : GapSubject.of(verdict, title, details, productName(orgId, productId), asked,
                        goalsOf(orgId, inquiry));
        KnowledgeGapView gap = KnowledgeGapView.of(found.lanes(), verdict, asked, named).asking(subject)
                .withNeeds(views(decision));
        if (short_) {
            gap = gap.withPrecedent(open.stream().flatMap(n -> n.precedents().stream())
                    .map(com.sellerops.inquiry.decision.PrecedentCandidate::memoryId).findFirst().orElse(null));
        }
        return new Assessment(productId, verdict, found, basis, asked, named, gap, subject, catalogueFinding, decision);
    }

    /** Whether the current evidence says the asked value holds for every 규격 — as the composer's 규격 line reads it. */
    static boolean coversEveryOption(InquiryEvidenceRetriever.InquiryEvidence lanes, SpecApplicability.Verdict verdict) {
        return lanes != null && lanes.figuresUnaided() && !lanes.variantSpecific()
                && SpecApplicability.coversEveryOption(lanes.passages().stream().filter(p -> p.scope().current())
                        .map(InquiryEvidenceRetriever.ScopedPassage::text).toList(), verdict.topicWord());
    }

    static List<com.sellerops.inquiry.draft.dto.NeedCoverageView> views(
            com.sellerops.inquiry.decision.NeedDecision decision) {
        return decision.needs().stream().map(n -> new com.sellerops.inquiry.draft.dto.NeedCoverageView(
                n.need().id(), n.need().ask(), n.need().type().name(), n.status().name(),
                // Covered for the seller's work, but never read as answered (2026-11-06 thirteenth review).
                n.settledByAction() ? com.sellerops.inquiry.decision.NeedResult.Enforcement.SETTLED_BY_ACTION_KO
                        // Not 「기준 없음」: the store wrote it down twice, differently (2026-11-15).
                        : n.enforcement() == com.sellerops.inquiry.decision.NeedResult.Enforcement.UNRESOLVED_CONFLICT
                        ? com.sellerops.inquiry.decision.NeedResult.Enforcement.UNRESOLVED_CONFLICT_KO
                        : n.status().labelKo(),
                n.evidence().stream().map(e -> e.kind().scopeLabelKo() + " · " + e.label()).distinct().toList(),
                n.missing(), n.askCustomer(),
                n.precedents().stream().map(com.sellerops.inquiry.decision.PrecedentCandidate::memoryId).toList(),
                n.acquirable())).toList();
    }

    /**
     * The seller's own past answer the memory lane found, when that is ALL that was found (Past Answer Prefill v1).
     *
     * <p>Only when no current passage — product knowledge or company rule — came back: a question the company's own
     * knowledge already speaks to is not one the seller should be asked to restate from memory. The past answer
     * stays what it was; it does not change the basis, and nothing here promotes it. It is handed to the one place
     * that asks the seller for knowledge, so that ask can start from what the seller already said.
     */
    static UUID precedentOf(InquiryEvidenceRetriever.InquiryEvidence lanes) {
        if (lanes == null || lanes.passages().stream().anyMatch(p -> p.scope().current())) {
            return null;
        }
        return lanes.passages().stream()
                .filter(p -> p.scope() == com.sellerops.knowledge.KnowledgeScope.PAST_ANSWER)
                .map(InquiryEvidenceRetriever.ScopedPassage::sourceId)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    /** The catalogue's answer for a catalogue question; null for any other question, or when the read failed. */
    private CatalogueInvestigator.Finding investigateCatalogue(UUID orgId, UUID productId, String title,
                                                               String details, boolean mayReadDetail) {
        if (catalogue == null) {
            return null;
        }
        try {
            return catalogue.question(orgId, title, details)
                    .map(q -> catalogue.investigate(orgId, productId, q, mayReadDetail))
                    .orElse(null);
        } catch (RuntimeException unreadable) {
            return null;
        }
    }


    /**
     * This product's name for matching a seller's table row — or null when another product of this seller has the same
     * name (2026-11-16 review B172): a table names products by name, so a name two products share identifies neither,
     * and the option stays unresolved.
     */
    String unambiguousName(UUID orgId, UUID productId) {
        String name = productName(orgId, productId);
        return name != null && noOtherProductNamed(orgId, productId, name) ? name : null;
    }

    /**
     * No other product of this org carries {@code name} under {@link SpecApplicability#sameProductKey} (B172 · B174),
     * read as name/sku rows only (B176: no entity of the catalogue is loaded). Not knowing is not 「unique」: a failed
     * read, or no name to compare, answers false, and the option then stays unresolved.
     */
    boolean noOtherProductNamed(UUID orgId, UUID productId, String name) {
        if (name == null || products == null || orgId == null || productId == null) {
            return false;
        }
        String key = SpecApplicability.sameProductKey(name);
        try {
            return products.findOtherNamesByOrgId(orgId, productId).stream()
                    .map(r -> com.sellerops.product.OperatorProductName.displayNameOrNull(r.name(), r.sku()))
                    .noneMatch(n -> n != null && key.equals(SpecApplicability.sameProductKey(n)));
        } catch (RuntimeException e) {
            log.warn("shared_name_check_unread reason={}", e.getClass().getSimpleName());
            return false;
        }
    }

    private static <T> java.util.function.Supplier<T> once(java.util.function.Supplier<T> source) {
        return new java.util.function.Supplier<>() {
            private boolean read;
            private T value;

            @Override
            public T get() {
                if (!read) {
                    value = source.get();
                    read = true;
                }
                return value;
            }
        };
    }

    /** This product's display name in this org, or null (no product, another org's id, a name that is a sku). */
    public String displayName(UUID orgId, UUID productId) {
        return productName(orgId, productId);
    }

    private String productName(UUID orgId, UUID productId) {
        return productId == null || products == null ? null : products.findById(productId)
                .filter(p -> orgId.equals(p.getOrgId()))
                .map(com.sellerops.product.OperatorProductName::displayNameOrNull).orElse(null);
    }

    /** The options a question is classified against — package-private for its regression. */
    List<SpecApplicability.Option> optionsFor(UUID orgId, UUID productId) {
        if (productId == null) {
            return List.of();
        }
        List<SpecApplicability.Option> out = new java.util.ArrayList<>(variants.findByOrgIdAndProductId(orgId, productId)
                .stream()
                .filter(v -> v.getOptionName() != null && !v.getOptionName().isBlank())
                .map(v -> new SpecApplicability.Option(v.getId(), v.getOptionName()))
                .toList());
        // The channel's list is the authority (2026-11-16 review B172): the schedule's names stand in only for a
        // listing that has none, never beside one.
        if (out.isEmpty()) {
            out.addAll(scheduledOptions(orgId, productId, out));
        }
        return List.copyOf(out);
    }

    /** What stands for the whole product in a schedule's option column, not for one option of it. */
    private static final java.util.Set<String> WHOLE_PRODUCT = java.util.Set.of("전체", "공통", "모든옵션", "전옵션", "-");

    /**
     * The options this product's own schedule rows name (2026-11-15 final eval q03: 「민트 색상 언제 다시 들어오나요?」 on a
     * listing with no channel variant rows was asked back 「어느 상품 · 규격인지」, though the seller's schedule has a 민트
     * row for exactly this product). A seller who writes an option into their schedule is saying the product sells it,
     * so a customer who names it has named an option; one who names none is still unresolved and still asked. No
     * variant id: retrieval stays unscoped, as for any product-level answer. A name the channel rows already carry is
     * not added twice, and a row for the whole product (「전체」) names no option.
     */
    private List<SpecApplicability.Option> scheduledOptions(UUID orgId, UUID productId,
                                                            List<SpecApplicability.Option> known) {
        if (schedules == null) {
            return List.of();
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        known.forEach(o -> seen.add(o.name().strip()));
        List<SpecApplicability.Option> out = new java.util.ArrayList<>();
        for (com.sellerops.knowledge.schedule.KnowledgeSchedule s : schedules.findAllByOrgIdOrderByCreatedAtAsc(orgId)) {
            String name = s.getOptionName() == null ? "" : s.getOptionName().strip();
            if (!productId.equals(s.getProductId()) || !s.isActive() || name.isEmpty()
                    || WHOLE_PRODUCT.contains(name.replace(" ", "")) || !seen.add(name)) {
                continue;
            }
            out.add(new SpecApplicability.Option(null, name));
        }
        return out;
    }

    static Set<KnowledgeTopic> namedTopics(Inquiry inquiry) {
        return KnowledgeTopic.of((inquiry.getTitle() == null ? "" : inquiry.getTitle()) + " "
                + (inquiry.getBody() == null ? "" : inquiry.getBody()));
    }
}
