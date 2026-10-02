import { describe, expect, it } from "vitest";
import { HEALTH_WORD, OVERDUE_CADENCES, automationHealth } from "./automationHealth";
import { COPY } from "./copy/customerOps";

/**
 * <b>ENABLED alone may never be green</b> (product-owner decision, 2026-10-01).
 *
 * <p>These assert the RULE, not the colour values: the thing that broke was that one field decided
 * the dot, so every case here fixes which fact selects which tone. A later package may re-tint `warn`
 * and these still hold; a later package that reads `status` alone fails all of them.
 */

const now = new Date("2026-10-01T09:00:00+09:00");
const co = (over: Partial<Parameters<typeof automationHealth>[0]> = {}) => ({
  status: "ACTIVE" as const,
  lastRunStatus: "SUCCEEDED",
  lastCheckedAt: "2026-10-01T08:50:00+09:00",
  cadenceMinutes: 30,
  ...over,
});

describe("automation health — what makes it green", () => {
  it("is green only when it is on, its last run did not fail, and it checked inside its own cadence", () => {
    expect(automationHealth(co(), now)).toEqual({ tone: "good", label: COPY.running, note: null });
  });

  it("is NOT green on ENABLED alone — the bug this function exists to end", () => {
    // The live org: ACTIVE, nothing failed, and the last check was four days ago. The old rule read
    // `status === "ACTIVE"` and drew green beside 「마지막 확인 9월 27일」.
    const stale = automationHealth(co({ lastCheckedAt: "2026-09-27T03:31:00+09:00" }), now);
    expect(stale.tone).not.toBe("good");
    expect(stale.tone).toBe("warn");
    expect(stale.label).toBe(HEALTH_WORD.overdue);
  });

  it("holds green through ONE missed cycle and gives it up on two", () => {
    const cadence = 30;
    const minutesAgo = (m: number) => new Date(now.getTime() - m * 60_000).toISOString();
    // Inside two cadences: a run in flight or a skewed clock, not a broken schedule.
    expect(automationHealth(co({ cadenceMinutes: cadence, lastCheckedAt: minutesAgo(cadence * OVERDUE_CADENCES - 1) }), now).tone)
      .toBe("good");
    expect(automationHealth(co({ cadenceMinutes: cadence, lastCheckedAt: minutesAgo(cadence * OVERDUE_CADENCES + 1) }), now).tone)
      .toBe("warn");
  });

  it("judges each job against the cadence IT declared, not against one number", () => {
    const sixHours = 360;
    const fiveHoursAgo = new Date(now.getTime() - 5 * 60 * 60_000).toISOString();
    // Five hours is wildly overdue for a 30-minute job and well inside a six-hour one.
    expect(automationHealth(co({ cadenceMinutes: 30, lastCheckedAt: fiveHoursAgo }), now).tone).toBe("warn");
    expect(automationHealth(co({ cadenceMinutes: sixHours, lastCheckedAt: fiveHoursAgo }), now).tone).toBe("good");
  });

  it("does not call an unknown schedule a late one", () => {
    // No cadence means nothing was promised, so nothing was missed. Absence is not a finding.
    expect(automationHealth(co({ cadenceMinutes: 0, lastCheckedAt: "2026-01-01T00:00:00+09:00" }), now).tone).toBe("good");
  });
});

describe("automation health — the three facts stay separate", () => {
  it("red is a failed run, and it outranks a schedule that is being kept", () => {
    const failed = automationHealth(co({ lastRunStatus: "FAILED" }), now);
    expect(failed.tone).toBe("bad");
    expect(failed.label).toBe(HEALTH_WORD.failed);
  });

  it("amber is on-but-never-checked, which is neither failure nor health", () => {
    const fresh = automationHealth(co({ lastCheckedAt: null }), now);
    expect(fresh.tone).toBe("warn");
    expect(fresh.label).toBe(COPY.running);
    expect(fresh.note).toBe(HEALTH_WORD.neverChecked);
  });

  it("amber is paused — the seller switched it off themselves and nothing failed", () => {
    expect(automationHealth(co({ status: "PAUSED" }), now)).toEqual({ tone: "warn", label: COPY.paused, note: null });
  });

  it("says nothing in colour when there is no job at all", () => {
    const none = automationHealth(co({ status: null, lastRunStatus: null, lastCheckedAt: null }), now);
    expect(none.tone).toBe("neutral");
    expect(none.label).toBe(COPY.off);
  });
});
