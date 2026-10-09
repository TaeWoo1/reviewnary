import { describe, expect, it } from "vitest";
import { readingFailureCode, NAVER_OBSERVE_FAILURE_CODES } from "../../src/aside/naver-review-observe-runner.js";
import { NAVER_REVIEW_LIST_URL, NAVER_REVIEW_READ_WORKFLOW } from "../../src/aside/naver-review-workflow.js";
import { buildSignInRuntimePlan } from "../../src/aside/sign-in-executor.js";
import { NAVER_REVIEW_MANAGEMENT_LANDING_URL } from "../../src/cli/local-agent.js";

/**
 * <b>거절은 자기 이름을 가지고 기록에 도착해야 한다.</b>
 *
 * <p>2026-10-10 라이브에서 자동 확인이 01:44와 01:48에 연속으로 SURFACE_UNREADABLE로 끝났다. 기록에 남은
 * 실패 코드는 READING_REFUSED 하나였고, 페이지가 실제로 한 말(GRID_NOT_FOUND)을 알려면 판매자 Mac의
 * helper 로그를 열어야 했다. 여덟 개의 서로 다른 사실이 한 단어 뒤에 있었다.
 */
describe("읽기 거절은 페이지가 쓴 단어로 기록된다", () => {
  for (const reason of ["GRID_NOT_FOUND", "MODEL_UNREADABLE", "MODEL_SHAPE_CHANGED",
    "ROWS_NOT_LOADED", "ID_LINK_MISMATCH", "TOO_MANY_ROWS", "ROUTE_MISMATCH"] as const) {
    it(`${reason}는 그대로 실패 코드가 된다`, () => {
      expect(readingFailureCode(reason)).toBe(reason);
      expect(NAVER_OBSERVE_FAILURE_CODES).toContain(reason);
    });
  }

  it("이 runner가 모르는 단어는 READING_REFUSED로 남는다 — 임의의 문자열이 기록에 닿지 않는다", () => {
    expect(readingFailureCode("ROW_RATING")).toBe("READING_REFUSED");
    expect(readingFailureCode("무엇이든")).toBe("READING_REFUSED");
    expect(readingFailureCode("")).toBe("READING_REFUSED");
  });
});

describe("리뷰 관리로 가는 길은 하나다", () => {
  it("read 레시피 · sign-in 세션 · legacy 랜딩이 모두 같은 deep link를 쓴다", () => {
    expect(NAVER_REVIEW_LIST_URL).toBe("https://sell.smartstore.naver.com/#/review/search");
    expect(NAVER_REVIEW_READ_WORKFLOW.entryUrl).toBe(NAVER_REVIEW_LIST_URL);
    expect(buildSignInRuntimePlan("NAVER").entryUrl).toBe(NAVER_REVIEW_LIST_URL);
    expect(buildSignInRuntimePlan("NAVER", "CHECK").entryUrl).toBe(NAVER_REVIEW_LIST_URL);
    expect(NAVER_REVIEW_MANAGEMENT_LANDING_URL).toBe(NAVER_REVIEW_LIST_URL);
  });

  it("그 길은 센터 메인이 아니라 리뷰 관리 화면이다 — OPERATOR와 SCHEDULED는 같은 레시피이므로 같은 길이다", () => {
    expect(NAVER_REVIEW_LIST_URL).toContain("#/review/search");
    expect(NAVER_REVIEW_LIST_URL.endsWith("/")).toBe(false);
  });
});
