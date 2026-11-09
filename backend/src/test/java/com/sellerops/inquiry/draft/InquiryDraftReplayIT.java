package com.sellerops.inquiry.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sellerops.agent.llm.AgentDraftGenerator;
import com.sellerops.agent.llm.AgentDraftResponseParser;
import com.sellerops.agent.llm.AgentDraftService;
import com.sellerops.inquiry.draft.dto.GeneratedDraftView;
import com.sellerops.review.draft.ReviewClaimGuard;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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

/**
 * <b>What the drafter wrote for an inquiry draft that was not kept</b> (2026-10-31: C06 was refused twice for leaving
 * out 「미개봉」, and a refused draft is saved nowhere — so whether the check was right could not be read). Runs the
 * inquiry's real 「초안 생성」 with the real model and writes, per inquiry, every draft the model returned, the seller's
 * reason the composer gave, and what the condition check found in each draft.
 *
 * <p>Off unless {@code RUN_INQUIRY_DRAFT_REPLAY=true}: the evaluation copy database and the drafting key come from the
 * environment of the subprocess that runs it ({@code tools/eval/case-eval/README.md}). The file holds model text that
 * may say back a customer's words, so it may only be written under {@code ~/workspace/sellerops/holds/}.
 */
@EnabledIfEnvironmentVariable(named = "RUN_INQUIRY_DRAFT_REPLAY", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class InquiryDraftReplayIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    @SpyBean
    private AgentDraftService model;
    @Autowired
    private InquiryDraftComposer composer;

    @Test
    void replay() throws Exception {
        Path out = Path.of(System.getenv("REPLAY_OUT")).toAbsolutePath().normalize();
        assertThat(out.toString()).as("model text leaves only for the holds folder")
                .startsWith(Path.of(System.getProperty("user.home"), "workspace", "sellerops", "holds").toString());
        UUID org = UUID.fromString(System.getenv("REPLAY_ORG"));
        List<Map<String, Object>> written = new ArrayList<>();
        doAnswer(call -> {
            @SuppressWarnings("unchecked")
            Optional<AgentDraftResponseParser.ParsedDraft> draft =
                    (Optional<AgentDraftResponseParser.ParsedDraft>) call.callRealMethod();
            List<AgentDraftGenerator.Passage> shown = call.getArgument(3);
            List<String> evidence = shown == null ? List.of() : shown.stream().map(AgentDraftGenerator.Passage::text).toList();
            String asked = String.join(" ", String.valueOf(call.getArgument(1, String.class)),
                    String.valueOf(call.getArgument(2, String.class)));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("draft", draft.map(AgentDraftResponseParser.ParsedDraft::comments).orElse(null));
            row.put("evidenceCount", evidence.size());
            row.put("droppedConditions", draft.map(d -> ReviewClaimGuard.droppedConditions(d.comments(), evidence, asked))
                    .orElse(List.of()));
            written.add(row);
            return draft;
        }).when(model).draft(any(), any(), any(), any(), any(), any(), any(), any(), any());
        // …and what the claim check said about it, number by number (2026-11-02: synthetic Q5 was refused once for 「20」,
        // which both the question and the policy carry, and nothing recorded the verdict).
        doAnswer(call -> {
            AgentDraftGenerator.FigureResult result = (AgentDraftGenerator.FigureResult) call.callRealMethod();
            Map<String, Object> check = new LinkedHashMap<>();
            check.put("asked", call.getArgument(7));
            check.put("reason", result.reason());
            check.put("figures", result.verdicts().map(vs -> vs.stream().map(v -> Map.of("verdict", v.verdict(),
                    "source", String.valueOf(v.source()), "about", String.valueOf(v.about()),
                    "when", String.valueOf(v.when()))).toList()).orElse(null));
            check.put("claims", result.claims().stream().map(c -> Map.of("text", c.text(), "verdict", c.verdict()))
                    .toList());
            check.put("answered", result.answered());
            if (!written.isEmpty()) {
                written.get(written.size() - 1).put("check", check);
            }
            return result;
        }).when(model).checkFiguresMetered(any(), any(), any(), any(), any(), any(), any(), any());

        List<String> rows = new ArrayList<>();
        int repeats = Integer.parseInt(System.getenv().getOrDefault("REPLAY_REPEATS", "1"));
        for (String id : java.util.Collections.nCopies(repeats, System.getenv("REPLAY_WORK_ITEMS").split(","))
                .stream().flatMap(java.util.Arrays::stream).toList()) {
            written.clear();
            GeneratedDraftView view = composer.generateAs(org, UUID.fromString(id.trim()), "SELLER:eval-replay");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("workItem", id.trim().substring(0, 8));
            row.put("saved", view.draft() != null);
            row.put("unavailable", view.unavailableMessage());
            row.put("knowledgeNote", view.knowledgeNote());
            row.put("drafts", new ArrayList<>(written));
            rows.add(JSON.writeValueAsString(row));
        }
        Files.write(out, rows, StandardCharsets.UTF_8);
    }
}
