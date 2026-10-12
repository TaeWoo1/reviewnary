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

  /**
   * **The method's body, and only that.** This used to be
   * `SOURCE.slice(start, SOURCE.indexOf("\n  private ", start + 10))`. `routeSend` is the LAST
   * `private` member of the class, so that `indexOf` returned `-1`, `slice(start, -1)` ran to the end
   * of the file, and the fence read ~1,186 lines while saying it read one method's 120. It passed for
   * reasons that had nothing to do with `routeSend`. A missing terminator must fail, not widen.
   * See `docs/FINDINGS.md` §B12.
   */
  function methodBody(signature: string): string {
    const start = SOURCE.indexOf(signature);
    expect(start, `${signature} not found — this fence is no longer reading it`).toBeGreaterThan(-1);
    // Whichever comes first: the next member at class indent, or the class's own closing brace.
    const ends = ["\n  private ", "\n  public ", "\n  protected ", "\n  async ", "\n}"]
      .map((marker) => SOURCE.indexOf(marker, start + signature.length))
      .filter((at) => at > -1);
    expect(ends.length, `no end boundary after ${signature} — the slice would run to EOF`).toBeGreaterThan(0);
    return SOURCE.slice(start, Math.min(...ends));
  }

  it("carries no approve or send path of its own — the link is the whole handoff", () => {
    const block = methodBody("private async routeSend(");
    // 120 lines at the time of writing. The bound is not a line count — it is the property that the
    // slice was actually CUT SHORT of the rest of the file. An absolute cap decays: this said `< 400`
    // while the runaway it guarded was 1,186 lines, and after the service was split the same runaway
    // became 295 lines and would have passed the cap. Compare against the file itself instead, so the
    // assertion keeps its meaning at any size. See `docs/FINDINGS.md` §B12 · §B16.
    const toEndOfFile = SOURCE.slice(SOURCE.indexOf("private async routeSend(")).split("\n").length;
    expect(block.split("\n").length, "routeSend slice runs to the end of the file")
      .toBeLessThan(toEndOfFile);
    for (const forbidden of APPROVE_OR_SEND) {
      expect(block, `routeSend must not reach ${forbidden}`).not.toContain(forbidden);
    }
    // Guided Reply UX Smoothing v1 §1: the runtime COMPOSES the approval card and never approves. The
    // seller's press is what calls the approval seam, from the browser, on their own screen.
    expect(block).toContain("replyApprovalStateOf");
  });
});
