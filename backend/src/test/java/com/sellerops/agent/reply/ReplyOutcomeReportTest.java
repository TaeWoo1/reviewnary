package com.sellerops.agent.reply;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sellerops.attention.reply.ReviewReplyService;
import com.sellerops.auth.AuthPrincipal;
import com.sellerops.auth.runtoken.ReplyAgentRun;
import com.sellerops.auth.runtoken.ReplyAgentRunRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What the agent's work came to — holds, approved as written, approved after an edit (second review, 2026-10-28). */
class ReplyOutcomeReportTest {

    private static final UUID ORG = UUID.randomUUID();

    private static ReplyAgentRun run(String outcome, String reason, Integer version) {
        ReplyAgentRun r = new ReplyAgentRun();
        r.setOrgId(ORG);
        r.setSubjectId(UUID.randomUUID());
        r.setOutcome(outcome);
        r.setHandoffReason(reason);
        r.setDraftVersion(version);
        return r;
    }

    @Test
    @DisplayName("runs by ending, handoffs by reason, and how the saved drafts were approved")
    void theOutcomesAreCounted() {
        ReplyAgentRun asWritten = run("SAVED", null, 2);
        ReplyAgentRun earlierOfSame = run("SAVED", null, 1);
        earlierOfSame.setSubjectId(asWritten.getSubjectId());
        ReplyAgentRun edited = run("SAVED", null, 1);
        ReplyAgentRun rewritten = run("SAVED", null, 1);
        ReplyAgentRun pending = run("SAVED", null, 1);
        ReplyAgentRunRepository runs = mock(ReplyAgentRunRepository.class);
        when(runs.findByOrgIdAndFinishedAtAfter(eq(ORG), any())).thenReturn(List.of(earlierOfSame, asWritten, edited,
                rewritten, pending, run("HANDOFF", "CATEGORY_S", null), run("HANDOFF", "KNOWLEDGE_MISSING", null),
                run("FAILED", null, null)));
        ReviewReplyService replies = mock(ReviewReplyService.class);
        Instant drafted = Instant.parse("2026-10-28T01:00:00Z");
        when(replies.approvedVersion(ORG, asWritten.getSubjectId()))
                .thenReturn(Optional.of(Map.entry(2, drafted.plusSeconds(600))));
        when(replies.approvedVersion(ORG, edited.getSubjectId()))
                .thenReturn(Optional.of(Map.entry(3, drafted.plusSeconds(1800))));
        when(replies.approvedVersion(ORG, rewritten.getSubjectId()))
                .thenReturn(Optional.of(Map.entry(2, drafted.plusSeconds(3000))));
        when(replies.draftAuthorKind(edited.getSubjectId(), 3)).thenReturn(Optional.of("SELLER"));
        when(replies.draftAuthorKind(rewritten.getSubjectId(), 2)).thenReturn(Optional.of("MODEL"));
        when(replies.approvedVersion(ORG, pending.getSubjectId())).thenReturn(Optional.empty());
        when(replies.draftWrittenAt(any(), any(Integer.class))).thenReturn(Optional.of(drafted));

        ReplyStartController.OutcomeView view = new ReplyStartController(null, null, replies, runs)
                .outcomes(new AuthPrincipal(UUID.randomUUID(), ORG, "seller@example.com"), 7);

        assertThat(view.runs()).isEqualTo(8);
        assertThat(view.byOutcome()).containsEntry("SAVED", 5).containsEntry("HANDOFF", 2).containsEntry("FAILED", 1);
        assertThat(view.handoffReasons()).containsEntry("CATEGORY_S", 1).containsEntry("KNOWLEDGE_MISSING", 1);
        assertThat(view.saved()).as("one review run twice is one review").isEqualTo(4);
        assertThat(view.approvedAsWritten()).isEqualTo(1);
        assertThat(view.approvedAfterEdit()).isEqualTo(1);
        assertThat(view.approvedAfterRewrite()).as("a newer version the AI wrote is not the seller's edit").isEqualTo(1);
        assertThat(view.waiting()).isEqualTo(1);
        assertThat(view.medianMinutesToApproval()).isEqualTo(30L);
    }
}
