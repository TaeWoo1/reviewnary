package com.sellerops.inquiry.decision;

import com.sellerops.inquiry.draft.AnswerBasisState;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * <b>The case policy, owned by code</b> (Inquiry Decision v2). Deterministic and pure.
 *
 * <p>The model decides what each need is and how far the evidence closes it. This class decides what the product
 * does with that, and it is the only place that does:
 * <ul>
 *   <li>every need FULL → {@link AnswerBasisState#GROUNDED};</li>
 *   <li>every need FULL or CONDITIONAL_ON_CUSTOMER, at least one conditional → {@link AnswerBasisState#NEEDS_CLARIFICATION};</li>
 *   <li><b>any need PARTIAL, NONE or UNKNOWN → {@link AnswerBasisState#NO_ANSWER_BASIS}</b>: the Case goes to the seller.
 *       There is no partial answer to a customer in this product — a reply that answers two of three questions reads as
 *       an answer to all three.</li>
 *   <li>no needs at all (the planner found nothing to answer) → NO_ANSWER_BASIS: nothing was judged, so nothing may be
 *       completed.</li>
 * </ul>
 * This is what removes partial-coverage leakage STRUCTURALLY: the old basis read 「a current passage exists」; this one
 * reads every need, and one uncovered need is enough to keep the Case away from the customer.
 */
public final class NeedAggregation {

    private NeedAggregation() {
    }

    public static AnswerBasisState basis(List<NeedResult> needs) {
        if (needs == null || needs.isEmpty()) {
            return AnswerBasisState.NO_ANSWER_BASIS;
        }
        // Owner, 2026-11-01 tenth review: a message whose needs the store answers only in part is drafted for the part it
        // answers, and the rest is left to the seller one question at a time — never sent as if complete (the inquiry's
        // send is held while a required question is open, OpenQuestionService). Before, one uncovered need sent the whole
        // message to the seller: 「두 질문에만 답한 답변은 세 질문 모두에 답한 것처럼 읽힌다」 — the hold answers that.
        boolean conditional = false;
        boolean answerable = false;
        boolean open = false;
        for (NeedResult n : needs) {
            answerable |= answersSomething(n);
            open |= !n.status().covered();
            conditional |= n.status() == NeedStatus.CONDITIONAL_ON_CUSTOMER;
        }
        if (!answerable) {
            return AnswerBasisState.NO_ANSWER_BASIS;
        }
        return open ? AnswerBasisState.PARTIAL_ANSWER
                : conditional ? AnswerBasisState.NEEDS_CLARIFICATION : AnswerBasisState.GROUNDED;
    }

    /**
     * Whether a reply can say anything true about this need: it is covered, or the evidence answers part of it (a
     * shipping rule for 「언제 출고되나요」 with no order to confirm it by — the rule is said as a rule, and what it does not
     * settle stays open). A need with nothing cited answers nothing.
     */
    public static boolean answersSomething(NeedResult n) {
        return n.status().covered() || n.status() == NeedStatus.PARTIAL && !n.evidence().isEmpty();
    }

    /**
     * The judge's verdicts made safe, need by need:
     * <ul>
     *   <li>a need the judge did not answer is NONE;</li>
     *   <li>evidence ids that are not candidates are dropped; <b>FULL, CONDITIONAL and PARTIAL with no surviving evidence
     *       become NONE</b> — a verdict of support that cites nothing is not support;</li>
     *   <li><b>evidence must be about the instance the need is about</b> (v2.2, generalising v2.1's other-listing fence):
     *       for a need about one listing or one order ({@link NeedType#instanceScope()}), a cited candidate attributed to
     *       a DIFFERENT instance of that kind ({@link EvidenceScope}) is dropped before the rule above — a fact about
     *       listing Y is not a fact about listing X, and order Y's state is not order X's. And for an ORDER need a FULL
     *       requires at least one cited candidate attributed to THIS order: otherwise it is PARTIAL — a company rule says
     *       what usually happens, never what happened to this order ({@link NeedType#fullRequiresAttributedEvidence()}).
     *       Availability needs name no instance: 「do you sell another size」 is answered by another listing existing;</li>
     *   <li><b>the verdict is held to its own words</b> (CoverageJudge v2): a <b>FULL</b> that names an assumption not in
     *       the evidence is PARTIAL; a FULL that names missing information is PARTIAL; a FULL that names a value only the
     *       customer can supply is CONDITIONAL_ON_CUSTOMER. <b>A CONDITIONAL is not downgraded for naming an
     *       assumption</b> (v2.2): the A/B run (apr-8ef649ab) measured that rule at zero unsafe verdicts caught and one
     *       correct CONDITIONAL lost — a judge describing what it cannot know about the customer is doing its job.
     *       None of this reads the words — only whether the judge filled the list;</li>
     *   <li>a precedent id cited as EVIDENCE is dropped (a past answer never grounds);</li>
     *   <li>precedents are kept only for uncovered needs and only when they are candidates;</li>
     *   <li>a listing need left PARTIAL/NONE while the listing's detail is unreadable becomes UNKNOWN; while it was never
     *       read, it is marked acquirable (the status stays — acquisition is a step, not an answer).</li>
     * </ul>
     */
    public static List<NeedResult> enforce(List<InquiryNeed> needs, Map<String, NeedVerdict> verdicts,
                                           Map<String, EvidenceCandidate> evidence,
                                           Map<String, PrecedentCandidate> precedents, DetailCapability detail) {
        return enforce(needs, verdicts, evidence, precedents, detail, EvidenceScope.CaseScope.NONE);
    }

    public static List<NeedResult> enforce(List<InquiryNeed> needs, Map<String, NeedVerdict> verdicts,
                                           Map<String, EvidenceCandidate> evidence,
                                           Map<String, PrecedentCandidate> precedents, DetailCapability detail,
                                           UUID caseProductId) {
        return enforce(needs, verdicts, evidence, precedents, detail,
                new EvidenceScope.CaseScope(caseProductId, null));
    }

    public static List<NeedResult> enforce(List<InquiryNeed> needs, Map<String, NeedVerdict> verdicts,
                                           Map<String, EvidenceCandidate> evidence,
                                           Map<String, PrecedentCandidate> precedents, DetailCapability detail,
                                           EvidenceScope.CaseScope caseScope) {
        EvidenceScope.CaseScope scope = caseScope == null ? EvidenceScope.CaseScope.NONE : caseScope;
        List<NeedResult> out = new ArrayList<>(needs.size());
        // What a need would be without the settlement, by position — restored when no action of this inquiry was done.
        Map<Integer, NeedResult> unsettled = new java.util.HashMap<>();
        for (InquiryNeed need : needs) {
            NeedVerdict v = verdicts.get(need.id());
            NeedStatus judged = v == null ? null : v.status();
            NeedStatus status = judged == null ? NeedStatus.NONE : judged;
            NeedResult.Enforcement why = judged == null ? NeedResult.Enforcement.NOT_JUDGED : null;
            EvidenceScope.Kind about = need.type() == null ? null : need.type().instanceScope();
            String instance = about == null ? null : scope.idOf(about);
            List<EvidenceCandidate> cited = new ArrayList<>();
            boolean foreignDropped = false;
            if (v != null) {
                for (String id : v.evidence()) {
                    EvidenceCandidate c = evidence.get(id);
                    if (c == null || cited.contains(c)) {
                        continue;
                    }
                    if (about != null && instance != null && c.scope() != null && c.scope().kind() == about
                            && c.scope().id() != null && !instance.equals(c.scope().id())) {
                        foreignDropped = true; // attributed to another instance of what this need is about
                        continue;
                    }
                    cited.add(c);
                }
            }
            if (status != NeedStatus.NONE && cited.isEmpty()) {
                why = why != null ? why : foreignDropped ? NeedResult.Enforcement.OTHER_INSTANCE_ONLY
                        : NeedResult.Enforcement.NO_CITED_EVIDENCE;
                status = NeedStatus.NONE;
            }
            if (status == NeedStatus.FULL && need.type() != null && need.type().fullRequiresAttributedEvidence()
                    && cited.stream().noneMatch(c -> c.scope() != null && c.scope().kind() == about
                            && c.scope().id() != null && c.scope().id().equals(instance))) {
                status = NeedStatus.PARTIAL;
                why = NeedResult.Enforcement.SCOPE_UNATTRIBUTED;
            } else if (v != null && status == NeedStatus.FULL && !v.assumptions().isEmpty()) {
                status = NeedStatus.PARTIAL;
                why = NeedResult.Enforcement.DECLARED_ASSUMPTION;
            } else if (v != null && status == NeedStatus.FULL && !v.missingInfo().isEmpty()) {
                status = NeedStatus.PARTIAL;
                why = NeedResult.Enforcement.DECLARED_MISSING;
            } else if (v != null && status == NeedStatus.FULL && !v.customerInput().isEmpty()) {
                status = NeedStatus.CONDITIONAL_ON_CUSTOMER;
                why = NeedResult.Enforcement.DECLARED_CUSTOMER_INPUT;
            }
            // Settled, not answered (2026-11-06 thirteenth review): the judge says the seller's record of an action already
            // carried out made this need's open part moot; code accepts that only under its own conditions.
            EvidenceCandidate settling = status.covered() ? null : settlingRecord(need, v, evidence, scope);
            NeedStatus beforeSettling = status;
            NeedResult.Enforcement whyBefore = why;
            List<EvidenceCandidate> citedBefore = List.copyOf(cited);
            if (settling != null) {
                if (!cited.contains(settling)) {
                    cited.add(settling);
                }
                status = NeedStatus.FULL;
                why = NeedResult.Enforcement.SETTLED_BY_ACTION;
            }
            boolean acquirable = false;
            if (!status.covered() && need.type() != null && need.type().aboutTheListing()) {
                if (detail != null && detail.unreadable()) {
                    status = NeedStatus.UNKNOWN;
                    why = why != null ? why : NeedResult.Enforcement.UNREADABLE_SOURCE;
                } else if (detail == DetailCapability.NOT_ACQUIRED) {
                    acquirable = true;
                }
            }
            List<PrecedentCandidate> offered = new ArrayList<>();
            if (!status.covered() && v != null) {
                for (String id : v.precedents()) {
                    PrecedentCandidate p = precedents.get(id);
                    if (p != null && !offered.contains(p)) {
                        offered.add(p);
                    }
                }
            }
            String askCustomer = null;
            if (status == NeedStatus.CONDITIONAL_ON_CUSTOMER && v != null) {
                askCustomer = v.askCustomer() != null ? v.askCustomer()
                        : v.customerInput().isEmpty() ? null : String.join(" · ", v.customerInput());
            }
            out.add(new NeedResult(judged, need, status, List.copyOf(cited), List.copyOf(offered),
                    v == null ? null : v.missing(), askCustomer, acquirable, status == judged ? null : why));
            if (settling != null) {
                unsettled.put(out.size() - 1, new NeedResult(judged, need, beforeSettling, citedBefore,
                        List.copyOf(offered), v == null ? null : v.missing(), null, acquirable,
                        beforeSettling == judged ? null : whyBefore));
            }
        }
        // The action has to be one THIS inquiry asked for (fourteenth review, 2026-11-06): a settlement stands only beside
        // an order-action need the same record answers in full. A refund done does not settle what an exchange needed.
        for (Map.Entry<Integer, NeedResult> e : unsettled.entrySet()) {
            EvidenceCandidate record = out.get(e.getKey()).settlingRecord();
            boolean actionDone = out.stream().anyMatch(r -> r.need().type() == NeedType.ORDER_ACTION
                    && r.status() == NeedStatus.FULL && !r.settledByAction() && r.evidence().contains(record));
            if (!actionDone) {
                out.set(e.getKey(), e.getValue());
            }
        }
        return List.copyOf(out);
    }

    /**
     * The need types a completed action can make moot: whether it can be done (a rule). Never an action request itself —
     * the record answers it or it stays open; another action asked in the same message is never settled by this one.
     */
    static final java.util.Set<NeedType> SETTLEABLE = java.util.EnumSet.of(NeedType.POLICY);

    /**
     * The record that settles {@code need}, or null. All of it must hold — the judge naming a record is not enough:
     * <ul>
     *   <li>the need is about a rule ({@link #SETTLEABLE}) — never an action request, an order's state (a date, an
     *       amount), a listing's fact, or a decision that is the seller's own; those the customer asked, and a finished
     *       action does not answer them;</li>
     *   <li>the record named is the seller's own record for this inquiry ({@code SELLER_CHECK}), attributed to this
     *       Case's order, and says 처리 완료 — an action under way or refused settles nothing;</li>
     *   <li>(after the loop, {@link #enforce}) an order-action need of this inquiry is answered in full by that same
     *       record — the action done is one this inquiry asked for.</li>
     * </ul>
     * Even then the customer's question is not closed here: the composer closes it only when the claim check says the
     * reply answered it (a rule asked separately — who pays the return shipping — is not answered by 「발송했습니다」).
     * What this settles is the system's own open part of the need: what deciding the action would have taken.
     */
    static EvidenceCandidate settlingRecord(InquiryNeed need, NeedVerdict v, Map<String, EvidenceCandidate> evidence,
                                            EvidenceScope.CaseScope scope) {
        if (v == null || v.settledBy() == null || need.type() == null || !SETTLEABLE.contains(need.type())) {
            return null;
        }
        EvidenceCandidate c = evidence.get(v.settledBy());
        String order = scope == null ? null : scope.idOf(EvidenceScope.Kind.ORDER);
        if (c == null || c.kind() != EvidenceCandidate.Kind.SELLER_CHECK
                || !com.sellerops.inquiry.sellercheck.SellerCheckOutcome.DONE.name().equals(c.factKey())
                || order == null || c.scope() == null || c.scope().kind() != EvidenceScope.Kind.ORDER
                || !order.equals(c.scope().id())) {
            return null;
        }
        return c;
    }
}
