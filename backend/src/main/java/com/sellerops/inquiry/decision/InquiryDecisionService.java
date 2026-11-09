package com.sellerops.inquiry.decision;

import com.sellerops.agent.access.AgentCapabilityAccess;
import com.sellerops.agent.llm.AgentLlmTransport;
import com.sellerops.agent.quota.AgentQuotaService;
import com.sellerops.agent.quota.AgentUsageActor;
import com.sellerops.agent.quota.AgentUsageKind;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * <b>The door to the Inquiry Decision v2 capability</b>: the organisation gate, the two calls, and a short memo.
 *
 * <p><b>The memo's key is the exact request</b> — model, instruction, message, needs, candidates, past answers — so an
 * answer is reused only when nothing it depended on has changed. The investigation and the draft both assess the same
 * inquiry minutes apart; without it they pay twice and may be told two different things about the same evidence.
 * Five minutes, in memory, bounded; nothing is persisted.
 *
 * <p><b>Every call it actually makes is on the AI meter</b> (2026-10-31: the first real-model round found the plan and
 * judge calls missing from usage — {@code docs/standing/reply_agent_v1.md} §8 ②). Charged as a step of the draft or
 * investigation they serve ({@link #PLAN_STEP}, {@link #JUDGE_STEP}), never as a new piece of the seller's work, to
 * the actor {@link #chargedTo} names — USER when nobody named one, as everywhere else. A remembered answer costs
 * nothing and is not charged. When the ceiling refuses, the call is not made and the answer says so ({@code QUOTA}):
 * the decision then has no opinion, which never drafts.
 */
@Service
public class InquiryDecisionService implements InquiryDecisionModel {

    static final long MEMO_MILLIS = 5 * 60 * 1000L;
    static final int MEMO_MAX = 256;
    /** The two calls' step names on the meter. */
    public static final String PLAN_STEP = "NEED_PLAN";
    public static final String JUDGE_STEP = "NEED_JUDGE";
    /** The answer's failure word when the AI ceiling refused the call. */
    static final String QUOTA = "QUOTA";

    private final InquiryDecisionProperties properties;
    private final AgentCapabilityAccess access;
    private final InquiryDecisionGenerator generator;
    private final Map<String, Memo> memo = new LinkedHashMap<>(16, 0.75f, true);
    private AgentQuotaService quota;

    private record Memo(Object value, long at) {
    }

    public InquiryDecisionService(InquiryDecisionProperties properties, AgentCapabilityAccess access,
                                  AgentLlmTransport transport) {
        this.properties = properties;
        this.access = access;
        this.generator = properties == null ? null : new InquiryDecisionGenerator(transport, properties);
    }

    @Autowired(required = false)
    public void setQuota(AgentQuotaService quota) {
        this.quota = quota;
    }

    /** A capability that is not present — what a unit test and a context without it get. */
    public static InquiryDecisionService disabled() {
        return new InquiryDecisionService(null, null, null);
    }

    @Override
    public boolean enabledFor(UUID orgId) {
        if (properties == null) {
            return false;
        }
        return access == null ? properties.isEnabledFor(orgId) : access.allows(properties, orgId);
    }

    @Override
    public Answer<List<InquiryNeed>> plan(UUID orgId, String question) {
        return plan(orgId, question, AgentUsageActor.USER);
    }

    @Override
    public Answer<Map<String, NeedVerdict>> judge(UUID orgId, String question, List<InquiryNeed> needs,
                                                  List<EvidenceCandidate> evidence,
                                                  List<PrecedentCandidate> precedents) {
        return judge(orgId, question, needs, evidence, precedents, null, AgentUsageActor.USER);
    }

    @Override
    public Answer<Map<String, NeedVerdict>> judge(UUID orgId, String question, List<InquiryNeed> needs,
                                                  List<EvidenceCandidate> evidence,
                                                  List<PrecedentCandidate> precedents, String received) {
        return judge(orgId, question, needs, evidence, precedents, received, AgentUsageActor.USER);
    }

    @Override
    public InquiryDecisionModel chargedTo(AgentUsageActor actor) {
        AgentUsageActor who = actor == null ? AgentUsageActor.USER : actor;
        InquiryDecisionService self = this;
        return new InquiryDecisionModel() {
            @Override
            public boolean enabledFor(UUID orgId) {
                return self.enabledFor(orgId);
            }

            @Override
            public Answer<List<InquiryNeed>> plan(UUID orgId, String question) {
                return self.plan(orgId, question, who);
            }

            @Override
            public Answer<Map<String, NeedVerdict>> judge(UUID orgId, String question, List<InquiryNeed> needs,
                                                          List<EvidenceCandidate> evidence,
                                                          List<PrecedentCandidate> precedents) {
                return self.judge(orgId, question, needs, evidence, precedents, null, who);
            }

            @Override
            public Answer<Map<String, NeedVerdict>> judge(UUID orgId, String question, List<InquiryNeed> needs,
                                                          List<EvidenceCandidate> evidence,
                                                          List<PrecedentCandidate> precedents, String received) {
                return self.judge(orgId, question, needs, evidence, precedents, received, who);
            }
        };
    }

    private Answer<List<InquiryNeed>> plan(UUID orgId, String question, AgentUsageActor actor) {
        if (!enabledFor(orgId) || question == null || question.isBlank()) {
            return Answer.none();
        }
        String body = generator.planBody(question);
        return remembered(body, () -> metered(orgId, PLAN_STEP, actor, () -> generator.plan(orgId, body)));
    }

    private Answer<Map<String, NeedVerdict>> judge(UUID orgId, String question, List<InquiryNeed> needs,
                                                   List<EvidenceCandidate> evidence,
                                                   List<PrecedentCandidate> precedents, String received,
                                                   AgentUsageActor actor) {
        if (!enabledFor(orgId) || needs == null || needs.isEmpty()) {
            return Answer.none();
        }
        String body = generator.judgeBody(question, needs, evidence, precedents, received);
        return remembered(body, () -> metered(orgId, JUDGE_STEP, actor,
                () -> generator.judge(orgId, body, needs.stream().map(InquiryNeed::id).toList(), evidence.size(),
                        precedents.size())));
    }

    /** One real call on the meter: charged before it is made, its time and tokens written after. */
    private <T> Answer<T> metered(UUID orgId, String step, AgentUsageActor actor, Supplier<Answer<T>> call) {
        AgentQuotaService.Charge charge = quota == null ? null
                : quota.chargeCheck(orgId, AgentUsageKind.DRAFT, step, actor);
        if (charge != null && charge.decision() != null && !charge.decision().allowed()) {
            return new Answer<>(null, CallCost.NONE, QUOTA);
        }
        Answer<T> answer = call.get();
        if (charge != null && answer.cost() != null && answer.cost().calls() > 0) {
            quota.recordOutcome(charge.usageId(), answer.cost().elapsedMs(), answer.cost().promptTokens(),
                    answer.cost().completionTokens());
        }
        return answer;
    }

    /** A remembered answer costs nothing; a refused one is not remembered, so the next assessment may try again. */
    @SuppressWarnings("unchecked")
    private <T> Answer<T> remembered(String body, Supplier<Answer<T>> call) {
        String key = sha256(body);
        long now = System.currentTimeMillis();
        synchronized (memo) {
            Memo m = memo.get(key);
            if (m != null && now - m.at() < MEMO_MILLIS) {
                return new Answer<>((T) m.value(), CallCost.NONE);
            }
        }
        Answer<T> answer = call.get();
        if (answer.value() != null) {
            synchronized (memo) {
                memo.put(key, new Memo(answer.value(), now));
                while (memo.size() > MEMO_MAX) {
                    memo.remove(memo.keySet().iterator().next());
                }
            }
        }
        return answer;
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** For the payload floor test. */
    InquiryDecisionGenerator generator() {
        return generator;
    }
}
