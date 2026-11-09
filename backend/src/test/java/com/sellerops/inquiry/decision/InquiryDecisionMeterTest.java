package com.sellerops.inquiry.decision;

import static com.sellerops.inquiry.decision.JudgeIntegrationContractTest.EVIDENCE;
import static com.sellerops.inquiry.decision.JudgeIntegrationContractTest.NEEDS;
import static com.sellerops.inquiry.decision.JudgeIntegrationContractTest.ORG;
import static com.sellerops.inquiry.decision.JudgeIntegrationContractTest.PRECEDENTS;
import static com.sellerops.inquiry.decision.JudgeIntegrationContractTest.V1;
import static com.sellerops.inquiry.decision.JudgeIntegrationContractTest.V2;
import static com.sellerops.inquiry.decision.JudgeIntegrationContractTest.envelope;
import static com.sellerops.inquiry.decision.JudgeIntegrationContractTest.props;
import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.agent.llm.AgentLlmTransport;
import com.sellerops.agent.quota.AgentLlmUsage;
import com.sellerops.agent.quota.AgentLlmUsageRepository;
import com.sellerops.agent.quota.AgentQuotaProperties;
import com.sellerops.agent.quota.AgentQuotaService;
import com.sellerops.agent.quota.AgentUsageActor;
import com.sellerops.agent.quota.AgentUsageKind;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * <b>The need decision's calls are on the AI meter</b> (2026-10-31: the first real-model round found inquiry plan and
 * judge calls missing from usage — {@code docs/standing/reply_agent_v1.md} §8 ②). A real call is one row, charged to
 * who asked, with its tokens; a remembered answer is no row; a failed call still cost a call; and a call the ceiling
 * refuses is never made.
 */
@DataJpaTest
@ActiveProfiles("test")
class InquiryDecisionMeterTest {

    @Autowired
    private AgentLlmUsageRepository usage;

    private static final String OK = envelope("{\"verdicts\":[" + V1 + "," + V2 + "]}");

    private InquiryDecisionService service(AgentLlmTransport transport, boolean enforced, int ceiling) {
        InquiryDecisionService s = new InquiryDecisionService(props(InquiryDecisionPrompt.JUDGE_V2, "", "json_schema"),
                null, transport);
        s.setQuota(new AgentQuotaService(usage, new AgentQuotaProperties(true, enforced, false, 1000, ceiling)));
        return s;
    }

    private List<AgentLlmUsage> rows() {
        return usage.findAll().stream().filter(u -> u.getOrgId().equals(ORG)).toList();
    }

    @Test
    void aRealCallIsOneRowForWhoAskedAndARememberedAnswerIsNone() {
        int[] posts = {0};
        InquiryDecisionService s = service((uri, headers, json) -> {
            posts[0]++;
            return new AgentLlmTransport.Response(200, OK, 7);
        }, true, 100);
        int before = rows().size();

        InquiryDecisionModel.Answer<?> first = s.chargedTo(AgentUsageActor.RUN)
                .judge(ORG, "q-meter-1", NEEDS, EVIDENCE, PRECEDENTS);
        s.chargedTo(AgentUsageActor.RUN).judge(ORG, "q-meter-1", NEEDS, EVIDENCE, PRECEDENTS);

        assertThat(first.value()).isNotNull();
        assertThat(posts[0]).as("the second answer is remembered").isEqualTo(1);
        List<AgentLlmUsage> added = rows().subList(before, rows().size());
        assertThat(added).singleElement().satisfies(u -> {
            assertThat(u.getKind()).isEqualTo(AgentUsageKind.DRAFT);
            assertThat(u.getStep()).isEqualTo(InquiryDecisionService.JUDGE_STEP);
            assertThat(u.getActor()).isEqualTo(AgentUsageActor.RUN);
            assertThat(u.isCountsAsItem()).as("a step of the work, not a new piece of it").isFalse();
            assertThat(u.getInputTokens()).isEqualTo(10);
            assertThat(u.getOutputTokens()).isEqualTo(5);
        });
    }

    @Test
    void aCallWithNoActorNamedIsTheSellersAndAFailedCallStillCounts() {
        InquiryDecisionService s = service((uri, headers, json) -> new AgentLlmTransport.Response(500, "{}", 3),
                true, 100);
        int before = rows().size();

        InquiryDecisionModel.Answer<?> failed = s.judge(ORG, "q-meter-2", NEEDS, EVIDENCE, PRECEDENTS);

        assertThat(failed.value()).isNull();
        assertThat(rows().subList(before, rows().size())).singleElement()
                .satisfies(u -> assertThat(u.getActor()).isEqualTo(AgentUsageActor.USER));
    }

    @Test
    void aCallTheCeilingRefusesIsNeverMade() {
        int[] posts = {0};
        InquiryDecisionService s = service((uri, headers, json) -> {
            posts[0]++;
            return new AgentLlmTransport.Response(200, OK, 7);
        }, true, 0);

        InquiryDecisionModel.Answer<?> refused = s.chargedTo(AgentUsageActor.USER)
                .judge(ORG, "q-meter-3", NEEDS, EVIDENCE, PRECEDENTS);

        assertThat(refused.value()).isNull();
        assertThat(refused.failure()).isEqualTo(InquiryDecisionService.QUOTA);
        assertThat(posts[0]).isZero();
    }
}
