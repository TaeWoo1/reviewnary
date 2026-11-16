package com.sellerops.inquiry.decision;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sellerops.agent.llm.AgentLlmTransport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * <b>A failed decision call says which refusal it was, and an evaluation keeps what came back</b> (2026-11-16 review
 * B178 · §7.55). The reader accepts exactly what it accepted before; the caller's failure word is unchanged; the
 * response text reaches only the evaluation's own file, never the application log.
 */
@ExtendWith(OutputCaptureExtension.class)
class EvalDecisionTraceTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String PLAN_OK = "{\"needs\":[{\"type\":\"PRODUCT_SPEC\",\"ask\":\"몇 cm인가요\",\"search\":\"길이\"}]}";

    @Test
    @DisplayName("each refusal of the plan reader is named — and the reader accepts exactly what it did before")
    void thePlanRefusalIsNamed() {
        assertThat(InquiryDecisionGenerator.readPlan(null).detail()).isEqualTo("EMPTY");
        assertThat(InquiryDecisionGenerator.readPlan("  ").detail()).isEqualTo("EMPTY");
        assertThat(InquiryDecisionGenerator.readPlan("{\"needs\":[{\"type\":").detail()).isEqualTo("JSON_SYNTAX");
        assertThat(InquiryDecisionGenerator.readPlan("not json").detail()).isEqualTo("JSON_SYNTAX");
        assertThat(InquiryDecisionGenerator.readPlan("{\"need\":[]}").detail()).isEqualTo("NO_NEEDS_ARRAY");
        assertThat(InquiryDecisionGenerator.readPlan("{\"needs\":[{\"type\":\"WEATHER\",\"ask\":\"a\"}]}").detail())
                .isEqualTo("UNKNOWN_TYPE");
        assertThat(InquiryDecisionGenerator.readPlan("{\"needs\":[{\"type\":\"PRODUCT_SPEC\",\"ask\":\" \"}]}").detail())
                .isEqualTo("BLANK_ASK");
        assertThat(InquiryDecisionGenerator.readPlan(PLAN_OK).detail()).isNull();

        List<String> corpus = new ArrayList<>(List.of(PLAN_OK, "{\"needs\":[]}", "{\"needs\":{}}", "[]", "null", "{}",
                "{\"needs\":[{\"type\":\"PRODUCT_SPEC\"}]}", "{\"needs\":[{\"ask\":\"a\"}]}",
                "{\"needs\":[{\"type\":\"PRODUCT_SPEC\",\"ask\":\"" + "가".repeat(200) + "\"}]}",
                "{\"needs\":[" + PLAN_OK.substring(10, PLAN_OK.length() - 2) + "," + PLAN_OK.substring(10, PLAN_OK.length() - 2) + "]}",
                "```json\n" + PLAN_OK + "\n```", PLAN_OK + " trailing", "{\"needs\":[{\"type\":\"policy\",\"ask\":\"b\"}]}",
                "{\"needs\":[{\"type\":null,\"ask\":\"b\"}]}", "{\"needs\":[1,2]}", "", "{\"needs\":[{\"type\":\"PRODUCT_SPEC\""));
        for (NeedType t : NeedType.values()) {
            corpus.add("{\"needs\":[{\"type\":\"" + t.name() + "\",\"ask\":\"x\",\"search\":\"\"}]}");
        }
        for (String c : corpus) {
            List<InquiryNeed> now = InquiryDecisionGenerator.parsePlan(c);
            assertThat(Objects.equals(now, legacyParsePlan(c))).as(c).isTrue();
            assertThat(InquiryDecisionGenerator.readPlan(c).detail() == null).as(c).isEqualTo(now != null);
        }
    }

    @Test
    @DisplayName("the judge's failure word is unchanged; the detail tells the set refusals apart")
    void theJudgeRefusalIsNamed() {
        List<String> ids = List.of("N1", "N2");
        String v1 = "{\"need\":\"N1\",\"status\":\"FULL\"}";
        String v2 = "{\"need\":\"N2\",\"status\":\"NONE\"}";
        assertThat(judged("{\"verdicts\":[" + v1 + "]}", ids)).containsExactly("VERDICT_SET", "MISSING_NEED");
        assertThat(judged("{\"verdicts\":[" + v1 + "," + v1 + "]}", ids)).containsExactly("VERDICT_SET", "DUPLICATE_NEED");
        assertThat(judged("{\"verdicts\":[{\"need\":\"N9\",\"status\":\"FULL\"}]}", ids)).containsExactly("VERDICT_SET", "UNKNOWN_NEED");
        assertThat(judged("{\"verdicts\":[{\"need\":\"N1\",\"status\":\"MAYBE\"}," + v2 + "]}", ids))
                .containsExactly("VERDICT_SET", "BAD_STATUS");
        assertThat(judged("{\"verdicts\":{}}", ids)).containsExactly("UNPARSEABLE", "NO_VERDICTS_ARRAY");
        assertThat(judged("{\"verdicts\":[", ids)).containsExactly("UNPARSEABLE", "JSON_SYNTAX");
        assertThat(judged("", ids)).containsExactly("EMPTY", "EMPTY");
        assertThat(judged("{\"verdicts\":[" + v1 + "," + v2 + "]}", ids)).containsExactly(null, null);
        InquiryDecisionGenerator.Judged keyed = InquiryDecisionGenerator.parseKeyedJudge(
                "{\"verdicts\":{\"N1\":{\"status\":\"FULL\"},\"N1\":{\"status\":\"NONE\"}}}", ids);
        assertThat(keyed.failure()).isEqualTo("VERDICT_SET");
        assertThat(keyed.detail()).isEqualTo("DUPLICATE_KEY");
        assertThat(InquiryDecisionGenerator.parseKeyedJudge("{\"verdicts\":[]}", ids).detail()).isEqualTo("NO_VERDICTS_OBJECT");
    }

    @Test
    @DisplayName("an evaluation keeps the failed response and the request's fingerprint — the log keeps neither")
    void anEvaluationKeepsTheFailedResponse(@TempDir Path dir, CapturedOutput output) throws Exception {
        String odd = "{\"need\":[\"합성-응답-표지\"]}";
        InquiryDecisionGenerator g = generator(odd);
        g.setTrace(new EvalDecisionTrace(true, dir.toString()));
        String body = g.planBody("합성 질문: 이 텀블러 몇 ml인가요?");
        InquiryDecisionModel.Answer<List<InquiryNeed>> a = g.plan(JudgeIntegrationContractTest.ORG, body);
        assertThat(a.failure()).as("the caller's word is unchanged").isEqualTo("UNPARSEABLE");
        g.plan(JudgeIntegrationContractTest.ORG, body);

        Path file = dir.resolve(EvalDecisionTrace.FILE);
        List<String> lines = Files.readAllLines(file);
        assertThat(lines).hasSize(2);
        JsonNode line = JSON.readTree(lines.get(0));
        assertThat(line.path("phase").asText()).isEqualTo("plan");
        assertThat(line.path("failure").asText()).isEqualTo("UNPARSEABLE");
        assertThat(line.path("detail").asText()).isEqualTo("NO_NEEDS_ARRAY");
        assertThat(line.path("finish").asText()).isEqualTo("stop");
        assertThat(line.path("response").asText()).isEqualTo(odd);
        assertThat(line.path("requestSha256").asText()).isEqualTo(EvalDecisionTrace.sha256(body))
                .isEqualTo(JSON.readTree(lines.get(1)).path("requestSha256").asText()).hasSize(64);
        assertThat(line.path("requestChars").asInt()).isEqualTo(body.length());
        assertThat(body).as("the key is a header, never in the fingerprinted body").doesNotContain("sk-test");
        assertThat(lines.get(0)).doesNotContain("sk-test");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");

        assertThat(output.getOut()).contains("phase=plan").contains("detail=NO_NEEDS_ARRAY");
        assertThat(output.getOut()).as("no response text in the log").doesNotContain("합성-응답-표지");
        assertThat(output.getOut()).as("no question text in the log").doesNotContain("합성 질문");
    }

    @Test
    @DisplayName("a success keeps the fingerprints only; off on a real board or without a directory")
    void aSuccessKeepsFingerprintsOnly(@TempDir Path dir) throws Exception {
        InquiryDecisionGenerator g = generator(PLAN_OK);
        g.setTrace(new EvalDecisionTrace(true, dir.toString()));
        assertThat(g.plan(JudgeIntegrationContractTest.ORG, g.planBody("q")).failure()).isNull();
        JsonNode line = JSON.readTree(Files.readAllLines(dir.resolve(EvalDecisionTrace.FILE)).get(0));
        assertThat(line.has("response")).isFalse();
        assertThat(line.path("responseSha256").asText()).isEqualTo(EvalDecisionTrace.sha256(PLAN_OK));
        assertThat(line.path("failure").isNull()).isTrue();

        for (EvalDecisionTrace off : List.of(new EvalDecisionTrace(false, dir.resolve("a").toString()),
                new EvalDecisionTrace(true, " "))) {
            InquiryDecisionGenerator h = generator("{}");
            h.setTrace(off);
            h.plan(JudgeIntegrationContractTest.ORG, h.planBody("q"));
            assertThat(off.enabled()).isFalse();
        }
        assertThat(Files.exists(dir.resolve("a"))).isFalse();
    }

    private static InquiryDecisionGenerator generator(String content) {
        return new InquiryDecisionGenerator(
                JudgeIntegrationContractTest.answering(200, JudgeIntegrationContractTest.envelope(content)),
                JudgeIntegrationContractTest.props(InquiryDecisionPrompt.JUDGE_V2, "", "json_schema"));
    }

    private static List<String> judged(String content, List<String> ids) {
        InquiryDecisionGenerator.Judged j = InquiryDecisionGenerator.parseJudge(content, ids);
        return java.util.Arrays.asList(j.failure(), j.detail());
    }

    /** The reader exactly as it stood before B178 — the oracle for 「accepts exactly what it did」. */
    private static List<InquiryNeed> legacyParsePlan(String content) {
        try {
            if (content == null || content.isBlank()) {
                return null;
            }
            JsonNode needs = JSON.readTree(content).path("needs");
            if (!needs.isArray()) {
                return null;
            }
            List<InquiryNeed> out = new ArrayList<>();
            for (JsonNode n : needs) {
                NeedType type = NeedType.parse(n.path("type").asText(null));
                String ask = n.path("ask").asText("").strip();
                if (type == null || ask.isEmpty()) {
                    return null;
                }
                if (ask.length() > InquiryDecisionGenerator.MAX_ASK) {
                    ask = ask.substring(0, InquiryDecisionGenerator.MAX_ASK);
                }
                String search = n.path("search").asText("").strip();
                out.add(new InquiryNeed("N" + (out.size() + 1), ask, type, search.isEmpty() ? ask : search));
            }
            return List.copyOf(out);
        } catch (Exception e) {
            return null;
        }
    }
}
