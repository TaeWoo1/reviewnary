import { describe, expect, it } from "vitest";
import { WORK_STATE } from "./workState";

/**
 * <b>Every work word fits the Home's lead column</b> (`DecisionRow`, `w-[104px]`). 「초안 준비됨」 — five
 * characters — is the longest that ever fitted; 「담당자 등록 못 함」 ran into the title beside it (2026-10-24).
 */
describe("WORK_STATE", () => {
  it("has no word longer than five characters, spaces aside", () => {
    const long = Object.values(WORK_STATE).map((w) => w.text).filter((t) => t.replace(/\s/g, "").length > 5);
    expect(Object.keys(WORK_STATE).length).toBeGreaterThan(0);
    expect(long).toEqual([]);
  });
});
