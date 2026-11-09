package com.sellerops.review.draft;

import com.sellerops.attention.reply.ReviewReplyDraft;
import com.sellerops.attention.reply.ReviewReplyDraftRepository;
import com.sellerops.attention.reply.ReviewReplyFingerprint;
import com.sellerops.attention.reply.ReviewReplyValidation;
import com.sellerops.common.BaseEntity;
import com.sellerops.inquiry.draft.InquiryDraftEvidence;
import com.sellerops.knowledge.org.OrgKnowledgeSource;
import com.sellerops.knowledge.org.OrgKnowledgeSourceRepository;
import com.sellerops.product.library.ProductKnowledgeSource;
import com.sellerops.product.library.ProductKnowledgeSourceRepository;
import com.sellerops.review.Review;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>A sent-back reply checked again before anyone is asked</b> (product-owner answer to interview question 34,
 * 2026-10-12: 「재검증 방식」 — the AI checks first; a draft that still stands goes on, one that does not is held and
 * the seller is asked).
 *
 * <p>No model writes or judges anything. The check is what this PC can prove from its own rows and the review's own
 * retrieval ({@link ReviewDraftComposer#currentLicense} — the draft's one retrieval, which uses the embedding model
 * only where semantic search is turned on), and it is deliberately the strict reading of "still stands":
 *
 * <ol>
 *   <li><b>What it was written from is unchanged.</b> When the text is a version written here, every product or
 *       company document that version cited must still exist and be in use, and the passage it cited must still be
 *       in it — else {@value #SOURCE_CHANGED}. An edit elsewhere in the same document is not a change to what the
 *       reply used (stage 2, 2026-10-23: the cited passage's fingerprint is found among the document's passages as
 *       they stand). A citation recorded before fingerprints were kept is judged by the document's edit time, as
 *       before. A citation to a past reply is not checked: it was never a fact.</li>
 *   <li><b>It promises nothing the current evidence does not</b> — neither the remedy nor its terms (period, amount,
 *       scope). {@link ReviewClaimGuard} against the evidence the review finds now, and the seller's own wording for
 *       its kind of review — else {@value #UNSUPPORTED_PROMISE}.
 *       This is the only check a text written on the phone can get here: it has no citations of its own.</li>
 *   <li><b>It says nothing the company has banned.</b> {@link ReviewStyleGuard} — the 답변 스타일 forbidden phrases,
 *       less bare topic words — else {@value #FORBIDDEN_PHRASE} (2026-10-23, finding B24).</li>
 * </ol>
 *
 * <p>A seller's edit is never rewritten: the result is pass or hold, and the text is the text.
 */
@Service
public class ReviewDraftRecheck {

    public static final String SOURCE_CHANGED = "SOURCE_CHANGED";
    public static final String UNSUPPORTED_PROMISE = "UNSUPPORTED_PROMISE";
    public static final String FORBIDDEN_PHRASE = "FORBIDDEN_PHRASE";
    /**
     * Some of the promise words are not merely unbacked: a policy speaks of the same remedy and says otherwise — 「불가」
     * written up as 「가능」, a grant without the state the policy limits it to, another period than the policy's
     * (2026-11-01 review: 「근거 없음과 정책 충돌도 구분해 주세요」). Always alongside {@value #UNSUPPORTED_PROMISE}, whose
     * {@code promises} still carry every word, so a reader that knows only that code holds on it as before.
     */
    public static final String POLICY_CONFLICT = "POLICY_CONFLICT";

    /**
     * The finding, and what the seller is shown about it (owner decision 2026-10-23: a hold names the changed basis,
     * the sentence it touches and what to do). {@code changed}: the cited documents that were edited or taken out of
     * use; {@code promises}: the commitment words that lost their ground; {@code sentences}: the reply's own
     * sentences that carry them, at most {@value #MAX_SENTENCES}.
     */
    public record Result(boolean passed, List<String> codes, List<Changed> changed, List<String> promises,
                         List<String> sentences, List<String> phrases, List<String> conflicts,
                         List<Against> against) {

        public Result {
            conflicts = conflicts == null ? List.of() : conflicts;
            against = against == null ? List.of() : against;
        }

        public Result(boolean passed, List<String> codes, List<Changed> changed, List<String> promises,
                      List<String> sentences, List<String> phrases, List<String> conflicts) {
            this(passed, codes, changed, promises, sentences, phrases, conflicts, List.of());
        }

        public Result(boolean passed, List<String> codes, List<Changed> changed, List<String> promises,
                      List<String> sentences, List<String> phrases) {
            this(passed, codes, changed, promises, sentences, phrases, List.of(), List.of());
        }

        static Result of(List<String> codes, List<Changed> changed, List<String> promises, List<String> sentences,
                         List<String> phrases, List<String> conflicts, List<Against> against) {
            return new Result(codes.isEmpty(), List.copyOf(codes), List.copyOf(changed), List.copyOf(promises),
                    List.copyOf(sentences), List.copyOf(phrases), List.copyOf(conflicts), List.copyOf(against));
        }
    }

    /** One registered document in use, read on its own: its kind (ORG_POLICY · PRODUCT_KNOWLEDGE), title and text. */
    public record Rule(String kind, String title, String text) {
    }

    /**
     * A promise word one document in use says otherwise of, and that document's title. {@code clash}: the reply's own
     * ground says it the reply's way — the documents disagree with each other, and which one applies is the seller's.
     */
    public record Against(String word, String title, boolean clash) {
    }

    /** A document a version cited that is no longer as it was: edited after the version, or out of use. */
    public record Changed(String kind, String title, Instant changedAt, boolean removed) {}

    static final int MAX_SENTENCES = 3;
    static final int MAX_SENTENCE = 300;
    static final int MAX_CHANGED = 5;

    private final ReviewDraftComposer composer;
    private final ReviewReplyDraftRepository drafts;
    private final ReviewDraftEvidenceRepository evidence;
    private final ProductKnowledgeSourceRepository productSources;
    private final OrgKnowledgeSourceRepository orgSources;
    /** The company's forbidden phrases, as review replies obey them; null where a test wires the recheck without it. */
    private ReviewStyleGuard styleGuard;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setStyleGuard(ReviewStyleGuard styleGuard) {
        this.styleGuard = styleGuard;
    }

    /** The documents' passages as they stand; null where a test wires the recheck without them. */
    private com.sellerops.product.library.ProductKnowledgeChunkRepository productChunks;
    private com.sellerops.knowledge.org.OrgKnowledgeChunkRepository orgChunks;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setChunks(com.sellerops.product.library.ProductKnowledgeChunkRepository productChunks,
                          com.sellerops.knowledge.org.OrgKnowledgeChunkRepository orgChunks) {
        this.productChunks = productChunks;
        this.orgChunks = orgChunks;
    }

    public ReviewDraftRecheck(ReviewDraftComposer composer, ReviewReplyDraftRepository drafts,
                              ReviewDraftEvidenceRepository evidence, ProductKnowledgeSourceRepository productSources,
                              OrgKnowledgeSourceRepository orgSources) {
        this.composer = composer;
        this.drafts = drafts;
        this.evidence = evidence;
        this.productSources = productSources;
        this.orgSources = orgSources;
    }

    /**
     * @param redactedBody the review body after the preview sanitizer, as the composer reads it
     * @param text         the reply as it stands on the server
     */
    @Transactional(readOnly = true)
    public Result check(UUID orgId, Review review, String redactedBody, String text) {
        List<String> codes = new java.util.ArrayList<>();
        String body = ReviewReplyValidation.normalize(text);
        Optional<ReviewReplyDraft> written = drafts.findTopByReviewIdAndContentFingerprintOrderByVersionDesc(
                review.getId(), ReviewReplyFingerprint.of(body));
        List<Changed> changed = written.isPresent() ? changedCitations(orgId, review.getId(), written.get())
                : List.of();
        if (!changed.isEmpty()) {
            codes.add(SOURCE_CHANGED);
        }
        // A version written here from the bundle is read against what it cited, as it stands now — the same ground its
        // writer, its critique and its save used (2026-11-01 third review). Anything else — a seller's own text, a
        // version that cited nothing — against what the review's retrieval finds today.
        List<String> cited = written.map(v -> citedNow(orgId, review.getId(), v)).orElse(null);
        List<String> license = cited != null ? cited : composer.currentLicense(orgId, review, redactedBody);
        // The word and its terms: a period, an amount or a scope the current evidence no longer states is a promise
        // that lost its ground as surely as the word (2026-10-23 external review).
        List<String> promises = new java.util.ArrayList<>(ReviewClaimGuard.unsupportedClaims(body, license, redactedBody));
        ReviewClaimGuard.unsupportedConditions(body, license, redactedBody).stream()
                .filter(c -> !promises.contains(c)).forEach(promises::add);
        // And its yes or no: an edit that turns the policy's 「불가」 into 「가능」 keeps every word (2026-10-27 review).
        ReviewClaimGuard.contradictedPromises(body, license).stream()
                .filter(c -> !promises.contains(c)).forEach(promises::add);
        ReviewClaimGuard.undecidedPromises(body, license).stream()
                .filter(c -> !promises.contains(c)).forEach(promises::add);
        // Whether the policy may be applied to this case at all (2026-11-01).
        ReviewClaimGuard.lawConflicts(body, license, redactedBody).stream()
                .filter(c -> !promises.contains(c)).forEach(promises::add);
        // And what it leaves out: a grant without the state the policy limits it to (2026-10-31).
        ReviewClaimGuard.droppedConditions(body, license, redactedBody).stream()
                .filter(c -> !promises.contains(c)).forEach(promises::add);
        if (!promises.isEmpty()) {
            codes.add(UNSUPPORTED_PROMISE);
        }
        // Read against every policy in use, not only what the retrieval found: a policy the search missed has not said
        // nothing (2026-11-01 third review). Only what it found licenses a promise; any of them can contradict one.
        List<Rule> rules = rulesInUse(orgId, review.getProductId());
        List<String> conflicts = new java.util.ArrayList<>(conflictsAmong(promises, body,
                rules.stream().map(r -> r.title() + "\n" + r.text()).toList(), redactedBody));
        // …and independently of whether the cited ground found anything (fourth review): a reply that follows the
        // passage it cited can still contradict another document in use — 「교환 가능」 cited, 「교환 불가」 elsewhere.
        List<Against> against = otherRulesAgainst(body, license, rules, redactedBody);
        for (Against a : against) {
            if (!conflicts.contains(a.word())) {
                conflicts.add(a.word());
            }
            if (!promises.contains(a.word())) {
                promises.add(a.word());
            }
        }
        if (!promises.isEmpty() && !codes.contains(UNSUPPORTED_PROMISE)) {
            codes.add(UNSUPPORTED_PROMISE);
        }
        if (!conflicts.isEmpty()) {
            codes.add(POLICY_CONFLICT);
        }
        List<String> phrases = styleGuard == null ? List.of() : styleGuard.forbiddenIn(body, orgId);
        if (!phrases.isEmpty()) {
            codes.add(FORBIDDEN_PHRASE);
        }
        List<String> marked = new java.util.ArrayList<>(promises);
        marked.addAll(phrases);
        return Result.of(codes, changed, promises, sentencesWith(body, marked), phrases, conflicts, against);
    }

    /**
     * The promise words a policy contradicts rather than merely fails to back: the remedy answered the other way
     * ({@link ReviewClaimGuard#contradictedPromises}), granted without the state every grant of it is limited to
     * ({@link ReviewClaimGuard#droppedConditions}), or a term in a unit the policy states with another number — 「14일」
     * where it says 「7일」. Everything else among {@code promises} is unbacked: nothing registered says it either way.
     * Shared with the inquiry recheck, which reads the same evidence the same way.
     */
    public static List<String> conflictsAmong(List<String> promises, String body, List<String> license, String asked) {
        if (promises.isEmpty()) {
            return List.of();
        }
        java.util.Set<String> against = new java.util.LinkedHashSet<>(ReviewClaimGuard.contradictedPromises(body, license));
        against.addAll(ReviewClaimGuard.droppedConditions(body, license, asked));
        String grounds = String.join(" ", license == null ? List.of() : license).replace(",", "");
        List<String> out = new java.util.ArrayList<>();
        for (String p : promises) {
            if (ReviewClaimGuard.LAW_WITHDRAWAL.equals(p) || ReviewClaimGuard.LAW_FAULT.equals(p)) {
                continue;
            }
            if (against.contains(p) || statesOtherwise(grounds, p)) {
                out.add(p);
            }
        }
        return List.copyOf(out);
    }

    /** Every operating policy the org has in use, and every product document in use for {@code productId}. */
    private List<Rule> rulesInUse(UUID orgId, UUID productId) {
        List<Rule> all = new java.util.ArrayList<>();
        for (OrgKnowledgeSource s : orgSources.findAllByOrgIdOrderByCreatedAtAsc(orgId)) {
            if (s.isActive() && s.getBody() != null) {
                all.add(new Rule(InquiryDraftEvidence.KIND_ORG_POLICY, s.getTitle(), s.getBody()));
            }
        }
        if (productId != null) {
            for (ProductKnowledgeSource s : productSources.findAllByOrgIdAndProductIdOrderByCreatedAtAsc(orgId, productId)) {
                if (s.isActive() && s.getBody() != null) {
                    all.add(new Rule(InquiryDraftEvidence.KIND_PRODUCT_KNOWLEDGE, s.getTitle(), s.getBody()));
                }
            }
        }
        return all;
    }

    /**
     * Every promise word the reply states that one document in use, read on its own, says otherwise of: the remedy
     * answered the other way, granted without the state it limits every grant to, or a term in its unit with another
     * number. Run whatever the reply cited and whatever the license check found (2026-11-01 fourth review: the
     * conflict check was reached only through a finding of the license check).
     *
     * <p><b>No document wins by where it is kept</b> (fifth review, 2026-11-01). A product document is not a product's
     * exception to a store policy because it is filed under the product: it may be the listing's text, or an old note —
     * 「교환 관련 문의는 판매자에게」 does not grant an exchange. Until the seller can state a product's own policy, the
     * conditions it applies under and whether it is in force, every disagreement is held: if the reply's own ground says
     * it the reply's way, the two documents disagree with each other ({@link Against#clash}) and which one applies is
     * the seller's to say.
     */
    public static List<Against> otherRulesAgainst(String body, List<String> license, List<Rule> rules, String asked) {
        List<Against> out = new java.util.ArrayList<>();
        if (body == null || body.isBlank() || rules == null) {
            return out;
        }
        for (Rule rule : rules) {
            // The body alone: a title (「반품 · 교환 제한」) names a remedy without answering it, and would read as a second answer.
            List<String> one = List.of(rule.text());
            java.util.Set<String> words = new java.util.LinkedHashSet<>(ReviewClaimGuard.contradictedPromises(body, one));
            words.addAll(ReviewClaimGuard.droppedConditions(body, one, asked));
            String grounds = String.join(" ", one).replace(",", "");
            ReviewClaimGuard.unsupportedConditions(body, one, asked).stream()
                    .filter(t -> statesOtherwise(grounds, t)).forEach(words::add);
            for (String word : words) {
                if (ReviewClaimGuard.LAW_WITHDRAWAL.equals(word) || ReviewClaimGuard.LAW_FAULT.equals(word)) {
                    continue;
                }
                boolean clash = license != null && !license.isEmpty()
                        && ReviewClaimGuard.contradictedPromises(body, license).isEmpty()
                        && !ReviewClaimGuard.unsupportedClaims(body, license, asked).contains(word);
                if (out.stream().noneMatch(a -> a.word().equals(word) && a.title().equals(rule.title()))) {
                    out.add(new Against(word, rule.title() == null ? "" : rule.title(), clash));
                }
            }
        }
        return List.copyOf(out);
    }

    /** Whether {@code grounds} states {@code term}'s unit with another number: 「14일」 against 「수령 후 7일 이내」. */
    static boolean statesOtherwise(String grounds, String term) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d+(?:\\.\\d+)?)(\\D{1,4})$")
                .matcher(term.replace(",", ""));
        if (!m.matches()) {
            return false;
        }
        java.util.regex.Matcher other = java.util.regex.Pattern
                .compile("(?<![\\d.])(\\d+(?:\\.\\d+)?)\\s*" + java.util.regex.Pattern.quote(m.group(2)))
                .matcher(grounds);
        while (other.find()) {
            if (!other.group(1).equals(m.group(1))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The reply's sentences that carry one of {@code promises}, in order and at most {@value #MAX_SENTENCES} — each
     * cut to {@value #MAX_SENTENCE} characters from its start, so it stays a piece of the reply's own text.
     */
    public static List<String> sentencesWith(String body, List<String> promises) {
        List<String> out = new java.util.ArrayList<>();
        if (promises.isEmpty()) {
            return out;
        }
        for (String sentence : body.split("(?<=[.!?。])\\s+|\\n+")) {
            String squeezed = sentence.replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT);
            String trimmed = sentence.strip();
            if (!trimmed.isEmpty() && promises.stream().map(p -> p.replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT))
                    .anyMatch(squeezed::contains)) {
                out.add(trimmed.length() > MAX_SENTENCE ? trimmed.substring(0, MAX_SENTENCE) : trimmed);
                if (out.size() == MAX_SENTENCES) {
                    break;
                }
            }
        }
        return out;
    }

    /** The cited product and company documents that were removed, put out of use, or edited after {@code version}. */
    private List<Changed> changedCitations(UUID orgId, UUID reviewId, ReviewReplyDraft version) {
        List<Changed> out = new java.util.ArrayList<>();
        for (ReviewDraftEvidence row : evidence.findByOrgIdAndReviewIdAndDraftVersionOrderByOrdinalAsc(orgId, reviewId,
                version.getVersion())) {
            Optional<? extends BaseEntity> source;
            boolean active;
            if (InquiryDraftEvidence.KIND_PRODUCT_KNOWLEDGE.equals(row.getKind())) {
                var found = productSources.findByIdAndOrgId(row.getSourceId(), orgId);
                source = found;
                active = found.map(s -> s.isActive()).orElse(false);
            } else if (InquiryDraftEvidence.KIND_ORG_POLICY.equals(row.getKind())) {
                var found = orgSources.findByIdAndOrgId(row.getSourceId(), orgId);
                source = found;
                active = found.map(s -> s.isActive()).orElse(false);
            } else {
                continue;
            }
            boolean edited = source.isPresent() && source.get().getUpdatedAt().isAfter(version.getCreatedAt());
            if (source.isEmpty() || !active || (edited && !passageStands(row))) {
                if (out.size() < MAX_CHANGED) {
                    // A document deleted outright has no title left to show.
                    String title = source.map(ReviewDraftRecheck::title).orElse(null);
                    out.add(new Changed(row.getKind(), title == null ? "삭제된 문서" : title,
                            source.map(BaseEntity::getUpdatedAt).orElse(null), source.isEmpty() || !active));
                }
            }
        }
        return out;
    }

    /**
     * The text of every product or policy passage {@code version} cited that still stands word for word in a document in
     * use; null when it cited none (then it has no ground of its own to be read against).
     */
    private List<String> citedNow(UUID orgId, UUID reviewId, ReviewReplyDraft version) {
        if (productChunks == null || orgChunks == null) {
            return null;
        }
        List<String> out = new java.util.ArrayList<>();
        boolean any = false;
        for (ReviewDraftEvidence row : evidence.findByOrgIdAndReviewIdAndDraftVersionOrderByOrdinalAsc(orgId, reviewId,
                version.getVersion())) {
            boolean product = InquiryDraftEvidence.KIND_PRODUCT_KNOWLEDGE.equals(row.getKind());
            if (!product && !InquiryDraftEvidence.KIND_ORG_POLICY.equals(row.getKind()) || row.getSourceId() == null) {
                continue;
            }
            any = true;
            boolean active = product
                    ? productSources.findByIdAndOrgId(row.getSourceId(), orgId).map(ProductKnowledgeSource::isActive)
                            .orElse(false)
                    : orgSources.findByIdAndOrgId(row.getSourceId(), orgId).map(OrgKnowledgeSource::isActive)
                            .orElse(false);
            if (!active || row.getPassageFingerprint() == null) {
                continue;
            }
            java.util.stream.Stream<String> now = product
                    ? productChunks.findAllBySourceId(row.getSourceId()).stream()
                            .map(com.sellerops.product.library.ProductKnowledgeChunk::getContent)
                    : orgChunks.findAllBySourceId(row.getSourceId()).stream()
                            .map(com.sellerops.knowledge.org.OrgKnowledgeChunk::getContent);
            now.filter(t -> row.getPassageFingerprint().equals(ReviewDraftEvidence.fingerprintOf(t))).findFirst()
                    .ifPresent(out::add);
        }
        return any ? out : null;
    }

    /** Whether the passage {@code row} cited is still, word for word, one of its document's passages. */
    private boolean passageStands(ReviewDraftEvidence row) {
        if (row.getPassageFingerprint() == null || productChunks == null || orgChunks == null) {
            return false;
        }
        java.util.stream.Stream<String> now = InquiryDraftEvidence.KIND_PRODUCT_KNOWLEDGE.equals(row.getKind())
                ? productChunks.findAllBySourceId(row.getSourceId()).stream()
                        .map(com.sellerops.product.library.ProductKnowledgeChunk::getContent)
                : orgChunks.findAllBySourceId(row.getSourceId()).stream()
                        .map(com.sellerops.knowledge.org.OrgKnowledgeChunk::getContent);
        return now.map(ReviewDraftEvidence::fingerprintOf).anyMatch(row.getPassageFingerprint()::equals);
    }

    private static String title(BaseEntity source) {
        String title = source instanceof ProductKnowledgeSource p ? p.getTitle()
                : source instanceof OrgKnowledgeSource o ? o.getTitle() : null;
        if (title == null || title.isBlank()) {
            return null;
        }
        String clean = title.strip().replaceAll("\\p{Cntrl}", " ");
        return clean.length() > 120 ? clean.substring(0, 120) : clean;
    }
}
