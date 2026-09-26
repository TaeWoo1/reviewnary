package com.sellerops.attention.reply;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sellerops.attention.triage.ReviewTriageRepository;
import com.sellerops.review.publish.ReviewExecutionCapability;
import com.sellerops.review.publish.ReviewExecutionKind;
import com.sellerops.review.triage.ReviewTriageChannelCapability;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>Preparing an answer and sending one are separate rights, and this pins the separation.</b>
 *
 * <p>The defect: {@link ReviewReplyWorkLookup} minted the reply-work ref only for a channel whose
 * {@code replySupported} column was true (NAVER) — or for one whose account already had
 * {@code API_EXECUTION}. So on Cafe24 with the marketplace write lane off, 확인할 일 listed the review
 * as 「초안 필요」 (that queue reads {@code replyFlowExists}) and the workspace refused to hand out the
 * ref the draft panel mounts on. The seller could see both halves of the contradiction at once, and
 * the demo path — write the answer, approve it, paste it into the mall's admin — was closed by a
 * switch about SENDING.
 *
 * <p>What is asserted here is the shape of the fix, not one screen's behaviour: the preparation gate
 * asks a question about the CHANNEL and cannot see an execution capability at all, and the send gate
 * is unchanged and still refuses anything that is not {@code API_EXECUTION}.
 */
class ReviewReplyPreparationWithoutWriteTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID REVIEW = UUID.randomUUID();

    private static ReviewReplyWorkLookup lookup() {
        ReviewTriageRepository triages = mock(ReviewTriageRepository.class);
        ReviewReplyDraftRepository drafts = mock(ReviewReplyDraftRepository.class);
        ReviewReplyApprovalRepository approvals = mock(ReviewReplyApprovalRepository.class);
        when(triages.findByOrgIdAndReviewId(any(), any())).thenReturn(Optional.empty());
        when(drafts.findReviewIdsWithDraft(any(), anyList())).thenReturn(List.of());
        when(approvals.findReviewIdsWithApproval(any(), anyList())).thenReturn(List.of());
        return new ReviewReplyWorkLookup(triages, drafts, approvals);
    }

    @Test
    @DisplayName("Cafe24 can be drafted, edited and approved with the marketplace write lane off")
    void cafe24PreparesWithoutWrite() {
        // No execution capability is supplied, because the lookup no longer takes one. This IS the
        // posture of a pilot mall before `mall.write_community` re-consent: the adapter bean may not
        // even exist, and the seller may still write the answer they will paste in themselves.
        assertThat(lookup().forReview(ORG, "CAFE24", REVIEW)).isPresent();
        assertThat(lookup().forReview(ORG, "NAVER", REVIEW)).isPresent();
    }

    @Test
    @DisplayName("a channel with no reply flow at all stays closed — Coupang, and everything outside the table")
    void noReplyFlowStaysClosed() {
        assertThat(lookup().forReview(ORG, "COUPANG", REVIEW)).isEmpty();
        for (String outside : List.of("GMARKET", "AUCTION", "ELEVENST", "cafe24", "")) {
            assertThat(lookup().forReview(ORG, outside, REVIEW)).as("%s", outside).isEmpty();
        }
        assertThat(lookup().forReview(ORG, null, REVIEW)).isEmpty();
    }

    @Test
    @DisplayName("the preparation gate is exactly the reply-flow predicate, for every code")
    void gateIsTheReplyFlowPredicate() {
        for (String code : List.of("NAVER", "CAFE24", "COUPANG", "GMARKET", "SSG", "")) {
            assertThat(lookup().forReview(ORG, code, REVIEW).isPresent())
                    .as("%s", code)
                    .isEqualTo(ReviewTriageChannelCapability.of(code).replyFlowExists());
        }
    }

    @Test
    @DisplayName("the preparation gate cannot read an execution capability — it does not name one")
    void preparationCannotSeeTheSendLane() throws Exception {
        // A source scan rather than a behavioural assertion, because the property is that the question
        // is not ASKED. A lookup that took an execution capability and happened to ignore it today is
        // one refactor away from consulting it again, and that refactor is what produced the defect.
        String src = Files.readString(
                Path.of("src/main/java/com/sellerops/attention/reply/ReviewReplyWorkLookup.java"));
        String code = src.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
        assertThat(code).doesNotContain("ReviewExecution");
        assertThat(code).contains("replyFlowExists()");
        assertThat(code).doesNotContain("replySupported");
    }

    @Test
    @DisplayName("the send is unchanged: nothing but API_EXECUTION may post")
    void sendStillRequiresApiExecution() throws Exception {
        String code = Files.readString(
                Path.of("src/main/java/com/sellerops/review/publish/ReviewReplyExecutionService.java"));
        assertThat(code).contains("decision.kind() != ReviewExecutionKind.API_EXECUTION");
    }

    @Test
    @DisplayName("Cafe24 with no adapter and no grant is NOT executable — the send gate still says no")
    void cafe24SendStaysUnsupportedWithoutTheLane() {
        ReviewExecutionCapability disabled = ReviewExecutionCapability.disabled();
        assertThat(disabled.of(ORG, UUID.randomUUID(), "CAFE24").kind())
                .isEqualTo(ReviewExecutionKind.NOT_SUPPORTED);
        assertThat(disabled.of(ORG, UUID.randomUUID(), "COUPANG").kind())
                .isEqualTo(ReviewExecutionKind.NOT_SUPPORTED);
        // …while NAVER is a guided browser flow regardless of configuration, and that did not move.
        assertThat(disabled.of(ORG, UUID.randomUUID(), "NAVER").kind())
                .isEqualTo(ReviewExecutionKind.GUIDED_BROWSER_EXECUTION);
    }

    @Test
    @DisplayName("the guided seller-center handoff belongs to NAVER alone, stated in one place")
    void guidedLaneIsNaverOnly() {
        assertThat(ReviewExecutionCapability.guidedBrowserLane("NAVER")).isTrue();
        for (String other : List.of("CAFE24", "COUPANG", "GMARKET", "naver", "")) {
            assertThat(ReviewExecutionCapability.guidedBrowserLane(other)).as("%s", other).isFalse();
        }
        assertThat(ReviewExecutionCapability.guidedBrowserLane(null)).isFalse();
        // And `of` reads that same predicate, so the channel is named once: every code the capability
        // calls GUIDED_BROWSER_EXECUTION is a code the predicate admits, and no other.
        ReviewExecutionCapability disabled = ReviewExecutionCapability.disabled();
        for (String code : List.of("NAVER", "CAFE24", "COUPANG", "GMARKET", "")) {
            boolean guided = disabled.of(ORG, UUID.randomUUID(), code).kind()
                    == ReviewExecutionKind.GUIDED_BROWSER_EXECUTION;
            assertThat(guided).as("%s", code).isEqualTo(ReviewExecutionCapability.guidedBrowserLane(code));
        }
    }

    @Test
    @DisplayName("the handoff is offered only on the guided lane, and it is refused server-side too")
    void handoffFollowsTheGuidedLane() throws Exception {
        // Cafe24 now reaches the preparation panel, and that panel's handoff step names 네이버
        // 판매자센터 in every sentence it renders. Offering it to a mall owner would instruct them to
        // paste their Cafe24 answer into NAVER — so the capability flag carries the lane, and the mint
        // refuses it as well, for the reason every other affordance here is also enforced: a client
        // that ignores the flag must still be refused.
        String code = Files.readString(
                Path.of("src/main/java/com/sellerops/attention/reply/ReviewReplyService.java"));
        assertThat(code).contains("sourceExecutable && guidedLane)");
        assertThat(code).contains("if (!guidedLane(review.getChannelId()))");
        assertThat(code).contains("ReviewExecutionCapability.guidedBrowserLane(code)");
    }
}
