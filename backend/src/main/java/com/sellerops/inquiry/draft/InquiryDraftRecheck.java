package com.sellerops.inquiry.draft;

import com.sellerops.inquiry.Inquiry;
import com.sellerops.inquiry.InquiryRepository;
import com.sellerops.inquiry.draft.dto.DraftEvidenceView;
import com.sellerops.inquiry.sellercheck.InquirySellerCheck;
import com.sellerops.inquiry.sellercheck.InquirySellerCheckRepository;
import com.sellerops.inquiry.sellercheck.SellerCheckGuard;
import com.sellerops.inquiry.workitem.InquiryWorkItem;
import com.sellerops.knowledge.org.OrgKnowledgeSource;
import com.sellerops.knowledge.org.OrgKnowledgeSourceRepository;
import com.sellerops.knowledge.style.AnswerStyleInstruction;
import com.sellerops.knowledge.style.AnswerStyleProfile;
import com.sellerops.knowledge.style.AnswerStyleService;
import com.sellerops.product.library.ProductKnowledgeSourceRepository;
import com.sellerops.review.draft.ReviewDraftRecheck;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>A waiting inquiry draft checked again against what the seller's knowledge says now</b> (2026-11-01 review: 웹 자동
 * 재검사). The inquiry twin of {@link ReviewDraftRecheck}, with its three codes and two of its own, and no model.
 *
 * <ol>
 *   <li>{@value ReviewDraftRecheck#SOURCE_CHANGED} — a policy or product passage the version cited cannot be read back:
 *       its document was edited (passages are re-cut on every edit) or removed.</li>
 *   <li>{@value ReviewDraftRecheck#UNSUPPORTED_PROMISE} — {@link InquiryClaimGuard} against the cited documents as they
 *       stand now and the company's current answer style: a remedy, condition, legal limit, follow-up or request of the
 *       customer the evidence no longer carries.</li>
 *   <li>{@value ReviewDraftRecheck#POLICY_CONFLICT} — with it, when a policy says otherwise of a promise rather than
 *       nothing ({@link ReviewDraftRecheck#conflictsAmong}).</li>
 *   <li>{@value ReviewDraftRecheck#FORBIDDEN_PHRASE} — a phrase the company's answer style now forbids.</li>
 *   <li>{@value #FIGURE_CHANGED} — a number whose ground no longer states it at all: the answer is now wrong
 *       ({@link InquiryFigureGrounds.Verdict#CHANGED}).</li>
 *   <li>{@value #FIGURE_UNVERIFIED} — a number nobody can say still holds: its clause was rewritten, its material cannot
 *       be read, it was typed with no ground, or it is an older draft's whose cited material moved
 *       ({@link InquiryFigureGrounds.Verdict#UNVERIFIED}).</li>
 *   <li>{@value #SELLER_CHECK_CHANGED} — the seller's record of what they found on the seller centre is not the one the
 *       version was written from: corrected since, written after the version, or changed while it was being drafted
 *       (2026-11-02 fourth seller-check review: 「현재 기록과 다른 기록을 근거로 한 초안은 승인·전송 전에 재확인」).
 *       What a version was written from is the record it names ({@code inquiry_reply_draft.seller_check_id}): the one
 *       the composer read before drafting, or the one the screen showed when the seller saved. Whoever wrote it, the
 *       same rule — a save time later than the record proves nothing (2026-11-03 fifth review: a draft left open on the
 *       PC and saved after the phone corrected the record).</li>
 *   <li>{@value #SELLER_CHECK_COMPLETION} — the reply calls the action finished and the current record does not
 *       ({@link SellerCheckGuard}). A finished fact is not something a decision can make true: the record is fixed
 *       first, or the sentence is.</li>
 * </ol>
 *
 * <p><b>Whoever wrote the version, the same check</b> (2026-11-01 review: an author is not a finding). A version with no
 * citations of its own — the seller's edit — is read against the citations of the version it was edited from, the
 * nearest earlier one that has them; a figure keeps the ground that version's claim check named only while it stands in
 * the same clause, and is unproven otherwise (third review: an edited claim does not inherit its check).
 */
@Service
public class InquiryDraftRecheck {

    public static final String FIGURE_CHANGED = "FIGURE_CHANGED";
    public static final String FIGURE_UNVERIFIED = "FIGURE_UNVERIFIED";
    public static final String SELLER_CHECK_CHANGED = "SELLER_CHECK_CHANGED";
    public static final String SELLER_CHECK_COMPLETION = "SELLER_CHECK_COMPLETION";
    /** A {@link FigureNote#source}: the figure was checked at generation in a clause the text no longer has. */
    public static final String EDITED = "EDITED";

    /**
     * One figure that did not stand: the number as the reply states it, whether it is now wrong or only unproven, and
     * the clause of the seller's material it was written from (null when there was none, or it was the order's).
     */
    public record FigureNote(String figure, String verdict, String was, String source) {
    }

    /**
     * What the seller's record says against the version: the outcome the version was written from ({@code was}, null
     * when it was written before any record), the outcome recorded now ({@code now}, null when none), and the finished
     * phrases the current record does not allow. Outcome codes ({@code DONE} …), never the seller's sentence.
     */
    public record SellerCheckNote(String was, String now, List<String> completions) {
        public SellerCheckNote {
            completions = completions == null ? List.of() : completions;
        }
    }

    /** What the check found; empty lists and no codes when the version still stands. */
    public record Result(List<String> codes, List<String> changed, List<String> promises, List<String> phrases,
                         List<FigureNote> figures, List<String> conflicts, List<ReviewDraftRecheck.Against> against,
                         SellerCheckNote sellerCheck) {
        public Result {
            conflicts = conflicts == null ? List.of() : conflicts;
            against = against == null ? List.of() : against;
        }

        public Result(List<String> codes, List<String> changed, List<String> promises, List<String> phrases,
                      List<FigureNote> figures, List<String> conflicts, List<ReviewDraftRecheck.Against> against) {
            this(codes, changed, promises, phrases, figures, conflicts, against, null);
        }

        public Result(List<String> codes, List<String> changed, List<String> promises, List<String> phrases,
                      List<FigureNote> figures, List<String> conflicts) {
            this(codes, changed, promises, phrases, figures, conflicts, List.of(), null);
        }

        public Result(List<String> codes, List<String> changed, List<String> promises, List<String> phrases,
                      List<FigureNote> figures) {
            this(codes, changed, promises, phrases, figures, List.of(), List.of(), null);
        }

        public Result(List<String> codes, List<String> changed, List<String> promises, List<String> phrases) {
            this(codes, changed, promises, phrases, List.of(), List.of(), List.of(), null);
        }

        public boolean passed() {
            return codes.isEmpty();
        }
    }

    private final InquiryRepository inquiries;
    private final InquiryDraftEvidenceRepository evidence;
    private final DraftEvidenceSnippets snippets;
    private final OrgKnowledgeSourceRepository orgSources;
    private final ProductKnowledgeSourceRepository productSources;
    private final AnswerStyleService styles;
    /** The figures' kept grounds; null where a test wires the recheck without them (figures are then not read). */
    private InquiryFigureGrounds grounds;
    /** The seller's records; null where a test wires the recheck without them (records are then not read). */
    private InquirySellerCheckRepository sellerChecks;

    public InquiryDraftRecheck(InquiryRepository inquiries, InquiryDraftEvidenceRepository evidence,
                               DraftEvidenceSnippets snippets, OrgKnowledgeSourceRepository orgSources,
                               ProductKnowledgeSourceRepository productSources, AnswerStyleService styles) {
        this.inquiries = inquiries;
        this.evidence = evidence;
        this.snippets = snippets;
        this.orgSources = orgSources;
        this.productSources = productSources;
        this.styles = styles;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setGrounds(InquiryFigureGrounds grounds) {
        this.grounds = grounds;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setSellerChecks(InquirySellerCheckRepository sellerChecks) {
        this.sellerChecks = sellerChecks;
    }

    @Transactional(readOnly = true)
    public Result check(InquiryWorkItem item, int version, String text) {
        return check(item, version, text, null);
    }

    @Transactional(readOnly = true)
    public Result check(InquiryWorkItem item, int version, String text, String authorKind) {
        return check(item, version, text, authorKind, null);
    }

    /**
     * The seller record a version was written from: the one it names, or — for a model version written before versions
     * named one — the one it cites. Null when its writer had none in front of them.
     */
    @Transactional(readOnly = true)
    public UUID writtenFrom(com.sellerops.inquiry.reply.InquiryReplyDraft v) {
        if (v == null) {
            return null;
        }
        if (v.getSellerCheckId() != null) {
            return v.getSellerCheckId();
        }
        if (!DraftAuthorKind.MODEL.name().equals(v.getAuthorKind())) {
            return null;
        }
        return evidence.findAllByWorkItemIdAndDraftVersionOrderByOrdinalAsc(v.getWorkItemId(), v.getVersion()).stream()
                .filter(r -> InquiryDraftEvidence.KIND_SELLER_CHECK.equals(r.getKind()))
                .map(InquiryDraftEvidence::getSourceId).filter(java.util.Objects::nonNull).findFirst().orElse(null);
    }

    /**
     * @param authorKind the version's author ({@code MODEL}, {@code SELLER}, …) — read only to tell a model draft written
     *                   before figure grounds were kept from a figure typed by hand, never to skip a check
     */
    @Transactional(readOnly = true)
    public Result check(InquiryWorkItem item, int version, String text, String authorKind, UUID writtenFrom) {
        int cites = version;
        List<InquiryDraftEvidence> cited =
                evidence.findAllByWorkItemIdAndDraftVersionOrderByOrdinalAsc(item.getId(), version);
        // A version with no citations of its own was edited from one that has them.
        while (cited.isEmpty() && cites > 1) {
            cites--;
            cited = evidence.findAllByWorkItemIdAndDraftVersionOrderByOrdinalAsc(item.getId(), cites);
        }
        Set<String> changed = new LinkedHashSet<>();
        List<String> current = new ArrayList<>();
        for (InquiryDraftEvidence row : cited) {
            DraftEvidenceView view = snippets.viewOf(row);
            if (view.gone()) {
                changed.add(row.getTitle() == null ? "" : row.getTitle());
            }
            if (row.getSourceId() == null) {
                continue;
            }
            if (InquiryDraftEvidence.KIND_ORG_POLICY.equals(row.getKind())) {
                orgSources.findById(row.getSourceId()).filter(OrgKnowledgeSource::isActive)
                        .ifPresentOrElse(s -> current.add(s.getTitle() + "\n" + s.getBody()),
                                () -> changed.add(row.getTitle() == null ? "" : row.getTitle()));
            } else if (InquiryDraftEvidence.KIND_PRODUCT_KNOWLEDGE.equals(row.getKind())) {
                productSources.findById(row.getSourceId())
                        .ifPresentOrElse(s -> current.add(s.getTitle() + "\n" + s.getBody()),
                                () -> changed.add(row.getTitle() == null ? "" : row.getTitle()));
            }
        }
        // The seller's record the version stands on is evidence like a cited passage: a refund the record says was done is
        // backed, not an unbacked promise. A record the version does not stand on backs nothing.
        List<String> sellerCodes = new ArrayList<>();
        RecordState record = sellerChecks == null ? null
                : sellerCheck(item, version, cites, cited, text, authorKind, writtenFrom, sellerCodes);
        if (record != null && record.standing() != null) {
            current.add(com.sellerops.inquiry.sellercheck.InquirySellerCheckService.evidenceText(record.standing()));
        }
        AnswerStyleProfile style = styles.profileFor(item.getOrgId());
        List<String> voice = new ArrayList<>(style.requiredPhrases());
        if (style.hasUnknownFallback()) {
            voice.add(style.unknownFallback());
        }
        Inquiry inquiry = inquiries.findById(item.getInquiryId()).orElse(null);
        String asked = inquiry == null ? "" : String.join(" ",
                inquiry.getTitle() == null ? "" : inquiry.getTitle(), inquiry.getBody() == null ? "" : inquiry.getBody());
        // A figure is the figure check's below, which knows what each one was written from.
        List<String> promises = InquiryClaimGuard.unsupportedClaims(text, current, voice, asked).stream()
                .filter(v -> v.kind() != InquiryClaimGuard.Kind.FIGURE)
                .map(InquiryClaimGuard.Violation::matched).distinct().toList();
        List<String> phrases = AnswerStyleInstruction.forbiddenPresentIn(text, style);
        List<FigureNote> figures = grounds == null ? List.of()
                : figures(item, version, cites, text, authorKind, !changed.isEmpty());
        List<String> codes = new ArrayList<>();
        if (!changed.isEmpty()) {
            codes.add(ReviewDraftRecheck.SOURCE_CHANGED);
        }
        if (!promises.isEmpty()) {
            codes.add(ReviewDraftRecheck.UNSUPPORTED_PROMISE);
        }
        // Which of them a policy says otherwise, read as the review recheck reads it (2026-11-01: 근거 없음 ≠ 정책 충돌).
        // Against every document in use as well as the cited ones: a policy the draft did not cite has not said nothing —
        // and read on its own, whatever the cited ones found (2026-11-01 fourth review), as the review recheck reads it.
        List<ReviewDraftRecheck.Rule> rules = new ArrayList<>();
        orgSources.findAllByOrgIdOrderByCreatedAtAsc(item.getOrgId()).stream()
                .filter(s -> s.isActive() && s.getBody() != null)
                .forEach(s -> rules.add(new ReviewDraftRecheck.Rule(InquiryDraftEvidence.KIND_ORG_POLICY, s.getTitle(),
                        s.getBody())));
        if (inquiry != null && inquiry.getProductId() != null) {
            productSources.findAllByOrgIdAndProductIdOrderByCreatedAtAsc(item.getOrgId(), inquiry.getProductId()).stream()
                    .filter(s -> s.isActive() && s.getBody() != null)
                    .forEach(s -> rules.add(new ReviewDraftRecheck.Rule(InquiryDraftEvidence.KIND_PRODUCT_KNOWLEDGE,
                            s.getTitle(), s.getBody())));
        }
        List<String> everyRule = new ArrayList<>(current);
        rules.forEach(r -> everyRule.add(r.title() + "\n" + r.text()));
        List<String> conflicts = new ArrayList<>(ReviewDraftRecheck.conflictsAmong(promises, text, everyRule, asked));
        List<ReviewDraftRecheck.Against> against = ReviewDraftRecheck.otherRulesAgainst(text, current, rules, asked);
        List<String> allPromises = new ArrayList<>(promises);
        for (ReviewDraftRecheck.Against a : against) {
            if (!conflicts.contains(a.word())) {
                conflicts.add(a.word());
            }
            if (!allPromises.contains(a.word())) {
                allPromises.add(a.word());
            }
        }
        if (!allPromises.isEmpty() && !codes.contains(ReviewDraftRecheck.UNSUPPORTED_PROMISE)) {
            codes.add(ReviewDraftRecheck.UNSUPPORTED_PROMISE);
        }
        promises = List.copyOf(allPromises);
        if (!conflicts.isEmpty()) {
            codes.add(ReviewDraftRecheck.POLICY_CONFLICT);
        }
        if (!phrases.isEmpty()) {
            codes.add(ReviewDraftRecheck.FORBIDDEN_PHRASE);
        }
        if (figures.stream().anyMatch(f -> FIGURE_CHANGED.equals(f.verdict()))) {
            codes.add(FIGURE_CHANGED);
        }
        if (figures.stream().anyMatch(f -> FIGURE_UNVERIFIED.equals(f.verdict()))) {
            codes.add(FIGURE_UNVERIFIED);
        }
        codes.addAll(sellerCodes);
        return new Result(List.copyOf(codes), List.copyOf(changed), promises, List.copyOf(phrases), figures,
                List.copyOf(conflicts), against, record == null ? null : record.note());
    }

    /**
     * The seller's record against the version, or null when neither has one. The version stands on the record it was
     * written from only while that record is the current one — whoever wrote it. Whatever the version stands on, a
     * finished action the current record does not say finished is refused.
     */
    /** The note, and the current record when the version stands on it (null otherwise). */
    private record RecordState(SellerCheckNote note, InquirySellerCheck standing) {
    }

    private RecordState sellerCheck(InquiryWorkItem item, int version, int cites, List<InquiryDraftEvidence> cited,
                                    String text, String authorKind, UUID writtenFrom, List<String> codes) {
        InquirySellerCheck current = sellerChecks
                .findFirstByOrgIdAndWorkItemIdAndSupersededAtIsNullOrderByRecordedAtDesc(item.getOrgId(), item.getId())
                .orElse(null);
        // A model version from before versions named their record: the record it cites on its own citations.
        UUID citedId = writtenFrom != null ? writtenFrom
                : DraftAuthorKind.MODEL.name().equals(authorKind) && cites == version
                ? cited.stream().filter(r -> InquiryDraftEvidence.KIND_SELLER_CHECK.equals(r.getKind()))
                        .map(InquiryDraftEvidence::getSourceId).filter(java.util.Objects::nonNull).findFirst()
                        .orElse(null)
                : null;
        if (current == null && citedId == null) {
            return null;
        }
        String was = citedId == null ? null : sellerChecks.findById(citedId)
                .filter(c -> item.getOrgId().equals(c.getOrgId())).map(InquirySellerCheck::getOutcome).orElse(null);
        boolean stands = current != null && current.getId().equals(citedId);
        List<String> completions = current == null ? List.of()
                : SellerCheckGuard.unrecordedCompletions(text, current.outcomeValue());
        if (!stands) {
            codes.add(SELLER_CHECK_CHANGED);
        }
        if (!completions.isEmpty()) {
            codes.add(SELLER_CHECK_COMPLETION);
        }
        return new RecordState(new SellerCheckNote(was, current == null ? null : current.getOutcome(), completions),
                stands ? current : null);
    }

    /**
     * Every figure the text states that does not stand. A figure is read against a kept ground only while it is still in
     * the very clause the ground was kept for (2026-11-01 third review: 「판매자가 해당 주장을 수정하면 기존 검증을 그대로
     * 물려주지 않아야 합니다」) — a customer's 「20가닥」 said back is not a ground for 「이 상품은 20가닥으로 구성」. Then the
     * ground decides ({@link InquiryFigureGrounds#verdict}); an order figure or a question said back stands as long as
     * its clause does. A figure in a clause nothing was kept for — rewritten, or typed by hand — is unproven: whether it
     * still means what was checked is a reading of meaning. A model draft from before grounds were kept stands only while
     * nothing it cited moved.
     */
    private List<FigureNote> figures(InquiryWorkItem item, int version, int cites, String text, String authorKind,
                                     boolean citedMoved) {
        List<InquiryDraftFigure> kept = grounds.of(item.getId(), version);
        if (kept.isEmpty() && cites != version) {
            kept = grounds.of(item.getId(), cites);
        }
        boolean legacy = kept.isEmpty() && DraftAuthorKind.MODEL.name().equals(authorKind);
        String said = squash(text);
        List<FigureNote> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (InquiryClaimGuard.Figure f : InquiryClaimGuard.statedFigures(text)) {
            String figure = f.value() + (f.unit() == null ? "" : f.unit());
            if (!seen.add(figure)) {
                continue;
            }
            List<InquiryDraftFigure> same = kept.stream().filter(k -> InquiryClaimGuard.sameQuantity(
                    new InquiryClaimGuard.Figure(k.getValue(), k.getUnit(), k.getReplyClause()), f)).toList();
            InquiryDraftFigure ground = same.stream()
                    .filter(k -> k.getReplyClause() != null && !squash(k.getReplyClause()).isEmpty()
                            && said.contains(squash(k.getReplyClause())))
                    .findFirst().orElse(null);
            InquiryFigureGrounds.Verdict verdict;
            String source;
            if (ground != null) {
                InquiryDraftFigure.Ground kind = InquiryDraftFigure.Ground.valueOf(ground.getGroundKind());
                verdict = InquiryFigureGrounds.verdict(ground, grounds.currentText(item.getOrgId(), kind,
                        ground.getSourceId(), ground.getProductId()));
                source = ground.getGroundKind();
            } else if (legacy) {
                verdict = citedMoved ? InquiryFigureGrounds.Verdict.UNVERIFIED : InquiryFigureGrounds.Verdict.STANDS;
                source = null;
            } else {
                verdict = InquiryFigureGrounds.Verdict.UNVERIFIED;
                source = same.isEmpty() ? null : EDITED;
            }
            if (verdict != InquiryFigureGrounds.Verdict.STANDS) {
                InquiryDraftFigure shown = ground != null ? ground : same.isEmpty() ? null : same.get(0);
                out.add(new FigureNote(figure,
                        verdict == InquiryFigureGrounds.Verdict.CHANGED ? FIGURE_CHANGED : FIGURE_UNVERIFIED,
                        shown == null ? null : shown.getGroundClause(), source));
            }
        }
        return List.copyOf(out);
    }

    private static String squash(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "");
    }
}
