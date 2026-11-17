package com.sellerops.agent.llm.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.agent.llm.AgentDraftProperties;
import com.sellerops.agent.quota.DevLlmBudget;
import com.sellerops.inquiry.decision.InquiryDecisionProperties;
import com.sellerops.knowledge.semantic.KnowledgeEmbeddingProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Loads the real config overlay, without a database, HTTP transport or any model call. */
class RunyourProfileTest {

    private static final List<String> CAPABILITIES = List.of(
            "reply-agent", "triage.ai-pilot", "agent.draft", "agent.report", "agent.plan", "agent.judge", "agent.converse",
            "inquiry.signature", "inquiry-goal", "product.image-knowledge", "knowledge.embedding", "knowledge.intent",
            "knowledge.eligibility", "responsibility.investigation", "inquiry-decision", "review.media-vision",
            "review.aspect");

    private ApplicationContextRunner context() {
        return new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.profiles.include=runyour", "RUNYOUR_API_KEY=synthetic-router-key")
                .withBean(AgentDraftProperties.class).withBean(InquiryDecisionProperties.class)
                .withBean(KnowledgeEmbeddingProperties.class);
    }

    @Test
    void aRouterEvaluationCannotInheritAnotherProvidersPrices() {
        context().withBean(DevLlmBudget.class).withPropertyValues("sellerops.dev.llm-budget-usd=10",
                "sellerops.dev.llm-budget-since=2026-11-15T00:00:00Z").run(c -> assertThat(c).hasFailed());
        context().withBean(DevLlmBudget.class).withPropertyValues("sellerops.dev.llm-budget-usd=10",
                "sellerops.dev.llm-budget-since=2026-11-15T00:00:00Z", "RUNYOUR_INPUT_USD_PER_MTOK=1",
                "RUNYOUR_OUTPUT_USD_PER_MTOK=2").run(c -> {
                    assertThat(c).hasNotFailed();
                    assertThat(c.getBean(DevLlmBudget.class).enabled()).isTrue();
                });
    }

    @Test
    void oneProfileRoutesEveryCapabilityWithoutTurningAnyOn() {
        context().run(c -> {
            assertThat(c).hasNotFailed();
            for (String capability : CAPABILITIES) {
                String prefix = "sellerops." + capability;
                assertThat(c.getEnvironment().getProperty(prefix + ".vendor")).as(capability).isEqualTo("RUNYOUR");
                assertThat(c.getEnvironment().getProperty(prefix + ".api-key")).as(capability)
                        .isEqualTo("synthetic-router-key");
                assertThat(c.getEnvironment().getProperty(prefix + ".enabled", Boolean.class)).as(capability).isFalse();
            }
            assertThat(c.getBean(AgentDraftProperties.class).model()).isEqualTo("anthropic/claude-opus-5-5");
            assertThat(c.getBean(InquiryDecisionProperties.class).vendor()).isEqualTo(LlmVendor.RUNYOUR);
            assertThat(c.getBean(KnowledgeEmbeddingProperties.class).vendor()).isEqualTo(LlmVendor.RUNYOUR);
            assertThat(c.getBean(KnowledgeEmbeddingProperties.class).model()).isEmpty();
            assertThat(c.getBean(AgentDraftProperties.class).isDeployed()).isFalse();
            assertThat(c.getEnvironment().getProperty("sellerops.dev.llm-price-in-per-mtok")).isEqualTo("-1");
        });
    }

    @Test
    void selectedModelsAndExplicitFeatureScopeRemainIndependent() {
        context().withPropertyValues("RUNYOUR_MODEL=anthropic/chat-test", "RUNYOUR_CHECK_MODEL=anthropic/check-test",
                "RUNYOUR_EMBEDDING_MODEL=openai/embedding-test", "sellerops.agent.draft.enabled=true",
                "sellerops.agent.draft.enabled-org-ids=*").run(c -> {
                    assertThat(c).hasNotFailed();
                    assertThat(c.getBean(AgentDraftProperties.class).model()).isEqualTo("anthropic/chat-test");
                    assertThat(c.getBean(AgentDraftProperties.class).checkModel()).isEqualTo("anthropic/check-test");
                    assertThat(c.getBean(AgentDraftProperties.class).isDeployed()).isTrue();
                    assertThat(c.getBean(KnowledgeEmbeddingProperties.class).isEnabled()).isFalse();
                    assertThat(c.getBean(KnowledgeEmbeddingProperties.class).storedModel())
                            .isEqualTo("runyour:openai/embedding-test");
                });
    }
}
