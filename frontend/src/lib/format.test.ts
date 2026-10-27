import { describe, expect, it } from "vitest";
import { listDay } from "./format";

describe("listDay — one way to write a day in a list (2026-10-28)", () => {
  const now = new Date("2026-10-28T03:00:00Z"); // 12:00 KST
  it("says 오늘 and N일 전 for a week, the date after that", () => {
    expect(listDay("2026-10-28", now)).toBe("오늘");
    expect(listDay("2026-10-21", now)).toBe("7일 전");
    expect(listDay("2026-10-20", now)).toBe("2026-10-20");
    expect(listDay(null, now)).toBe("날짜 없음");
  });
});
