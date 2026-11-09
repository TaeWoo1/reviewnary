package com.sellerops.inquiry.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sellerops.agent.llm.AgentDraftGenerator;
import com.sellerops.agent.llm.AgentDraftService;
import com.sellerops.agent.quota.AgentUsageActor;
import com.sellerops.inquiry.decision.EvidenceCandidate;
import com.sellerops.inquiry.decision.InquiryDecisionModel;
import com.sellerops.inquiry.decision.InquiryDecisionService;
import com.sellerops.inquiry.decision.InquiryEvidenceCollector;
import com.sellerops.inquiry.decision.InquiryNeed;
import com.sellerops.inquiry.decision.NeedVerdict;
import com.sellerops.inquiry.decision.PrecedentCandidate;
import com.sellerops.inquiry.draft.dto.GeneratedDraftView;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * <b>Which step of an inquiry draft changes between runs</b> (2026-11-02 review: real Q1 saved 3/3, then 1/3, with the
 * same inquiry and the same evidence — repeating the whole flow says it moves, not where). Runs the real 「초안 생성」 with
 * the real model, one step held still at a time:
 *
 * <ol>
 *   <li><b>natural</b> — nothing held; the drafter is not called. Each run's need split, collected evidence and verdicts.
 *   <li><b>plan held</b> — the first natural run's need split is reused; collection and the judge run. Same evidence
 *       every time means collection is fixed; verdicts that still move are the judge's own.
 *   <li><b>decision held</b> — a natural run's need split and verdicts are reused; only the drafter and the claim check run.
 * </ol>
 *
 * <p>The decision service's five-minute memo is emptied before every run, so a repeat is a new call. Written as hashes,
 * types, statuses and counts — no inquiry, evidence or draft text — under {@code ~/workspace/sellerops/holds/} only.
 * {@code STAGES_TEXT_OUT} adds a second file with the run's words (question, evidence, verdict reasons, the drafter's
 * instruction lines, the draft, the check's claims), for reading a cause there — never for a report or a log.
 * Off unless {@code RUN_INQUIRY_STAGES=true}; the evaluation copy and the key come from the subprocess that runs it.
 */
