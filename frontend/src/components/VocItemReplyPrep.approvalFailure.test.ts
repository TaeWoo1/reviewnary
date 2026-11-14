import { describe, expect, it } from "vitest";
import { approvalFailureMessage } from "./VocItemReplyPrep";

/**
 * Why 확정 was refused, in the line under it (2026-11-09 demo §7.10 3A). Only a 409 whose message held 「확인」 used to be
 * read as the review's state; every other refusal said 「다시 눌러 주세요」 about a press that could not work.
 */
describe("approvalFailureMessage", () => {
  it("points at the hold above the reply for a recheck hold", () => {
    expect(approvalFailureMessage("APPROVED", 409, "이 답변은 다시 확인하고 진행해 주세요.")).toContain("위의 안내를 확인해");
  });

  it("says the server's own sentence for any other refusal of the review's state", () => {
    expect(approvalFailureMessage("APPROVED", 409, "채널에 이미 답변이 등록된 리뷰예요. 새 답변은 준비하지 않아요."))
      .toBe("채널에 이미 답변이 등록된 리뷰예요. 새 답변은 준비하지 않아요.");
    expect(approvalFailureMessage("APPROVED", 409, "담당자에게 맡긴 리뷰예요. 여기서 승인하거나 등록하지 않아요."))
      .not.toContain("다시 눌러");
    expect(approvalFailureMessage("WITHDRAWN", 409, "승인된 초안이 없어요.")).toBe("승인된 초안이 없어요.");
  });

  it("never asks for a second press on a 409, even without a message", () => {
    expect(approvalFailureMessage("APPROVED", 409, undefined)).toContain("새로고침");
    expect(approvalFailureMessage("APPROVED", 409, undefined)).not.toContain("다시 눌러");
  });

  it("asks for another press only when the server was not reached or failed", () => {
    expect(approvalFailureMessage("APPROVED", undefined, undefined)).toBe("확정하지 못했어요. 다시 눌러 주세요.");
    expect(approvalFailureMessage("WITHDRAWN", 500, "boom")).toBe("확정을 풀지 못했어요. 다시 눌러 주세요.");
  });
});
