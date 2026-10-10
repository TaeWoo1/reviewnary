package com.sellerops.opportunity;

import com.sellerops.common.ApiException;
import com.sellerops.opportunity.dto.OpportunityDraftRequest;
import com.sellerops.opportunity.dto.OpportunityOutcomeView;
import com.sellerops.opportunity.dto.OpportunityDraftView;
import com.sellerops.opportunity.dto.OpportunityEventView;
import com.sellerops.opportunity.dto.OpportunityKnowledgeView;
import com.sellerops.opportunity.dto.OpportunityView;
import com.sellerops.product.ProductSignalsService;
import com.sellerops.reviewissue.ReviewIssueQueryService;
import com.sellerops.reviewissue.dto.ReviewIssueView;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Derives improvement opportunities from the issue memory and joins the seller's decisions.
 *
 * <p><b>Derived on every read, never stored.</b> The issue list is the candidate gate — an opportunity
 * exists only for an issue the extractor holds with enough evidence ({@link OpportunityRules#qualifies}),
 * so "evidence 없는 제안" is unreachable by construction: every view carries the issue id its evidence
 * lives under. The knowledge check is a deterministic mention count over the seller's active sources.
 * No model is called anywhere in this package.
 *
 * <p><b>Writes are the seller's decisions, and only those.</b> Accept prepares a draft (a scaffold of
 * facts and the seller's own sentences) and remembers it; dismiss remembers the "not now"; restore
 * puts the opportunity back to open. The draft's destination — the knowledge library, or the seller's
 * clipboard — is outside this package, through seams that already exist and already require the
 * seller's hand.
 *
 * <p><b>And every one of those writes is also appended to a trail.</b> Until V103 the decision was a
 * single mutable row: dismiss erased the seller's edited text, restore deleted the row, and the whole
 * sequence 채택 → 수정 → 보류 → 되돌림 left nothing behind. Two rules hold now and both are asserted:
 * the seller's prepared text is never destroyed by a decision (a dismissal hides it, the view does
 * that), and every decision appends one {@link OpportunityDecisionEvent} naming the status it left.
 * The trail is append-only — nothing in this class updates or deletes one.
 */
@Service
public class OpportunityService {

    static final int TITLE_MAX = 200;
    static final int BODY_MAX = 4000;

    private final ReviewIssueQueryService issues;
    private final ProductSignalsService productSignals;
    private final KnowledgeMentionCheck knowledge;
    private final ImprovementOpportunityRepository decisions;
    private final OpportunityDecisionEventRepository trail;

    public OpportunityService(ReviewIssueQueryService issues, ProductSignalsService productSignals,
                              KnowledgeMentionCheck knowledge, ImprovementOpportunityRepository decisions,
                              OpportunityDecisionEventRepository trail) {
        this.issues = issues;
        this.productSignals = productSignals;
        this.knowledge = knowledge;
        this.decisions = decisions;
        this.trail = trail;
    }

    /**
     * The two collaborators 적용 needs, optional so the five-argument constructor two unit tests already use is
     * untouched.
     *
     * <p>Optional rather than required is also the honest shape: {@link #list}, {@link #accept},
     * {@link #dismiss}, {@link #restore} and {@link #updateDraft} were all complete without them and still are.
     * A context that wires neither is the product before this package existed, and it behaves exactly as it did.
     */
    private ImprovementOutcomeService outcomes;
    private com.sellerops.reviewissue.ReviewIssueLifecycleService lifecycle;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setOutcomeLane(ImprovementOutcomeService outcomes,
                        com.sellerops.reviewissue.ReviewIssueLifecycleService lifecycle) {
        this.outcomes = outcomes;
        this.lifecycle = lifecycle;
    }

    /** Settle every closed window. 0 when the outcome lane is not wired, which is the product before it existed. */
    @Transactional
    public int readOutcomes(UUID orgId, LocalDate referenceDate) {
        return outcomes == null ? 0 : outcomes.read(orgId, referenceDate);
    }

    /** The anchored results of this issue's applied improvements, newest first. Empty when nothing was applied. */
    @Transactional(readOnly = true)
    public List<OpportunityOutcomeView> outcomes(UUID orgId, UUID issueId) {
        if (outcomes == null) {
            return List.of();
        }
        List<ImprovementOutcome> rows = outcomes.forIssue(orgId, issueId);
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<UUID, OpportunityKind> kinds = new HashMap<>();
        for (ImprovementOpportunity row : decisions.findByOrgIdAndIssueIdIn(orgId, List.of(issueId))) {
            kinds.put(row.getId(), row.getKind());
        }
        return rows.stream()
                .map(row -> OpportunityOutcomeViews.of(row, kinds.get(row.getOpportunityId())))
                .toList();
    }

    /**
     * Every opportunity for the org, or for one product, or for one issue — open and accepted, plus the
     * dismissed ones when asked. Ordered as the issue list is (severity, change, recency), guidance
     * before product review within an issue.
     */
    @Transactional(readOnly = true)
    public List<OpportunityView> list(UUID orgId, LocalDate referenceDate, UUID productId, UUID issueId,
                                      boolean includeDismissed) {
        List<ReviewIssueView> candidates;
        if (issueId != null) {
            candidates = List.of(issues.issueView(orgId, issueId, referenceDate));
        } else if (productId != null) {
            candidates = productSignals.issuesFor(orgId, productId, referenceDate);
        } else {
            candidates = issues.list(orgId, referenceDate);
        }
        List<ReviewIssueView> qualifying = candidates.stream().filter(OpportunityRules::qualifies).toList();
        Map<String, ImprovementOpportunity> decided = new HashMap<>();
        if (!qualifying.isEmpty()) {
            for (ImprovementOpportunity row : decisions.findByOrgIdAndIssueIdIn(orgId,
                    qualifying.stream().map(ReviewIssueView::id).toList())) {
                decided.put(key(row.getIssueId(), row.getKind()), row);
            }
        }
        // One query for every trail on the page. Loading it per card would be a read per opportunity
        // on a screen that already costs one per issue; loading it lazily would let a card render a
        // decision badge above an empty history for as long as the second request took.
        Map<UUID, List<OpportunityEventView>> history = historyOf(orgId, decided.values());
        // One read for every result on the page, for the same reason the trail is batched: an outcome per card
        // would be a query per card on a screen that already costs one per issue.
        Map<UUID, ImprovementOutcome> results = outcomes == null || decided.isEmpty() ? Map.of()
                : outcomes.byOpportunity(orgId, decided.values().stream()
                        .map(ImprovementOpportunity::getId).toList());
        List<OpportunityView> out = new ArrayList<>();
        for (ReviewIssueView issue : qualifying) {
            for (Derived d : derive(orgId, issue)) {
                ImprovementOpportunity row = decided.get(key(issue.id(), d.candidate().kind()));
                if (row != null && row.getStatus() == OpportunityStatus.DISMISSED && !includeDismissed) {
                    continue;
                }
                out.add(view(issue, d, row, row == null ? List.of()
                        : history.getOrDefault(row.getId(), List.of()), standingResult(results, row)));
            }
        }
        return List.copyOf(out);
    }

    /**
     * The opportunities whose draft is prepared and still derivable — what the Operations Home may
     * call 준비된 작업.
     *
     * <p><b>The decided rows are the gate, not the issue list.</b> Only a row the seller accepted can
     * be prepared work, and those are few; deriving over every issue first would make the Home pay for
     * the whole issue memory to find them. An org that has accepted nothing pays one indexed query.
     *
     * <p><b>Re-derived before it is counted.</b> An opportunity whose issue was since resolved,
     * dismissed, or fell under the repeat threshold is not prepared work — its row is inert, and a
     * Home that counted it would be asking for work the workspace no longer offers.
     */
    @Transactional(readOnly = true)
    public List<PreparedDraft> preparedDrafts(UUID orgId, LocalDate referenceDate) {
        List<ImprovementOpportunity> accepted = decisions.findByOrgIdAndStatus(orgId, OpportunityStatus.ACCEPTED)
                .stream().filter(r -> r.getDraftBody() != null && !r.getDraftBody().isBlank()).toList();
        if (accepted.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<OpportunityEventView>> history = historyOf(orgId, accepted);
        List<PreparedDraft> out = new ArrayList<>();
        for (ImprovementOpportunity row : accepted) {
            ReviewIssueView issue;
            try {
                issue = issues.issueView(orgId, row.getIssueId(), referenceDate);
            } catch (ApiException e) {
                continue;
            }
            derive(orgId, issue).stream()
                    .filter(d -> d.candidate().kind() == row.getKind())
                    .findFirst()
                    .ifPresent(d -> out.add(new PreparedDraft(row.getId(), view(issue, d, row,
                            history.getOrDefault(row.getId(), List.of()), null))));
        }
        return List.copyOf(out);
    }

    /**
     * A prepared draft and the id of the DECISION behind it.
     *
     * <p>The opportunity itself still has no id — it is derived, and {@code (issueId, kind)} names it
     * everywhere a client does. The decision row does have one, and a caller that must tell two
     * prepared drafts on the same repeated problem apart needs exactly that and nothing more. It is
     * not an address: no route takes it.
     */
    public record PreparedDraft(UUID decisionId, OpportunityView opportunity) {
    }

    private Map<UUID, List<OpportunityEventView>> historyOf(UUID orgId,
                                                            Collection<ImprovementOpportunity> rows) {
        if (rows.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<OpportunityEventView>> out = new HashMap<>();
        for (OpportunityDecisionEvent e : trail.findByOrgIdAndOpportunityIdInOrderByDecidedAtAsc(
                orgId, rows.stream().map(ImprovementOpportunity::getId).toList())) {
            out.computeIfAbsent(e.getOpportunityId(), k -> new ArrayList<>()).add(eventView(e));
        }
        return out;
    }

    private static OpportunityEventView eventView(OpportunityDecisionEvent e) {
        return new OpportunityEventView(e.getEvent().name(), e.getEvent().labelKo(),
                e.getStatusFrom() == null ? null : e.getStatusFrom().name(),
                e.getStatusTo().name(), e.getEvidenceCount(), e.getDecidedAt());
    }

    /**
     * Accept: prepare the draft and remember it. Idempotent — accepting an already-accepted
     * opportunity keeps the seller's edits and appends nothing, because nothing was decided.
     */
    @Transactional
    public OpportunityView accept(UUID orgId, UUID actorId, UUID issueId, OpportunityKind kind,
                                  LocalDate referenceDate) {
        ReviewIssueView issue = issues.issueView(orgId, issueId, referenceDate);
        Derived d = requireDerived(orgId, issue, kind);
        ImprovementOpportunity row = locked(orgId, issueId, kind);
        OpportunityStatus from = statusOf(row);
        if (row == null) {
            row = newRow(orgId, issueId, kind);
        }
        // The composer only writes where the seller has not. A draft already on the row is either the
        // scaffold or the seller's rewrite of it, and this path cannot tell them apart — so it keeps
        // whichever it finds rather than replacing sentences somebody may have written by hand.
        if (row.getDraftBody() == null || row.getDraftBody().isBlank()) {
            OpportunityDraftComposer.Draft draft = OpportunityDraftComposer.draft(issue, d.candidate(), d.mention());
            row.setDraftTitle(draft.title());
            row.setDraftBody(draft.body());
        }
        return settle(orgId, actorId, issue, d, row, from, OpportunityStatus.ACCEPTED,
                OpportunityEvent.ACCEPTED);
    }

    /**
     * Dismiss: "not now".
     *
     * <p><b>The prepared text is not erased.</b> A dismissed opportunity has no prepared action and
     * the view says so by not carrying one — but 「지금은 보류」 is a decision about the suggestion, not
     * a request to delete sentences the seller wrote. Until V103 this nulled both draft columns, so a
     * seller who edited a draft and then deferred it lost the edit with no record that it had existed.
     */
    @Transactional
    public OpportunityView dismiss(UUID orgId, UUID actorId, UUID issueId, OpportunityKind kind,
                                   LocalDate referenceDate) {
        ReviewIssueView issue = issues.issueView(orgId, issueId, referenceDate);
        Derived d = requireDerived(orgId, issue, kind);
        ImprovementOpportunity row = locked(orgId, issueId, kind);
        OpportunityStatus from = statusOf(row);
        if (row == null) {
            row = newRow(orgId, issueId, kind);
        }
        return settle(orgId, actorId, issue, d, row, from, OpportunityStatus.DISMISSED,
                OpportunityEvent.DISMISSED);
    }

    /**
     * Restore: back to open.
     *
     * <p><b>The row is not deleted.</b> Deleting it would take the trail beneath it with it (the
     * events cascade off this row), which is the one thing this package must not be able to do: a
     * seller could then erase their own decision history by pressing 되돌리기. The row stays at
     * {@code OPEN}, which is what an absent row has always meant to every reader.
     */
    @Transactional
    public OpportunityView restore(UUID orgId, UUID actorId, UUID issueId, OpportunityKind kind,
                                   LocalDate referenceDate) {
        ReviewIssueView issue = issues.issueView(orgId, issueId, referenceDate);
        Derived d = requireDerived(orgId, issue, kind);
        ImprovementOpportunity row = locked(orgId, issueId, kind);
        if (row == null) {
            // Nothing was decided, so nothing is taken back and nothing is appended.
            return view(issue, d, null, List.of(), null);
        }
        return settle(orgId, actorId, issue, d, row, row.getStatus(), OpportunityStatus.OPEN,
                OpportunityEvent.REOPENED);
    }

    /**
     * <b>적용 — the seller carried it out.</b> The step the chain was missing.
     *
     * <p>Until now the draft went into the seller's library through two frontend calls
     * ({@code createOrgKnowledge} / {@code createProductKnowledgeSource}) and this row never learned it, so
     * «채택했다» and «실제로 했다» were one word and «did it work» had no first date to measure from. This records
     * the act, freezes the measurement's premise, and — when the application was itself a change in the seller's
     * own records — records the remediation on the problem too.
     *
     * <p><b>It does not re-derive.</b> Every other mutation here checks that the rules still yield this kind;
     * this one deliberately checks the stored row instead, because saving the draft into the product's library
     * is exactly what flips {@code aspectMentioned} and therefore flips {@code FAQ_SUPPLEMENT} to
     * {@code PRODUCT_GUIDE_SUPPLEMENT}. A re-derivation here would refuse the application of the very draft that
     * had just succeeded. The gate is {@code ACCEPTED}: you can only carry out what was prepared.
     *
     * <p><b>Idempotent.</b> Applying twice appends nothing and re-anchors nothing — {@link #settle} suppresses a
     * no-change decision, and {@code ImprovementOutcomeService.anchor} returns the standing anchor rather than
     * measuring a baseline against a later day.
     *
     * @param artifact  what it landed in. {@code SELLER_DECLARED} is the seller's own word, recorded as that and
     *                  not dressed up as a saved document
     * @param appliedRef the id of that artifact, or null
     */
    @Transactional
    public Applied apply(UUID orgId, UUID actorId, UUID issueId, OpportunityKind kind, LocalDate referenceDate,
                         AppliedArtifact artifact, UUID appliedRef) {
        if (artifact == null) {
            throw ApiException.badRequest("무엇으로 적용했는지 알려 주세요.");
        }
        ReviewIssueView issue = issues.issueView(orgId, issueId, referenceDate);
        ImprovementOpportunity row = decisions.findWithLockByOrgIdAndIssueIdAndKind(orgId, issueId, kind)
                .filter(r -> r.getStatus() == OpportunityStatus.ACCEPTED
                        || r.getStatus() == OpportunityStatus.APPLIED)
                .orElseThrow(() -> ApiException.conflict("초안이 준비된 기회에서만 적용을 기록할 수 있습니다."));
        OpportunityRules.Scope scope = scopeOf(artifact, issue);
        UUID productId = scope == OpportunityRules.Scope.PRODUCT ? issue.dominantProductId() : null;
        if (scope == OpportunityRules.Scope.PRODUCT && productId == null) {
            // No silent widening: an application the product cannot bind to a product is not recorded as one
            // that covers the whole company. Same refusal shape as SellerOperationsPolicyService.
            throw ApiException.badRequest("이 문제는 상품이 특정되지 않아 상품 적용으로 기록할 수 없습니다.");
        }

        OpportunityStatus from = row.getStatus();
        boolean first = from != OpportunityStatus.APPLIED;
        if (first) {
            row.setAppliedAt(Instant.now());
            row.setAppliedBy(actorId);
            row.setAppliedRef(appliedRef);
            row.setAppliedRefKind(artifact);
        }
        // `from` is the status actually left, read under the lock. Passing ACCEPTED unconditionally would make a
        // second press look like a decision (ACCEPTED → APPLIED) and append a second trail row for one act.
        settle(orgId, actorId, issue, null, row, from, OpportunityStatus.APPLIED, OpportunityEvent.APPLIED);

        // <b>The window is anchored on the reference date, not on the clock.</b> `referenceDate` is the
        // parameter every other route in this controller already takes, and it is what lets a demo or a test
        // place an application on a day other than today; `appliedAt` above records the instant of the press.
        // Only the first application's date is kept — `anchor` returns the standing anchor unchanged, so a
        // second press cannot re-measure a baseline against a later day.
        ImprovementOutcome outcome = outcomes == null ? null
                : outcomes.anchor(orgId, row.getId(), issueId, scope, productId, referenceDate);
        // A change standing in the seller's own records IS the remediation; their word that they acted on a memo
        // is not, so only the first moves the problem. Either way the application above is recorded.
        boolean remediationRecorded = first && artifact.isRecordedChange() && lifecycle != null
                && lifecycle.recordRemediation(orgId, issueId, appliedNote(row, artifact));
        return new Applied(issueId, kind.name(), kind.labelKo(), OpportunityStatus.APPLIED.name(),
                OpportunityStatus.APPLIED.labelKo(), row.getAppliedAt(), artifact.name(), artifact.labelKo(),
                remediationRecorded, outcome == null ? null : OpportunityOutcomeViews.of(outcome, kind));
    }

    /** What one application recorded, and what it started watching. */
    public record Applied(UUID issueId, String kind, String kindLabelKo, String status, String statusLabelKo,
                          Instant appliedAt, String artifact, String artifactLabelKo,
                          boolean remediationRecorded, OpportunityOutcomeView outcome) {
    }

    /**
     * The population the result will be counted in.
     *
     * <p>Read off the artifact where the artifact says it — a company rule reaches the company, a product source
     * reaches that product — and off the issue only for {@code SELLER_DECLARED}, where there is no artifact to
     * ask. An issue whose evidence resolves to no product can only be an ORG measurement; that is the same fence
     * {@code SellerPolicyOverlay.applies} and {@code KnowledgeSpineScope} hold.
     */
    private static OpportunityRules.Scope scopeOf(AppliedArtifact artifact, ReviewIssueView issue) {
        return switch (artifact) {
            case ORG_KNOWLEDGE -> OpportunityRules.Scope.ORG;
            case PRODUCT_KNOWLEDGE -> OpportunityRules.Scope.PRODUCT;
            case SELLER_DECLARED -> issue.dominantProductId() == null
                    ? OpportunityRules.Scope.ORG : OpportunityRules.Scope.PRODUCT;
        };
    }

    /** The note the lifecycle trail carries. Operator-facing, derived from closed vocabulary only. */
    private static String appliedNote(ImprovementOpportunity row, AppliedArtifact artifact) {
        return row.getKind().labelKo() + "을 " + artifact.labelKo() + "으로 적용했습니다.";
    }

    /** The seller's edit of a prepared draft. Only an ACCEPTED opportunity has one to edit. */
    @Transactional
    public OpportunityView updateDraft(UUID orgId, UUID actorId, UUID issueId, OpportunityKind kind,
                                       LocalDate referenceDate, OpportunityDraftRequest request) {
        ReviewIssueView issue = issues.issueView(orgId, issueId, referenceDate);
        Derived d = requireDerived(orgId, issue, kind);
        ImprovementOpportunity row = decisions.findWithLockByOrgIdAndIssueIdAndKind(orgId, issueId, kind)
                .filter(r -> r.getStatus() == OpportunityStatus.ACCEPTED)
                .orElseThrow(() -> ApiException.conflict("초안이 준비된 기회에서만 수정할 수 있습니다."));
        String title = request == null || request.title() == null ? "" : request.title().strip();
        String body = request == null || request.body() == null ? "" : request.body().strip();
        if (title.isEmpty() || body.isEmpty()) {
            throw ApiException.badRequest("제목과 내용을 모두 적어 주세요.");
        }
        if (title.length() > TITLE_MAX || body.length() > BODY_MAX) {
            throw ApiException.badRequest("초안이 너무 깁니다 (제목 " + TITLE_MAX + "자, 내용 " + BODY_MAX + "자까지).");
        }
        boolean changed = !title.equals(row.getDraftTitle()) || !body.equals(row.getDraftBody());
        row.setDraftTitle(title);
        row.setDraftBody(body);
        if (!changed) {
            // Saving the same text is not an edit. A trail that recorded it would grow a row every
            // time a seller pressed 저장 to close the editor.
            ImprovementOpportunity kept = decisions.save(row);
            return view(issue, d, kept, historyFor(orgId, kept), outcomeOf(orgId, kept));
        }
        return settle(orgId, actorId, issue, d, row, OpportunityStatus.ACCEPTED, OpportunityStatus.ACCEPTED,
                OpportunityEvent.EDITED);
    }

    // ---- decision writing ---------------------------------------------------------------------

    /**
     * Save the decision and append exactly one event for it.
     *
     * <p>{@code from} is read under the lock before anything changes, so the event names the status
     * actually left. A decision that changes nothing ({@code from == to} outside an edit) appends
     * nothing — pressing a button twice is one decision.
     */
    private OpportunityView settle(UUID orgId, UUID actorId, ReviewIssueView issue, Derived d,
                                   ImprovementOpportunity row, OpportunityStatus from,
                                   OpportunityStatus to, OpportunityEvent event) {
        boolean isDecision = event == OpportunityEvent.EDITED || from != to;
        row.setStatus(to);
        if (isDecision) {
            row.setDecidedAt(Instant.now());
        } else if (row.getDecidedAt() == null) {
            row.setDecidedAt(Instant.now());
        }
        ImprovementOpportunity saved = decisions.save(row);
        if (isDecision) {
            OpportunityDecisionEvent e = new OpportunityDecisionEvent();
            e.setOrgId(orgId);
            e.setOpportunityId(saved.getId());
            e.setIssueId(saved.getIssueId());
            e.setKind(saved.getKind());
            e.setEvent(event);
            // Null on the first event about this opportunity: there was no standing decision to leave.
            e.setStatusFrom(from == OpportunityStatus.OPEN && event != OpportunityEvent.REOPENED ? null : from);
            e.setStatusTo(to);
            e.setEvidenceCount(issue.evidenceCount());
            e.setActorId(actorId);
            e.setDecidedAt(saved.getDecidedAt());
            trail.save(e);
        }
        // The apply path has no derived candidate and wants no view — see apply's javadoc for why re-deriving
        // there would refuse the application of the draft that had just succeeded.
        return d == null ? null : view(issue, d, saved, historyFor(orgId, saved), outcomeOf(orgId, saved));
    }

    /** The anchored result of one opportunity, or null — including when the outcome lane is not wired. */
    private ImprovementOutcome outcomeOf(UUID orgId, ImprovementOpportunity row) {
        if (outcomes == null || row == null || row.getId() == null) {
            return null;
        }
        return outcomes.byOpportunity(orgId, List.of(row.getId())).get(row.getId());
    }

    /**
     * The batched result for one row, tolerating a row with no id yet.
     *
     * <p>Not defensive noise: a decision this transaction has only just created has no generated id until it is
     * flushed, and {@code Map.of()} rejects a null lookup outright. An unsaved row has no outcome by definition.
     */
    private static ImprovementOutcome standingResult(Map<UUID, ImprovementOutcome> results,
                                                     ImprovementOpportunity row) {
        return row == null || row.getId() == null ? null : results.get(row.getId());
    }

    private List<OpportunityEventView> historyFor(UUID orgId, ImprovementOpportunity row) {
        return historyOf(orgId, List.of(row)).getOrDefault(row.getId(), List.of());
    }

    /** The standing status, or {@code OPEN} when nothing has been decided. */
    private static OpportunityStatus statusOf(ImprovementOpportunity row) {
        return row == null ? OpportunityStatus.OPEN : row.getStatus();
    }

    private ImprovementOpportunity locked(UUID orgId, UUID issueId, OpportunityKind kind) {
        return decisions.findWithLockByOrgIdAndIssueIdAndKind(orgId, issueId, kind).orElse(null);
    }

    // ---- derivation ---------------------------------------------------------------------------

    /** A candidate with the knowledge check it was derived against. */
    record Derived(OpportunityRules.Candidate candidate, KnowledgeMention mention) {
    }

    private List<Derived> derive(UUID orgId, ReviewIssueView issue) {
        OpportunityRules.GuidanceTarget target = OpportunityRules.guidanceTargetOf(issue);
        KnowledgeMention mention = null;
        if (target != null) {
            if (target.scope() == OpportunityRules.Scope.ORG) {
                mention = knowledge.org(orgId, issue.aspect(), target.orgType());
            } else if (issue.dominantProductId() != null) {
                mention = knowledge.product(orgId, issue.dominantProductId(), issue.aspect());
            }
        }
        boolean mentioned = mention != null && mention.mentioned();
        List<Derived> out = new ArrayList<>(2);
        for (OpportunityRules.Candidate c : OpportunityRules.derive(issue, mentioned)) {
            out.add(new Derived(c, c.lane() == OpportunityRules.Lane.GUIDANCE ? mention : null));
        }
        return out;
    }

    private Derived requireDerived(UUID orgId, ReviewIssueView issue, OpportunityKind kind) {
        return derive(orgId, issue).stream()
                .filter(d -> d.candidate().kind() == kind)
                .findFirst()
                // Same sentence whether the issue is gone, another org's, or no longer yields this kind:
                // a decision about an opportunity the evidence no longer supports is not a decision.
                .orElseThrow(() -> ApiException.notFound("이 개선 기회는 지금 제안되지 않습니다."));
    }

    private static ImprovementOpportunity newRow(UUID orgId, UUID issueId, OpportunityKind kind) {
        ImprovementOpportunity row = new ImprovementOpportunity();
        row.setOrgId(orgId);
        row.setIssueId(issueId);
        row.setKind(kind);
        return row;
    }

    private static String key(UUID issueId, OpportunityKind kind) {
        return issueId + ":" + kind.name();
    }

    private static OpportunityView view(ReviewIssueView issue, Derived d, ImprovementOpportunity row,
                                        List<OpportunityEventView> history, ImprovementOutcome outcome) {
        OpportunityRules.Candidate c = d.candidate();
        OpportunityStatus status = row == null ? OpportunityStatus.OPEN : row.getStatus();
        OpportunityKnowledgeView knowledgeView = null;
        if (c.lane() == OpportunityRules.Lane.GUIDANCE && d.mention() != null) {
            knowledgeView = new OpportunityKnowledgeView(
                    c.target().scope().name(),
                    OpportunityDraftComposer.scopeLabelKo(c.target()),
                    c.target().scope() == OpportunityRules.Scope.ORG
                            ? c.target().orgType().name() : c.target().productType().name(),
                    OpportunityDraftComposer.topicOf(c.target(), issue.aspect()),
                    d.mention().sources(), d.mention().mentions(), d.mention().excerpts());
        }
        OpportunityDraftView draft = row != null && status == OpportunityStatus.ACCEPTED && row.getDraftBody() != null
                ? new OpportunityDraftView(row.getDraftTitle(), row.getDraftBody(), row.getUpdatedAt())
                : null;
        return new OpportunityView(
                issue.id(), c.kind().name(), c.kind().labelKo(),
                status.name(), status.labelKo(),
                issue.title(), issue.aspect(), issue.problem(), issue.severity(),
                issue.evidenceCount(), issue.firstEvidenceOn(), issue.lastEvidenceOn(),
                issue.change() == null ? List.of() : issue.change().labelsKo(),
                issue.dominantProductId(), issue.dominantProductName(),
                OpportunityDraftComposer.why(issue, c, d.mention()),
                OpportunityDraftComposer.recommendation(issue, c, d.mention()),
                "/memory/" + issue.id(),
                knowledgeView,
                c.kind().actionLabelKo(),
                draft,
                history,
                // An opportunity that is open has no decision date, whether that is because nothing
                // was ever decided or because the seller took a decision back. Reporting the restore
                // instant beside 「검토 전」 would put a date on a decision that no longer stands.
                row == null || status == OpportunityStatus.OPEN ? null : row.getDecidedAt(),
                row == null ? null : row.getAppliedAt(),
                outcome == null ? null : OpportunityOutcomeViews.of(outcome, c.kind()));
    }
}