@EnabledIfEnvironmentVariable(named = "RUN_INQUIRY_STAGES", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class InquiryDecisionStagesIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    @SpyBean
    private AgentDraftService drafter;
    @Autowired
    private InquiryDraftComposer composer;
    @Autowired
    private InquiryKnowledgeAssessor assessor;
    @Autowired
    private InquiryDecisionService decisions;
    @Autowired
    private InquiryEvidenceCollector collector;

    /** What one run did at each step, and what the next stage may hold still. */
    static final class State {
        InquiryDecisionModel.Answer<List<InquiryNeed>> heldPlan;
        InquiryDecisionModel.Answer<Map<String, NeedVerdict>> heldJudge;
        InquiryDecisionModel.Answer<List<InquiryNeed>> lastPlan;
        InquiryDecisionModel.Answer<Map<String, NeedVerdict>> lastJudge;
        Map<String, Object> trace = new LinkedHashMap<>();
        /** The same run's words — only when STAGES_TEXT_OUT names a file under the holds folder. */
        Map<String, Object> text = new LinkedHashMap<>();
    }

    /** The real decision model with each step recorded, and held when the stage says so. */
    record Recording(InquiryDecisionModel real, State state) implements InquiryDecisionModel {

        @Override
        public boolean enabledFor(UUID orgId) {
            return real.enabledFor(orgId);
        }

        @Override
        public Answer<List<InquiryNeed>> plan(UUID orgId, String question) {
            Answer<List<InquiryNeed>> a = state.heldPlan != null ? state.heldPlan : real.plan(orgId, question);
            state.lastPlan = a;
            state.trace.put("planHeld", state.heldPlan != null);
            if (a.value() != null) {
                state.text.put("needs", a.value().stream().map(n -> n.id() + " [" + n.type() + "] " + n.ask()).toList());
            }
            state.trace.put("needs", a.value() == null ? null : a.value().stream().map(n -> Map.of("id", n.id(),
                    "type", String.valueOf(n.type()), "ask", hash(n.ask()), "search", hash(n.search()))).toList());
            return a;
        }

        @Override
        public Answer<Map<String, NeedVerdict>> judge(UUID orgId, String question, List<InquiryNeed> needs,
                                                      List<EvidenceCandidate> evidence,
                                                      List<PrecedentCandidate> precedents) {
            return judge(orgId, question, needs, evidence, precedents, null);
        }

        @Override
        public Answer<Map<String, NeedVerdict>> judge(UUID orgId, String question, List<InquiryNeed> needs,
                                                      List<EvidenceCandidate> evidence,
                                                      List<PrecedentCandidate> precedents, String received) {
            state.trace.put("evidence", evidence.stream().map(e -> Map.of("id", e.id(), "kind",
                    String.valueOf(e.kind()), "text", hash(e.text()), "source", hash(String.valueOf(e.sourceId())),
                    "scope", String.valueOf(e.scope()))).toList());
            state.trace.put("evidenceSet", hash(String.join("|", evidence.stream().map(e -> e.text()).sorted().toList())));
            state.trace.put("precedents", precedents.size());
            state.text.put("evidence", evidence.stream().map(e -> e.id() + " " + e.label() + " :: " + e.text()).toList());
            Answer<Map<String, NeedVerdict>> a = state.heldJudge != null ? state.heldJudge
                    : real.judge(orgId, question, needs, evidence, precedents, received);
            state.lastJudge = a;
            state.trace.put("judgeHeld", state.heldJudge != null);
            if (a.value() != null) {
                state.text.put("verdicts", a.value().values().stream().map(v -> v.needId() + " " + v.status()
                        + " cited=" + v.evidence() + " missing=" + v.missingInfo() + " customerInput="
                        + v.customerInput() + " assumptions=" + v.assumptions() + " askCustomer=" + v.askCustomer())
                        .toList());
            }
            state.trace.put("verdicts", a.value() == null ? null : a.value().values().stream().map(v -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", v.needId());
                m.put("status", String.valueOf(v.status()));
                m.put("cited", v.evidence());
                m.put("customerInput", v.customerInput() == null ? 0 : v.customerInput().size());
                m.put("missingInfo", v.missingInfo() == null ? 0 : v.missingInfo().size());
                return m;
            }).toList());
            return a;
        }

        @Override
        public InquiryDecisionModel chargedTo(AgentUsageActor actor) {
            return new Recording(real.chargedTo(actor), state);
        }
    }

    static String hash(String s) {
        if (s == null) {
            return null;
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8))).substring(0, 10);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void writeText(Path textOut, List<String> texts, Map<String, Object> row, Map<String, Object> text)
            throws Exception {
        if (textOut == null) {
            return;
        }
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("workItem", row.get("workItem"));
        t.put("stage", row.get("stage"));
        t.put("run", row.get("run"));
        t.putAll(text);
        texts.add(JSON.writeValueAsString(t));
        Files.write(textOut, texts, StandardCharsets.UTF_8);
    }

    @Test
    void stages() throws Exception {
        Path out = Path.of(System.getenv("STAGES_OUT")).toAbsolutePath().normalize();
        assertThat(out.toString()).as("results leave only for the holds folder")
                .startsWith(Path.of(System.getProperty("user.home"), "workspace", "sellerops", "holds").toString());
        UUID org = UUID.fromString(System.getenv("STAGES_ORG"));
        int natural = Integer.parseInt(System.getenv().getOrDefault("STAGES_NATURAL", "5"));
        int planHeld = Integer.parseInt(System.getenv().getOrDefault("STAGES_PLAN_HELD", "5"));
        int drafts = Integer.parseInt(System.getenv().getOrDefault("STAGES_DRAFTS", "3"));

        State state = new State();
        assessor.setDecision(new Recording(decisions, state), collector);
        boolean[] draftLive = {false};
        List<Map<String, Object>> checks = new ArrayList<>();
        List<Integer> draftBytes = new ArrayList<>();
        doAnswer(call -> {
            if (!draftLive[0]) {
                return Optional.empty();
            }
            @SuppressWarnings("unchecked")
            Optional<com.sellerops.agent.llm.AgentDraftResponseParser.ParsedDraft> d =
                    (Optional<com.sellerops.agent.llm.AgentDraftResponseParser.ParsedDraft>) call.callRealMethod();
            d.ifPresent(x -> draftBytes.add(x.comments() == null ? 0
                    : x.comments().getBytes(StandardCharsets.UTF_8).length));
            state.text.put("question", call.getArgument(1) + " / " + call.getArgument(2));
            state.text.put("shown", ((List<AgentDraftGenerator.Passage>) call.getArgument(3)).stream()
                    .map(x -> x.scopeLabel() + " | " + x.heading() + " :: " + x.text()).toList());
            state.text.put("order", call.getArgument(4));
            state.text.put("spec", call.getArgument(5));
            state.text.put("scope", call.getArgument(8));
            state.text.put("draft", d.map(com.sellerops.agent.llm.AgentDraftResponseParser.ParsedDraft::comments)
                    .orElse(null));
            return d;
        }).when(drafter).draft(any(), any(), any(), any(), any(), any(), any(), any(), any());
        doAnswer(call -> draftLive[0] ? call.callRealMethod() : Optional.empty())
                .when(drafter).draft(any(), any(), any(), any(), any(), any(), any(), any());
        doAnswer(call -> {
            AgentDraftGenerator.FigureResult r = (AgentDraftGenerator.FigureResult) call.callRealMethod();
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("reason", r.reason());
            c.put("figures", r.verdicts().map(vs -> vs.stream().map(AgentDraftGenerator.FigureVerdict::verdict).toList())
                    .orElse(null));
            c.put("claims", r.claims().stream().map(AgentDraftGenerator.ClaimVerdict::verdict).toList());
            c.put("answered", r.answered());
            c.put("covered", r.covered());
            checks.add(c);
            state.text.put("checkClaims", r.claims().stream().map(x -> x.verdict() + " :: " + x.text()).toList());
            state.text.put("checkAsked", call.getArgument(7));
            return r;
        }).when(drafter).checkFiguresMetered(any(), any(), any(), any(), any(), any(), any(), any());
        @SuppressWarnings("unchecked")
        Map<String, ?> memo = (Map<String, ?>) ReflectionTestUtils.getField((Object) AopTestUtils.getTargetObject(decisions),
                "memo");

        List<String> rows = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        Path textOut = System.getenv("STAGES_TEXT_OUT") == null ? null
                : Path.of(System.getenv("STAGES_TEXT_OUT")).toAbsolutePath().normalize();
        assertThat(textOut == null || textOut.toString().startsWith(
                Path.of(System.getProperty("user.home"), "workspace", "sellerops", "holds").toString()))
                .as("words leave only for the holds folder").isTrue();
        for (String raw : System.getenv("STAGES_WORK_ITEMS").split(",")) {
            UUID work = UUID.fromString(raw.trim());
            List<InquiryDecisionModel.Answer<List<InquiryNeed>>> plans = new ArrayList<>();
            List<InquiryDecisionModel.Answer<Map<String, NeedVerdict>>> judges = new ArrayList<>();
            List<String> bases = new ArrayList<>();
            for (String stage : List.of("natural", "planHeld", "decisionHeld")) {
                int times = stage.equals("natural") ? natural : stage.equals("planHeld") ? planHeld : drafts;
                if (stage.equals("planHeld")) {
                    state.heldPlan = plans.isEmpty() ? null : plans.get(0);
                    state.heldJudge = null;
                    if (state.heldPlan == null || state.heldPlan.value() == null) {
                        continue;
                    }
                }
                if (stage.equals("decisionHeld")) {
                    // The first natural run whose decision would write a draft; none — nothing to draft from.
                    int pick = -1;
                    for (int i = 0; i < bases.size(); i++) {
                        if (judges.get(i) != null && judges.get(i).value() != null
                                && !"NO_ANSWER_BASIS".equals(bases.get(i))) {
                            pick = i;
                            break;
                        }
                    }
                    if (pick < 0) {
                        continue;
                    }
                    state.heldPlan = plans.get(pick);
                    state.heldJudge = judges.get(pick);
                    draftLive[0] = true;
                }
                for (int run = 1; run <= times; run++) {
                    synchronized (memo) {
                        memo.clear();
                    }
                    state.trace = new LinkedHashMap<>();
                    state.text = new LinkedHashMap<>();
                    state.lastPlan = null;
                    state.lastJudge = null;
                    checks.clear();
                    draftBytes.clear();
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("workItem", raw.trim().substring(0, 8));
                    row.put("stage", stage);
                    row.put("run", run);
                    GeneratedDraftView view;
                    try {
                        view = composer.generateAs(org, work, "SELLER:eval-stages");
                    } catch (com.sellerops.common.ApiException e) {
                        // A draft the save refused (2026-11-02: one over the 1000-byte reply limit) — recorded, not fatal.
                        row.putAll(state.trace);
                        row.put("error", e.getMessage());
                        row.put("draftBytes", new ArrayList<>(draftBytes));
                        row.put("checks", new ArrayList<>(checks));
                        rows.add(JSON.writeValueAsString(row));
                        Files.write(out, rows, StandardCharsets.UTF_8);
                        writeText(textOut, texts, row, state.text);
                        continue;
                    }
                    row.putAll(state.trace);
                    row.put("basis", view.answerBasis());
                    row.put("finalNeeds", view.knowledgeGap() == null || view.knowledgeGap().needs() == null ? null
                            : view.knowledgeGap().needs().stream().map(n -> n.id() + ":" + n.status()).toList());
                    row.put("saved", view.draft() != null);
                    row.put("rewrite", view.rewrite());
                    row.put("unavailable", draftLive[0] ? view.unavailableMessage() : null);
                    row.put("open", view.openQuestions() == null ? 0 : view.openQuestions().size());
                    row.put("checks", new ArrayList<>(checks));
                    row.put("draftBytes", new ArrayList<>(draftBytes));
                    rows.add(JSON.writeValueAsString(row));
                    Files.write(out, rows, StandardCharsets.UTF_8);
                    writeText(textOut, texts, row, state.text);
                    if (stage.equals("natural")) {
                        plans.add(state.lastPlan);
                        judges.add(state.lastJudge);
                        bases.add(view.answerBasis());
                    }
                }
                draftLive[0] = false;
            }
            state.heldPlan = null;
            state.heldJudge = null;
        }
        Files.write(out, rows, StandardCharsets.UTF_8);
    }
}
