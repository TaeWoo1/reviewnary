import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { reviewReplyTaskLink } from "../../src/conversation/ConversationService";

const SOURCE = readFileSync(new URL("../../src/conversation/ConversationService.ts", import.meta.url), "utf8");

/**
 * Approval Path v1 §5 — a review action in the conversation points at THAT review's reply work.
 *
 * <b>Why a source scan and not only a behavioural test.</b> The defect being fenced is a constant:
 * every review action artifact shipped `to: "/reviews"`, the channel's whole record — 4,455 rows on the
 * live NAVER account, with the approve button six screens below the fold and nothing on the page
 * saying which row the conversation meant. A regression here is one edited string literal, and the
 * cheapest way to make that impossible is to read the two literals.
 *
 * <b>What is NOT changed by this, and is asserted so:</b> the link is a link. The write fence stands —
 * approving still happens on the seller's own screen, and this conversation gains no approve path.
 */
describe("review action artifacts deep-link to the exact review", () => {
  it("names the review and asks the screen to offer a way back to this conversation", () => {
    expect(reviewReplyTaskLink("rev-1")).toBe("/reviews/reply/rev-1?from=chat");
    // Not the record. That was the bug.
    expect(reviewReplyTaskLink("rev-1")).not.toBe("/reviews");
    // An id is put in a path segment, so it is encoded rather than trusted to be path-safe.
    expect(reviewReplyTaskLink("a/b?c")).toBe("/reviews/reply/a%2Fb%3Fc?from=chat");
  });

  it("every review action artifact uses it — none ships the bare record link", () => {
    for (const marker of [
      'artifactId: `a-guided-${t.reviewId}`',
      'artifactId: `a-approval-${t.reviewId}`',
      // Guided Reply UX Smoothing v1 §1: the in-chat approval card's link is the PRECISION surface
      // (edit / recover), not the normal path — but it still has to name this review.
      'artifactId: `a-approval-required-${t.reviewId}`',
    ]) {
      const start = SOURCE.indexOf(marker);
      expect(start, `${marker} not found — the artifact was renamed, so this fence is not reading it`).toBeGreaterThan(-1);
      // The object literal that starts at the marker, to its closing brace.
      const block = SOURCE.slice(start, SOURCE.indexOf("};", start));
      expect(block).toContain("to: reviewReplyTaskLink(t.reviewId)");
      expect(block).not.toContain('to: "/reviews"');
    }
  });

  const APPROVE_OR_SEND = [
    "decideReviewReplyApproval", "decideReviewApproval", "publishReviewReply",
    "executeReviewReply", "recordReviewReplyOutcome", "startReviewSubmissionRun",
  ];

  // The file, not one method's body. The assertion below slices `routeSend` because that is where the
  // handoff is decided and the slice says so plainly — but a slice ends at the next `private `, so
  // splitting `routeSend` into helpers would leave the fence green while reading a fraction of what it
  // used to. This whole-file pass is what survives that refactor. See `docs/FINDINGS.md` §B10.
  it("no part of the service reaches an approve or send path, however it is later split up", () => {
    for (const forbidden of APPROVE_OR_SEND) {
      expect(SOURCE, `ConversationService.ts names ${forbidden}`).not.toContain(forbidden);
    }
  });

  it("carries no approve or send path of its own — the link is the whole handoff", () => {
    const start = SOURCE.indexOf("private async routeSend(");
    expect(start, "routeSend was renamed — this fence is no longer reading it").toBeGreaterThan(-1);
    const block = SOURCE.slice(start, SOURCE.indexOf("\n  private ", start + 10));
    for (const forbidden of APPROVE_OR_SEND) {
      expect(block, `routeSend must not reach ${forbidden}`).not.toContain(forbidden);
    }
    // Guided Reply UX Smoothing v1 §1: the runtime COMPOSES the approval card and never approves. The
    // seller's press is what calls the approval seam, from the browser, on their own screen.
    expect(block).toContain("replyApprovalStateOf");
  });
});
