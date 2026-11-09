package com.sellerops.inquiry.decision;

import com.sellerops.agent.llm.AgentDraftGenerator;
import com.sellerops.inquiry.draft.AnswerBasisState;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The need-level decision for one inquiry (Inquiry Decision v2): what the customer needs, how far each need is
 * covered, and the basis code derived from that.
 *
 * @param outcome {@code DECIDED}, or why no decision was reached ({@code PLAN_FAILED}, {@code JUDGE_FAILED},
 *                {@code NO_NEEDS}). A Case with no decision is never completed for the customer.
 */
public record NeedDecision(Outcome outcome, List<NeedResult> needs, AnswerBasisState basis,
                           InquiryDecisionModel.CallCost cost, int evidenceCandidates, int precedentCandidates,
                           DetailCapability detail) {

    public enum Outcome { DECIDED, PLAN_FAILED, JUDGE_FAILED, NO_NEEDS }

    public List<NeedResult> covered() {
        return needs.stream().filter(n -> n.status().covered()).toList();
    }

    public List<NeedResult> unresolved() {
        return needs.stream().filter(n -> !n.status().covered()).toList();
    }

    /**
     * What this reply leaves to the seller (tenth review): every need it does not fully answer, as one line — what was
     * asked, and for a need answered in part, what the evidence did not settle. Empty when the reply answers everything.
     */
    public List<String> openQuestions() {
        if (basis == AnswerBasisState.NO_ANSWER_BASIS) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (NeedResult n : unresolved()) {
            // For a need answered in part, what the evidence did not settle; a need nothing answered is the question itself.
            String missing = n.status() != NeedStatus.PARTIAL || n.missing() == null || n.missing().isBlank() ? ""
                    : " — " + n.missing().strip();
            out.add(n.need().ask() + missing);
        }
        return List.copyOf(out);
    }

    /**
     * The needs the seller's completed action made moot (2026-11-06 thirteenth review) — not answered, and not the
     * seller's to handle: each is recorded on the work's question list as 「조치 완료로 판단 불필요」 with the record named.
     */
    public List<NeedResult> settledByAction() {
        return needs.stream().filter(NeedResult::settledByAction).toList();
    }

    /** Some need is short only because the listing's detail was never read — reading it is the next step. */
    public boolean acquisitionPlanned() {
        return needs.stream().anyMatch(NeedResult::acquirable);
    }

    /** What a drafter is shown: the evidence of the covered needs, once each — and nothing a need did not cite. */
    public List<AgentDraftGenerator.Passage> passages() {
        List<AgentDraftGenerator.Passage> out = new ArrayList<>();
        for (EvidenceCandidate e : shownEvidence()) {
            out.add(new AgentDraftGenerator.Passage(e.kind().scopeLabelKo(), e.label(), e.text()));
        }
        return out;
    }

    /** The candidates behind {@link #passages}, in the same order — what a draft written from them cites. */
    public List<EvidenceCandidate> shownEvidence() {
        Set<EvidenceCandidate> seen = new LinkedHashSet<>();
        for (NeedResult n : needs) {
            if (NeedAggregation.answersSomething(n)) {
                seen.addAll(n.evidence());
            }
        }
        return List.copyOf(seen);
    }

    /**
     * The drafter's scope line: which needs this reply answers and which it only asks the customer about. Written by
     * us from the needs, never from the evidence text — it tells the drafter what to cover, not what is true.
     *
     * <p>What the evidence leaves open is not mentioned at all — no 「확인 후 안내」, no 「확정해 드리기 어렵습니다」. The
     * seller handles each open question before sending, so a sentence about the remainder is a promise or a refusal
     * nobody on the seller's side made (2026-11-01 case-eval-r10: all four held partial drafts were refused for one).
     * One exception (2026-11-05 re-eval, eleventh review): when the evidence itself says it cannot be settled yet — the
     * seller recorded 「도착일은 아직 정할 수 없음」 — the draft says that, as written. 「Not in the evidence」 is never turned
     * into 「cannot be known」, and saying it closes nothing: the need stays open for the seller.
     */
    public String answerScope() {
        StringBuilder sb = new StringBuilder();
        for (NeedResult n : needs) {
            // Each item carries its need id, so the claim check can say item by item whether the draft took it up.
            sb.append("- ").append(n.need().id()).append(". ").append(n.need().ask());
            if (n.settledByAction()) {
                // Settled by what the seller did, not by a rule (thirteenth review): the reply says what was done, as
                // recorded, and nothing about what deciding it would have taken.
                sb.append(" → 판매자가 이 주문에 그 조치를 이미 했습니다. 판매자 확인 기록대로 한 일을 답하고, 할 수 있는지 "
                        + "정하는 기준은 쓰지 마세요");
            } else if (n.status() == NeedStatus.CONDITIONAL_ON_CUSTOMER) {
                sb.append(" → 고객에게 확인: ").append(n.askCustomer() == null ? "필요한 정보" : n.askCustomer());
            } else if (n.status() == NeedStatus.PARTIAL && NeedAggregation.answersSomething(n)) {
                sb.append(" → 근거가 답하는 데까지만 답하세요(일반 기준은 기준으로). 근거에 없는 것")
                        .append(n.missing() == null || n.missing().isBlank() ? "" : "(" + n.missing().strip() + ")")
                        .append("은 쓰지 마세요. 확정도, 나중에 안내하겠다는 말도 쓰지 않습니다. 확정하기 어렵다는 말은 근거"
                                + "(판매자 확인 기록 포함)에 그렇게 적혀 있을 때만 적힌 대로 쓰고, 근거에 없다는 이유로 쓰지 않습니다. "
                                + "그 부분은 판매자가 초안을 보고 직접 덧붙입니다");
            } else if (!n.status().covered()) {
                sb.append(" → 이번 답변에서 다루지 않습니다. 답하지 말고, 나중에 안내하겠다는 말도, 답하기 어렵다는 말도 "
                        + "쓰지 마세요. 판매자가 초안을 보고 직접 덧붙입니다");
            }
            sb.append('\n');
        }
        return sb.toString().strip();
    }

    /**
     * The needs the drafter was told to take up — answer, answer in part, or ask the customer about — by id. A need it was
     * told to leave to the seller is not here (2026-11-02: what the check reads item by item, so a need the draft drops
     * goes to the seller's list instead of going nowhere).
     */
    public List<NeedResult> toTakeUp() {
        return needs.stream().filter(n -> n.status().covered() || n.status() == NeedStatus.PARTIAL
                && NeedAggregation.answersSomething(n)).toList();
    }

    public static NeedDecision failed(Outcome why, InquiryDecisionModel.CallCost cost, DetailCapability detail) {
        return new NeedDecision(why, List.of(), AnswerBasisState.NO_ANSWER_BASIS, cost, 0, 0, detail);
    }
}
