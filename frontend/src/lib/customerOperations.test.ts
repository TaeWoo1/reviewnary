import { describe, expect, it } from "vitest";
import {
  cadenceLabel,
  decisionsLine,
  gapRowLine,
  handledLine,
  kstClock,
  scopeLabels,
  sourceHealthLine,
  unobservedLine,
} from "./customerOperations";
import { failureShort, gapActionFullKo, gapActionKo } from "./copy/customerOps";
import type { CustomerOperationsSourceHealth } from "./customerOperationsTypes";

function source(over: Partial<CustomerOperationsSourceHealth>): CustomerOperationsSourceHealth {
  return {
    channelCode: "CAFE24",
    channelNameKo: "카페24",
    dataType: "INQUIRY",
    completeness: "COMPLETE",
    observedCount: 4,
    newCount: 1,
    failureReason: null,
    observedAt: "2026-09-16T05:00:00Z",
    sellerActionRequired: false,
    ...over,
  };
}

describe("고객 운영 관리 — copy invariants", () => {
  it("0 items is a finding; could-not-observe never prints a number", () => {
    const zero = sourceHealthLine(source({ observedCount: 0, newCount: 0 }));
    expect(zero.text).toBe("Cafe24 문의 — 확인함 · 새로 들어온 것 없음");
    const none = sourceHealthLine(source({ completeness: "NONE", observedCount: null, newCount: null, failureReason: "TIMEOUT" }));
    expect(none.text).toContain("확인하지 못했어요");
    expect(none.text).not.toMatch(/\d+건/);
    expect(none.text).not.toContain("없음");
  });

  it("PARTIAL and BOUNDED never read as fine", () => {
    for (const s of [source({ completeness: "PARTIAL", failureReason: "EXECUTION_FAILED" }), source({ completeness: "BOUNDED" })]) {
      const line = sourceHealthLine(s);
      expect(line.text).toContain("일부");
      expect(line.text).not.toContain("정상");
      expect(line.tone).toBe("warn");
    }
  });

  it("observed is not processed, and prepared is said with not-sent", () => {
    expect(
      handledLine({ since: null, autoResolved: 0, monitoring: 0, draftsPrepared: 0, verifying: 0, rows: [] }),
    ).toBe("최근 24시간 동안 정리하거나 준비한 일은 없어요.");
    const line = handledLine({
      since: null, autoResolved: 2, monitoring: 1, draftsPrepared: 1, verifying: 0, rows: [],
    });
    expect(line).toContain("2건을 정리했어요");
    expect(line).toContain("1건을 지켜보고 있어요");
    expect(line).toContain("아직 보내지 않았어요");
    expect(line).not.toMatch(/4건|처리했어요|보냈어요/);
    // The denominator leads, and is never the sum of the parts.
    const withChecked = handledLine({
      since: null, autoResolved: 2, monitoring: 1, draftsPrepared: 1, verifying: 0, rows: [], checked: 9,
    });
    expect(withChecked.startsWith("최근 24시간 동안 9건을 확인했어요.")).toBe(true);
  });

  it("a decided case being read back is said as reading, never as sent", () => {
    const line = handledLine({
      since: null, autoResolved: 0, monitoring: 0, draftsPrepared: 0, verifying: 2, rows: [],
    });
    expect(line).toContain("승인한 작업의 처리 결과를 확인하고 있어요");
    // The execution record owns delivery. Until it speaks, this area may not.
    expect(line).not.toMatch(/보냈어요|전송했어요|처리 완료|처리했어요|등록했어요/);
    // Nothing is summed across populations: two being verified is not two of anything else.
    expect(line).not.toMatch(/2건을 정리했어요|2건을 지켜보고/);
  });

  it("the three areas speak of their own population only", () => {
    expect(decisionsLine(0)).toBe("지금 직접 판단할 일은 없어요.");
    expect(decisionsLine(2)).toBe("직접 판단할 일이 2건 있어요.");
    expect(unobservedLine({ gaps: { total: 1, rows: [] }, sources: [], lastCheckedAt: null })).toBe(
      "다시 연결해야 확인할 수 있는 곳이 1곳 있어요.",
    );
    expect(unobservedLine({ gaps: { total: 0, rows: [] }, sources: [], lastCheckedAt: null })).toBe("아직 첫 확인이 끝나지 않았어요.");
    expect(
      unobservedLine({ gaps: { total: 0, rows: [] }, sources: [source({}), source({ completeness: "NONE", observedCount: null })], lastCheckedAt: "x" }),
    ).toContain("끝까지 보지 못한 곳");
    expect(unobservedLine({ gaps: { total: 0, rows: [] }, sources: [source({})], lastCheckedAt: "x" })).toBe(
      "지난 확인에서 모든 대상을 끝까지 확인했어요.",
    );
    expect(
      gapRowLine({ caseId: "g", channelCode: "CAFE24", channelNameKo: "카페24", reason: "SOURCE_AUTH_REQUIRED", dataTypes: ["INQUIRY", "REVIEW"], since: "x", lastSeenAt: "x", to: "/connect/cafe24" }),
    ).toBe("Cafe24 문의·리뷰 — 연결이 만료되어 확인하지 못했어요.");
  });

  it("a signed-out browser is not an expired connection", () => {
    // The standing automatic check reads the seller centre with the seller's own session; the API connection
    // beside it may be fine. Telling them to re-authorise it would be the wrong repair.
    const signedOut = {
      caseId: "g",
      channelCode: "NAVER",
      channelNameKo: "네이버 스마트스토어",
      reason: "SOURCE_SIGN_IN_REQUIRED",
      dataTypes: ["REVIEW"],
      since: "x",
      lastSeenAt: "x",
      to: "/connect/naver",
    };
    expect(gapRowLine(signedOut)).toBe("네이버 리뷰 — 판매자센터에 로그인이 필요해 확인하지 못했어요.");
    expect(gapRowLine(signedOut)).not.toContain("연결");
    expect(failureShort("SOURCE_SIGN_IN_REQUIRED")).toBe("로그인 필요");
    expect(gapActionKo("SOURCE_SIGN_IN_REQUIRED")).toBe("로그인");
    expect(gapActionFullKo("SOURCE_SIGN_IN_REQUIRED")).toBe("판매자센터 로그인하기");
    // The connection words stay exactly where they were true.
    expect(failureShort("SOURCE_AUTH_REQUIRED")).toBe("연결 만료");
    expect(gapActionKo("SOURCE_AUTH_REQUIRED")).toBe("재연결");
    expect(gapActionFullKo("SOURCE_NOT_CONNECTED")).toBe("다시 연결하기");
    // An unknown token guesses no repair.
    expect(gapRowLine({ ...signedOut, reason: "SOMETHING_NEW" })).toContain("확인하지 못해");
  });

  it("scope, cadence and Korea time", () => {
    expect(scopeLabels(["CAFE24:INQUIRY", "CAFE24:REVIEW", "MYSTERY:X"])).toEqual(["Cafe24 문의", "Cafe24 리뷰"]);
    expect(cadenceLabel(120)).toBe("2시간마다");
    const now = new Date("2026-09-16T05:30:00Z"); // 14:30 KST
    expect(kstClock("2026-09-16T05:02:00Z", now)).toBe("오늘 14:02");
    expect(kstClock("2026-09-16T07:00:00Z", now)).toBe("오늘 16:00");
    expect(kstClock("2026-09-15T13:00:00Z", now)).toBe("어제 22:00");
    expect(kstClock("2026-09-16T15:00:00Z", now)).toBe("내일 00:00");
    expect(kstClock("2026-09-13T05:00:00Z", now)).toBe("9월 13일 14:00");
    expect(kstClock(null, now)).toBeNull();
  });
});
