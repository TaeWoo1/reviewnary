package com.sellerops.review.draft;

import com.sellerops.inquiry.draft.InquiryEvidenceRetriever;
import com.sellerops.inquiry.draft.InquiryEvidenceRetriever.ScopedPassage;
import com.sellerops.common.ApiException;
import com.sellerops.inquiry.draft.dto.DraftEvidenceView;
import com.sellerops.knowledge.CustomerPhrases;
import com.sellerops.knowledge.KnowledgeBlanks;
import com.sellerops.knowledge.KnowledgeScope;
import com.sellerops.knowledge.org.OrgKnowledgeSource;
import com.sellerops.knowledge.org.OrgKnowledgeSourceRepository;
import com.sellerops.product.library.ProductKnowledgeSource;
import com.sellerops.product.library.ProductKnowledgeSourceRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>The guidance a review asks for in the customer's own words</b> — 「고객은 이렇게 말해요」 (owner decision 2026-10-27).
 *
 * <p>The seller writes, beside a piece of guidance, how customers say the problem it answers. A review that says one of
 * those expressions is answered with that guidance, ahead of whatever the lexical lanes found: the lexical scorer found
 * the registered adhesion note for 19 of 79 reviews that complained about exactly it, because 「떨어져요」 shares no word
 * with 「접착력이 약할 때」 (FINDINGS B33). Nothing is scored and nothing is guessed — the seller's list, matched as
 * {@link CustomerPhrases} reads it.
 *
 * <p><b>Only what the seller registered.</b> The corpus is active product documents of the review's own product and
 * the company's active rules; a proposal waiting in 확인 필요 is not a document and is never read, and a document that
 * still carries a 【확인: …】 blank is skipped ({@link KnowledgeBlanks}). A document written about one 규격 is left to the
 * lexical lane, which knows how to caution about a variant this review never named.
 *
 * <p><b>And what the seller said about it, for this review</b> (2026-10-27 evaluation). Measured on the owner's store,
 * phrases picked from past reviews found 27 of 43 later reviews that needed the guidance, and teaching each handled
 * review's words back found no more — customers say the same problem in new words. So the review screen asks: a
 * guidance the customer's words attached is a <i>candidate</i> until the seller says 「맞아요」 (CONFIRMED); one the
 * seller picks from the list is PICKED; 「아니에요」 is REJECTED and keeps it out of this review's drafts
 * ({@link ReviewGuidanceChoice}). What the seller chose comes first, then the phrase hits they have not refused.
 */
@Component
public class CustomerPhraseLane {

    /** A hit is the seller's own say-so; it ranks above anything the scorer found. */
    static final double HIT_SCORE = 1.0;

    /** How many registered guidances the picker lists — a product's notes and the company's rules. */
    static final int MAX_PICKABLE = 40;

    private final ProductKnowledgeSourceRepository productSources;
    private final OrgKnowledgeSourceRepository orgSources;
    /** The seller's choices per review; null where a test wires the lane without them. */
    private ReviewGuidanceChoiceRepository choices;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setChoices(ReviewGuidanceChoiceRepository choices) {
        this.choices = choices;
    }

    public CustomerPhraseLane(ProductKnowledgeSourceRepository productSources,
                              OrgKnowledgeSourceRepository orgSources) {
        this.productSources = productSources;
        this.orgSources = orgSources;
    }

    /** The guidance {@code reviewBody} names in a customer's words — product documents first, then company rules. */
    @Transactional(readOnly = true)
    public List<ScopedPassage> hits(UUID orgId, UUID productId, String reviewBody) {
        return phraseHits(orgId, productId, reviewBody).stream().map(Hit::passage).toList();
    }

    /** One attached guidance: the passage, how it came to be attached, and the customer phrase when that was how. */
    public record Hit(ScopedPassage passage, String how, String phrase) {

        /** Attached by the customer's words and not yet confirmed — a candidate, not the seller's word. */
        public static final String PHRASE = "PHRASE";

        public boolean candidate() {
            return PHRASE.equals(how);
        }
    }

    /** What a review's draft is given from this lane, and the guidances the seller refused for it. */
    public record ForReview(List<Hit> hits, Set<UUID> rejected) {

        public static final ForReview NONE = new ForReview(List.of(), Set.of());

        public List<ScopedPassage> passages() {
            return hits.stream().map(Hit::passage).toList();
        }

        /** The sources attached only by a phrase match — told to the model as candidates. */
        public Set<UUID> candidates() {
            Set<UUID> out = new HashSet<>();
            hits.stream().filter(Hit::candidate).forEach(h -> out.add(h.passage().sourceId()));
            return out;
        }
    }

