import { describe, expect, it } from "vitest";
import {
  DECISION_ACTION_WORD,
  DECISION_DONE_LABEL,
  DECISION_LOG_DISCLOSURE,
  DECISION_LOG_DISCLOSURE_EXECUTED,
  decisionLogDisclosure,
  decisionLogSentence,
} from "./reviewDecision";
import { TRIAGE_OPTIONS } from "./vocItems";
import type { ReviewDecisionLogEntry } from "./types";

function entry(over: Partial<ReviewDecisionLogEntry>): ReviewDecisionLogEntry {
  return { kind: "ACTION_CHOSEN", from: null, to: null, at: "2026-09-10T00:00:00Z", ...over };
}

/**
 * The workspace's words for decisions the product already records.
 *
 * The thing worth pinning is the refusals: an entry this build cannot name renders as NOTHING rather
 * than as its own raw token, because a `SOMETHING_NEW` on a seller's screen is the internal-word leak
 * this product removes everywhere else — and the server's vocabulary can always grow ahead of a
 * deployed bundle.
 */
describe("decisionLogSentence", () => {
  it("says what the seller decided, naming the value they left when there was one", () => {
    expect(decisionLogSentence(entry({ kind: "SELLER_JUDGMENT_SET", to: "NEEDS_ATTENTION" })))
      .toBe("판매자 판단을 확인 필요(으)로 기록");
    expect(decisionLogSentence(entry({ kind: "SELLER_JUDGMENT_SET", from: "FYI", to: "NEEDS_ATTENTION" })))
      .toBe("판매자 판단을 참고에서 확인 필요로 바꿈");
    expect(decisionLogSentence(entry({ kind: "SELLER_JUDGMENT_WITHDRAWN", from: "FYI" })))
      .toBe("판매자 판단을 되돌림");
    expect(decisionLogSentence(entry({ kind: "ACTION_CHOSEN", to: "MONITOR" })))
      .toBe("조치를 두고 보기(으)로 정함");
    expect(decisionLogSentence(entry({ kind: "ACTION_RECORDED", to: "ACTION_COMPLETED" })))
      .toBe("조치를 완료했다고 기록");
    expect(decisionLogSentence(entry({ kind: "REPLY_APPROVAL", to: "APPROVED" })))
      .toBe("답변을 승인");
    expect(decisionLogSentence(entry({ kind: "REPLY_OUTCOME", to: "OPERATOR_REPORTED_SUBMITTED" })))
      .toBe("판매자센터에 올렸다고 기록");
  });

  /**
   * Review Delivery Truth Spine v1 — what reviewnary itself did, said at exactly the strength it was proven.
   *
   * The pair that matters is the last two: a hash read-back may say the channel holds the approved text, and the
   * guided lane's ceiling may not. A screen that said 등록 완료 for both would be claiming NAVER's content was
   * verified, which nothing in the product can check.
   */
  it("says how much of a delivery was actually proven, and no more", () => {
    expect(decisionLogSentence(entry({ kind: "REPLY_EXECUTION", from: "POSTED", to: "VERIFIED" })))
      .toBe("채널에 답변을 등록하고 내용까지 확인함");
    expect(decisionLogSentence(entry({ kind: "REPLY_EXECUTION", from: "POSTED", to: "DELIVERY_UNKNOWN" })))
      .toBe("채널에 답변을 보냈지만 등록됐는지 확인하지 못함");
    expect(decisionLogSentence(entry({ kind: "REPLY_EXECUTION", from: null, to: "REFUSED" })))
      .toBe("답변을 보내지 않음");
    expect(decisionLogSentence(entry({
      kind: "REPLY_EXECUTION",
      from: "SELLER_SUBMISSION_OBSERVED",
      to: "SUBMISSION_OBSERVED_CONTENT_UNVERIFIED",
    }))).toBe("채널에 답변이 생긴 것을 확인함 — 내용은 확인하지 못함");
    expect(decisionLogSentence(entry({ kind: "REPLY_EXECUTION", to: "SOMETHING_NEW" }))).toBeNull();
  });

  /**
   * The safety line has to follow the record.
   *
   * 「마켓플레이스에는 아무것도 전송되지 않습니다」 was true of every screen that only ever recorded decisions.
   * It stops being true the moment the log shows an execution reviewnary performed, and a disclosure that is
   * false on one review is worth nothing on the others.
   */
  it("stops promising nothing was sent once something was", () => {
    expect(decisionLogDisclosure([])).toBe(DECISION_LOG_DISCLOSURE);
    expect(decisionLogDisclosure([entry({ kind: "REPLY_APPROVAL", to: "APPROVED" })]))
      .toBe(DECISION_LOG_DISCLOSURE);
    // A refused execution sent nothing, so the original promise still holds.
    expect(decisionLogDisclosure([entry({ kind: "REPLY_EXECUTION", to: "REFUSED" })]))
      .toBe(DECISION_LOG_DISCLOSURE);
    expect(decisionLogDisclosure([entry({ kind: "REPLY_EXECUTION", from: "POSTED", to: "VERIFIED" })]))
      .toBe(DECISION_LOG_DISCLOSURE_EXECUTED);
    expect(DECISION_LOG_DISCLOSURE_EXECUTED).not.toContain("아무것도 전송되지 않습니다");
  });

  it("renders nothing for a value it cannot name — never the raw token", () => {
    expect(decisionLogSentence(entry({ kind: "ACTION_RECORDED", to: "SOMETHING_NEW" }))).toBeNull();
    expect(decisionLogSentence(entry({ kind: "ACTION_CHOSEN", to: "SOMETHING_NEW" }))).toBeNull();
    expect(decisionLogSentence(entry({ kind: "REPLY_APPROVAL", to: null }))).toBeNull();
    expect(decisionLogSentence(entry({ kind: "SELLER_JUDGMENT_SET", to: "UNKNOWN_TIER" }))).toBeNull();
  });

  /**
   * The workspace does not rename the three decisions. A second set of words for one enum would leave
   * the worklist, the record and the audit trail describing the same values differently.
   */
  it("uses the product's existing words for the decision, not new ones", () => {
    for (const option of TRIAGE_OPTIONS) {
      expect(DECISION_ACTION_WORD[option.value]).toBe(option.label);
    }
  });

  /**
   * 조치 불필요 is `TriageDisposition.NO_ACTION`'s statement and it is made in the decision spine, with
   * its own trail. Offering it here as well would put one press into two spines at two evidential
   * strengths — the thing the triage contract §5-C says must not happen.
   */
  it("offers no 조치 불필요 in the 완료 기록 controls", () => {
    expect(Object.keys(DECISION_DONE_LABEL)).toEqual(["ACTION_STARTED", "ACTION_COMPLETED"]);
    expect(Object.keys(DECISION_DONE_LABEL)).not.toContain("ACTION_NOT_NEEDED");
  });
});
