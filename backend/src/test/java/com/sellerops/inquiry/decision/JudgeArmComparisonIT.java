package com.sellerops.inquiry.decision;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sellerops.agent.llm.AgentLlmTransport;
import com.sellerops.inquiry.Inquiry;
import com.sellerops.inquiry.InquiryRepository;
import com.sellerops.inquiry.draft.InquiryKnowledgeAssessor;
import com.sellerops.order.fact.OrderFactLookup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * <b>CoverageJudge v2 against v3, on the same real inputs</b> (owner, 2026-10-29: 「v3 구조로 만들고 v2와 비교해 줘」).
 *
 * <p>Each listed inquiry goes through the production assessment ({@link InquiryKnowledgeAssessor#assess}, stored order
 * facts only — nothing is written, nothing reaches a channel). Its decision model is replaced by one that plans once and
 * then judges the SAME needs, evidence and past answers with both instructions, {@code CMP_REPEATS} times each. So the
 * arms differ only in the instruction's output shape — the request otherwise byte-identical apart from the schema.
 *
 * <p>Writes one row per judge call to {@code CMP_OUT}: inquiry id, arm, run, failure word, the status per need id, and
 * tokens. <b>No text</b> — the customer's message, the needs' wording and the evidence never leave the database.
 * {@code CMP_MAX_CALLS} is the approved cap and a run that would pass it stops.
 *
 * <p>Off unless {@code RUN_JUDGE_ARM_COMPARISON=true}; boots the whole application against the database the
 * environment names (the evaluation copy), with the key in {@code CMP_API_KEY}.
 */
@EnabledIfEnvironmentVariable(named = "RUN_JUDGE_ARM_COMPARISON", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class JudgeArmComparisonIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private InquiryKnowledgeAssessor assessor;
    @Autowired
    private InquiryEvidenceCollector collector;
    @Autowired
    private InquiryRepository inquiries;
    @Autowired
    private AgentLlmTransport transport;

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }

    private static InquiryDecisionProperties props(UUID org, String judge) {
        return new InquiryDecisionProperties(true, org.toString(), env("CMP_MODEL", "claude-opus-5-5"),
                System.getenv("CMP_API_KEY"), 800, Integer.parseInt(env("CMP_JUDGE_MAX_TOKENS", "6000")),
                env("CMP_EFFORT", "low"), judge, "", InquiryDecisionProperties.FORMAT_JSON_SCHEMA,
                env("CMP_VENDOR", "ANTHROPIC"));
    }

    @Test
    void compare() throws Exception {
        UUID org = UUID.fromString(System.getenv("CMP_ORG"));
        int repeats = Integer.parseInt(env("CMP_REPEATS", "2"));
        int maxCalls = Integer.parseInt(env("CMP_MAX_CALLS", "0"));
        assertThat(System.getenv("CMP_API_KEY")).as("CMP_API_KEY").isNotBlank();
        assertThat(maxCalls).as("CMP_MAX_CALLS — the approved cap").isPositive();
        String synthetic = System.getenv("CMP_SYNTHETIC_QUESTION");
        if (synthetic != null && !synthetic.isBlank()) {
            // A made-up sentence only: what the planner sends back for it, raw, to see the vendor's answer shape.
            InquiryDecisionGenerator g = new InquiryDecisionGenerator(transport,
                    props(org, InquiryDecisionPrompt.JUDGE_V2));
            AgentLlmTransport.Response r = transport.post(
                    com.sellerops.agent.llm.provider.LlmWire.of(props(org, InquiryDecisionPrompt.JUDGE_V2).vendor())
                            .endpoint(),
                    com.sellerops.agent.llm.provider.LlmWire.of(props(org, InquiryDecisionPrompt.JUDGE_V2).vendor())
                            .headers(System.getenv("CMP_API_KEY")), g.planBody(synthetic));
            System.out.println("JUDGE_ARM_SYNTHETIC status=" + r.status() + " body=" + r.body());
            return;
        }
        List<UUID> ids = Files.readAllLines(Path.of(System.getenv("CMP_CASES"))).stream().map(String::strip)
                .filter(s -> !s.isEmpty()).map(UUID::fromString).toList();

        Map<String, InquiryDecisionGenerator> arms = new java.util.LinkedHashMap<>();
        arms.put("v2", new InquiryDecisionGenerator(transport, props(org, InquiryDecisionPrompt.JUDGE_V2)));
        arms.put("v3", new InquiryDecisionGenerator(transport, props(org, InquiryDecisionPrompt.JUDGE_V3)));
        InquiryDecisionGenerator planner = arms.get("v2");
        AtomicInteger calls = new AtomicInteger();
        List<String> rows = new ArrayList<>();
        String[] current = new String[1];

        InquiryDecisionModel comparing = new InquiryDecisionModel() {
            @Override
            public boolean enabledFor(UUID orgId) {
                return true;
            }

            @Override
            public Answer<List<InquiryNeed>> plan(UUID orgId, String question) {
                spend(calls, maxCalls);
                Answer<List<InquiryNeed>> a = planner.plan(orgId, planner.planBody(question));
                ObjectNode row = JSON.createObjectNode().put("inquiry", current[0]).put("arm", "plan")
                        .put("failure", a.failure()).put("needs", a.value() == null ? -1 : a.value().size());
                tokens(row, a.cost());
                rows.add(row.toString());
                return a;
            }

            @Override
            public Answer<Map<String, NeedVerdict>> judge(UUID orgId, String question, List<InquiryNeed> needs,
                                                          List<EvidenceCandidate> evidence,
                                                          List<PrecedentCandidate> precedents) {
                Answer<Map<String, NeedVerdict>> first = null;
                List<String> needIds = needs.stream().map(InquiryNeed::id).toList();
                for (int run = 1; run <= repeats; run++) {
                    for (Map.Entry<String, InquiryDecisionGenerator> arm : arms.entrySet()) {
                        spend(calls, maxCalls);
                        InquiryDecisionGenerator g = arm.getValue();
                        Answer<Map<String, NeedVerdict>> a = g.judge(orgId,
                                g.judgeBody(question, needs, evidence, precedents), needIds, evidence.size(),
                                precedents.size());
                        ObjectNode row = JSON.createObjectNode().put("inquiry", current[0]).put("arm", arm.getKey())
                                .put("run", run).put("needs", needs.size()).put("evidence", evidence.size())
                                .put("precedents", precedents.size()).put("failure", a.failure());
                        ObjectNode statuses = row.putObject("status");
                        if (a.value() != null) {
                            a.value().forEach((need, v) -> statuses.put(need, v.status().name()));
                        }
                        tokens(row, a.cost());
                        rows.add(row.toString());
                        if (first == null && "v2".equals(arm.getKey())) {
                            first = a;
                        }
                    }
                }
                return first;
            }
        };
        assessor.setDecision(comparing, collector);

        for (UUID id : ids) {
            current[0] = id.toString();
            Inquiry inquiry = inquiries.findById(id).filter(i -> org.equals(i.getOrgId())).orElse(null);
            if (inquiry == null) {
                rows.add(JSON.createObjectNode().put("inquiry", current[0]).put("arm", "skip")
                        .put("failure", "NOT_IN_ORG").toString());
                continue;
            }
            try {
                assessor.assess(org, inquiry, OrderFactLookup.STORED_ONLY);
            } catch (CapReached e) {
                rows.add(JSON.createObjectNode().put("inquiry", current[0]).put("arm", "stop")
                        .put("failure", "CAP_REACHED").toString());
                break;
            } catch (RuntimeException e) {
                rows.add(JSON.createObjectNode().put("inquiry", current[0]).put("arm", "error")
                        .put("failure", e.getClass().getSimpleName()).toString());
            }
            Files.write(Path.of(System.getenv("CMP_OUT")), rows);
        }
        Files.write(Path.of(System.getenv("CMP_OUT")), rows);
        System.out.println("JUDGE_ARM_COMPARISON calls=" + calls.get() + " rows=" + rows.size());
    }

    private static final class CapReached extends RuntimeException {
    }

    private static void spend(AtomicInteger calls, int max) {
        if (calls.incrementAndGet() > max) {
            throw new CapReached();
        }
    }

    private static void tokens(ObjectNode row, InquiryDecisionModel.CallCost cost) {
        if (cost != null) {
            row.put("inTok", cost.promptTokens()).put("outTok", cost.completionTokens()).put("ms", cost.elapsedMs());
        }
    }
}