    /**
     * The guidance for one review: what the seller chose for it first, then the phrase hits they have not refused.
     * A chosen guidance that has since been retired or blanked is skipped — the choice was about a sentence that is no
     * longer the seller's word.
     */
    @Transactional(readOnly = true)
    public ForReview forReview(UUID orgId, UUID reviewId, UUID productId, String reviewBody) {
        if (orgId == null) {
            return ForReview.NONE;
        }
        List<ReviewGuidanceChoice> said = choices == null || reviewId == null ? List.of()
                : choices.findAllByOrgIdAndReviewIdOrderByDecidedAtAsc(orgId, reviewId);
        Set<UUID> rejected = new HashSet<>();
        List<Hit> hits = new ArrayList<>();
        Set<UUID> taken = new HashSet<>();
        for (ReviewGuidanceChoice choice : said) {
            if (!choice.chosen()) {
                rejected.add(choice.getSourceId());
                continue;
            }
            usable(orgId, productId, choice.getSourceId()).ifPresent(p -> {
                if (taken.add(p.sourceId())) {
                    hits.add(new Hit(p, choice.getChoice(), choice.getMatchedPhrase()));
                }
            });
        }
        for (Hit hit : phraseHits(orgId, productId, reviewBody)) {
            if (!rejected.contains(hit.passage().sourceId()) && taken.add(hit.passage().sourceId())) {
                hits.add(hit);
            }
        }
        return new ForReview(List.copyOf(hits), Set.copyOf(rejected));
    }

    /** One guidance as the review screen lists it. {@code state}: PICKED · CONFIRMED · PHRASE · REJECTED · null. */
    public record Option(UUID sourceId, String scope, String title, String snippet, String state, String phrase) {
    }

    /** What the review screen shows: attached (in draft order), refused, and the rest the seller may pick. */
    public record Options(List<Option> attached, List<Option> rejected, List<Option> pickable) {
    }

    @Transactional(readOnly = true)
    public Options options(UUID orgId, UUID reviewId, UUID productId, String reviewBody) {
        ForReview found = forReview(orgId, reviewId, productId, reviewBody);
        List<Option> attached = found.hits().stream().map(h -> option(h.passage(), h.how(), h.phrase())).toList();
        Set<UUID> shown = new LinkedHashSet<>();
        attached.forEach(o -> shown.add(o.sourceId()));
        List<Option> rejected = new ArrayList<>();
        for (UUID id : found.rejected()) {
            any(orgId, productId, id).ifPresent(p -> rejected.add(option(p, ReviewGuidanceChoice.REJECTED, null)));
            shown.add(id);
        }
        rejected.sort(Comparator.comparing(Option::title));
        List<Option> pickable = new ArrayList<>();
        for (ScopedPassage p : registered(orgId, productId)) {
            if (!shown.contains(p.sourceId()) && pickable.size() < MAX_PICKABLE) {
                pickable.add(option(p, null, null));
            }
        }
        return new Options(attached, List.copyOf(rejected), List.copyOf(pickable));
    }

    /**
     * Record what the seller said about one guidance for one review. Only a guidance the review could be given — this
     * review's product (not one 규격 of it) or the company's rules, in use and with no 【확인】 blank.
     */
    @Transactional
    public void choose(UUID orgId, UUID reviewId, UUID productId, String reviewBody, UUID sourceId, String choice,
                       UUID actorUserId, String actorName) {
        if (!List.of(ReviewGuidanceChoice.CONFIRMED, ReviewGuidanceChoice.PICKED, ReviewGuidanceChoice.REJECTED)
                .contains(choice)) {
            throw ApiException.badRequest("맞아요 · 고르기 · 아니에요 중 하나여야 합니다.");
        }
        if (choices == null) {
            throw ApiException.conflict("안내 선택을 저장할 수 없습니다.");
        }
        ScopedPassage passage = (ReviewGuidanceChoice.REJECTED.equals(choice) ? any(orgId, productId, sourceId)
                : usable(orgId, productId, sourceId))
                .orElseThrow(() -> ApiException.notFound("이 리뷰에 쓸 수 있는 안내가 아닙니다."));
        String phrase = phraseHits(orgId, productId, reviewBody).stream()
                .filter(h -> sourceId.equals(h.passage().sourceId())).map(Hit::phrase).findFirst().orElse(null);
        ReviewGuidanceChoice row = choices.findByOrgIdAndReviewIdAndSourceId(orgId, reviewId, sourceId)
                .orElseGet(ReviewGuidanceChoice::new);
        row.setOrgId(orgId);
        row.setReviewId(reviewId);
        row.setSourceId(sourceId);
        row.setSourceScope(passage.scope() == KnowledgeScope.PRODUCT ? "PRODUCT" : "ORG");
        row.setChoice(choice);
        row.setMatchedPhrase(phrase == null ? null : phrase.substring(0, Math.min(phrase.length(), 200)));
        row.setDecidedBy(actorUserId);
        row.setDecidedByName(actorName);
        row.setDecidedAt(Instant.now());
        choices.save(row);
    }

    /** Take back what the seller said — the guidance is again whatever the customer's words make it. */
    @Transactional
    public void clear(UUID orgId, UUID reviewId, UUID sourceId) {
        if (choices != null) {
            choices.findByOrgIdAndReviewIdAndSourceId(orgId, reviewId, sourceId).ifPresent(choices::delete);
        }
    }

