package com.sellerops.knowledge.semantic;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.agent.llm.AgentLlmTransport;
import com.sellerops.agent.quota.AgentLlmUsage;
import com.sellerops.agent.quota.AgentLlmUsageRepository;
import com.sellerops.agent.quota.AgentQuotaProperties;
import com.sellerops.agent.quota.AgentQuotaService;
import com.sellerops.agent.quota.AgentUsageActor;
import com.sellerops.agent.quota.AgentUsageKind;
import com.sellerops.agent.quota.AgentUsageScope;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * <b>The search helpers' calls are on the AI meter</b> (2026-10-31: question intent, evidence eligibility and embeddings
 * reached a vendor on every search and left no usage row — {@code docs/standing/reply_agent_v1.md} §8 ②). A real call
 * is one row, charged to whoever the surrounding work names, with its tokens; a remembered answer is no row; a failed
 * call still cost a call; and a call the ceiling refuses is never made — the helper answers as if its vendor refused.
 */
@DataJpaTest
@ActiveProfiles("test")
class SemanticMeterTest {

    private static final UUID ORG = UUID.fromString("5e1a0000-0000-4000-8000-0000000000a1");
    private static final String INTENT_REPLY = "{\"choices\":[{\"message\":{\"content\":\"{\\\"intent\\\":\\\"배송 기간\\\"}\"}}],"
            + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}";

    @Autowired
    private AgentLlmUsageRepository usage;
    @Autowired
    private KnowledgeEmbeddingRepository embeddings;

    private AgentQuotaService quota(int ceiling) {
        return new AgentQuotaService(usage, new AgentQuotaProperties(true, true, false, 1000, ceiling));
    }

    private List<AgentLlmUsage> rows() {
        return usage.findAll().stream().filter(u -> u.getOrgId().equals(ORG)).toList();
    }

    @Test
    void anIntentCallIsOneRowForWhoTheWorkNamesAndARememberedOneIsNone() {
        int[] posts = {0};
        KnowledgeQuestionIntent intent = new KnowledgeQuestionIntent(
                new KnowledgeQuestionIntentProperties(true, ORG.toString(), "m", "k", 400, "minimal"), null,
                (uri, headers, body) -> {
                    posts[0]++;
                    return new AgentLlmTransport.Response(200, INTENT_REPLY, 7);
                });
        intent.setQuota(quota(100));
        int before = rows().size();

        String first = AgentUsageScope.as(AgentUsageActor.RUN, () -> intent.intentOf(ORG, "언제 와요"));
        AgentUsageScope.as(AgentUsageActor.RUN, () -> intent.intentOf(ORG, "언제 와요"));

        assertThat(first).isEqualTo("배송 기간");
        assertThat(posts[0]).as("the second answer is remembered").isEqualTo(1);
        assertThat(rows().subList(before, rows().size())).singleElement().satisfies(u -> {
            assertThat(u.getKind()).isEqualTo(AgentUsageKind.RETRIEVE);
            assertThat(u.getStep()).isEqualTo(SemanticMeter.INTENT_STEP);
            assertThat(u.getActor()).isEqualTo(AgentUsageActor.RUN);
            assertThat(u.isCountsAsItem()).as("a step of the work, not a new piece of it").isFalse();
            assertThat(u.getInputTokens()).isEqualTo(10);
            assertThat(u.getOutputTokens()).isEqualTo(5);
        });
        assertThat(AgentUsageScope.current()).as("the scope ends with the work").isEqualTo(AgentUsageActor.USER);
    }

    @Test
    void anEligibilityCallWithNoActorNamedIsTheSellersAndAFailedOneStillCounts() {
        KnowledgeEvidenceEligibility eligibility = new KnowledgeEvidenceEligibility(
                new KnowledgeEligibilityProperties(true, ORG.toString(), "m", "k", 600, "minimal"), null,
                (uri, headers, body) -> new AgentLlmTransport.Response(500, "{}", 3));
        eligibility.setQuota(quota(100));
        int before = rows().size();

        assertThat(eligibility.forQuestion(ORG, "언제 와요", List.of("평일 1~2일 내 출고"))).isNull();

        assertThat(rows().subList(before, rows().size())).singleElement().satisfies(u -> {
            assertThat(u.getStep()).isEqualTo(SemanticMeter.ELIGIBILITY_STEP);
            assertThat(u.getActor()).isEqualTo(AgentUsageActor.USER);
        });
    }

    @Test
    void anEmbeddingTheCeilingRefusesIsNeverMade() {
        int[] posts = {0};
        KnowledgeEmbeddingService embedding = new KnowledgeEmbeddingService(
                new KnowledgeEmbeddingProperties(true, ORG.toString(), "m", "k", 1024), embeddings, null,
                (uri, headers, body) -> {
                    posts[0]++;
                    return new AgentLlmTransport.Response(200, "{}", 3);
                });
        embedding.setQuota(quota(0));
        int before = rows().size();

        assertThat(embedding.questionVector(ORG, "언제 와요")).isNull();

        assertThat(posts[0]).isZero();
        assertThat(rows()).hasSize(before);
    }
}