    private List<Hit> phraseHits(UUID orgId, UUID productId, String reviewBody) {
        if (orgId == null || reviewBody == null || reviewBody.isBlank()) {
            return List.of();
        }
        List<Hit> found = new ArrayList<>();
        if (productId != null) {
            productSources.findAllByOrgIdAndProductIdAndActiveTrueAndCustomerPhrasesIsNotNull(orgId, productId)
                    .stream()
                    .filter(s -> s.getVariantId() == null && !KnowledgeBlanks.carries(s.getBody()))
                    .sorted(Comparator.comparing(ProductKnowledgeSource::getTitle))
                    .forEach(s -> CustomerPhrases.firstHit(reviewBody, CustomerPhrases.parse(s.getCustomerPhrases()))
                            .ifPresent(phrase -> found.add(new Hit(passageOf(s), Hit.PHRASE, phrase))));
        }
        orgSources.findAllByOrgIdAndActiveTrueAndCustomerPhrasesIsNotNull(orgId).stream()
                .filter(s -> !KnowledgeBlanks.carries(s.getBody()))
                .sorted(Comparator.comparing(OrgKnowledgeSource::getTitle))
                .forEach(s -> CustomerPhrases.firstHit(reviewBody, CustomerPhrases.parse(s.getCustomerPhrases()))
                        .ifPresent(phrase -> found.add(new Hit(passageOf(s), Hit.PHRASE, phrase))));
        return List.copyOf(found);
    }

    /** Every guidance a review of {@code productId} could be given — its product's notes, then the company's rules. */
    private List<ScopedPassage> registered(UUID orgId, UUID productId) {
        List<ScopedPassage> out = new ArrayList<>();
        if (productId != null) {
            productSources.findAllByOrgIdAndProductIdOrderByCreatedAtAsc(orgId, productId).stream()
                    .filter(s -> s.isActive() && s.getVariantId() == null && !KnowledgeBlanks.carries(s.getBody()))
                    .sorted(Comparator.comparing(ProductKnowledgeSource::getTitle))
                    .forEach(s -> out.add(passageOf(s)));
        }
        orgSources.findAllByOrgIdOrderByCreatedAtAsc(orgId).stream()
                .filter(s -> s.isActive() && !KnowledgeBlanks.carries(s.getBody()))
                .sorted(Comparator.comparing(OrgKnowledgeSource::getTitle))
                .forEach(s -> out.add(passageOf(s)));
        return out;
    }

    /** The guidance, if this review may be given it now. */
    private Optional<ScopedPassage> usable(UUID orgId, UUID productId, UUID sourceId) {
        Optional<ProductKnowledgeSource> product = productSources.findByIdAndOrgId(sourceId, orgId)
                .filter(s -> s.isActive() && s.getVariantId() == null && !KnowledgeBlanks.carries(s.getBody())
                        && productId != null && productId.equals(s.getProductId()));
        if (product.isPresent()) {
            return product.map(CustomerPhraseLane::passageOf);
        }
        return orgSources.findByIdAndOrgId(sourceId, orgId)
                .filter(s -> s.isActive() && !KnowledgeBlanks.carries(s.getBody()))
                .map(CustomerPhraseLane::passageOf);
    }

    /** The guidance whatever its state — a refusal may name one that has since been retired. */
    private Optional<ScopedPassage> any(UUID orgId, UUID productId, UUID sourceId) {
        Optional<ProductKnowledgeSource> product = productSources.findByIdAndOrgId(sourceId, orgId)
                .filter(s -> productId != null && productId.equals(s.getProductId()));
        if (product.isPresent()) {
            return product.map(CustomerPhraseLane::passageOf);
        }
        return orgSources.findByIdAndOrgId(sourceId, orgId).map(CustomerPhraseLane::passageOf);
    }

    private static ScopedPassage passageOf(ProductKnowledgeSource s) {
        return new ScopedPassage(KnowledgeScope.PRODUCT, s.getTitle(), s.getBody(), s.getId(), null,
                InquiryEvidenceRetriever.productLocator(s.getSourceType(), s.getAuthorName()), HIT_SCORE,
                s.getAuthoredOrigin());
    }

    private static ScopedPassage passageOf(OrgKnowledgeSource s) {
        return new ScopedPassage(KnowledgeScope.ORG_OPERATIONS, s.getTitle(), s.getBody(), s.getId(), null,
                InquiryEvidenceRetriever.policyLocator(s.getKnowledgeType(), s.getAuthorName(), s.getVersion()),
                HIT_SCORE);
    }

    private static Option option(ScopedPassage p, String state, String phrase) {
        return new Option(p.sourceId(), p.scope() == KnowledgeScope.PRODUCT ? "PRODUCT" : "ORG", p.heading(),
                DraftEvidenceView.snippetOf(p.text()), state, phrase);
    }
}
