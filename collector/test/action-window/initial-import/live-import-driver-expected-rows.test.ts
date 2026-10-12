/**
 * The live driver's list-total read, against a scripted context.
 *
 * The range was applied a moment before this read and the list refreshes asynchronously, so one sample can
 * still describe the PREVIOUS period — and the backend FAILS a segment whose file disagrees with the number.
 * These pin that a number comes back only when consecutive samples agree with no loading overlay, and that the
 * log carries a reason word and a bucket, never the number.
 */
import { beforeEach, describe, expect, it } from "vitest";
import { NaverLiveImportDriver } from "../../../src/action-window/initial-import/naver-live-import-driver";
import type { NaverLiveProbeDriver } from "../../../src/action-window/naver-live-driver";
import { buildNaverLabelledTotalScript } from "../../../src/naver/review-list-observe-inpage";
import { clearLogSink, getLogSink } from "../../../src/log";

type Sample = { total: number | null; loading: boolean } | "throw";

function driverOver(samples: Sample[], opts: { stable?: number; max?: number } = {}) {
  const evaluated: unknown[] = [];
  let i = 0;
  const ctx = {
    evaluate: async (script: unknown) => {
      evaluated.push(script);
      const s = samples[Math.min(i, samples.length - 1)]!;
      i += 1;
      if (s === "throw") throw new Error("Execution context was destroyed");
      return s;
    },
  };
  const proven = { surfaceContext: () => ctx } as unknown as NaverLiveProbeDriver;
  const sleeps: number[] = [];
  const driver = new NaverLiveImportDriver(proven, {
    expectedRowsIntervalMs: 500,
    expectedRowsStableReads: opts.stable ?? 3,
    expectedRowsMaxSamples: opts.max ?? 12,
    sleepFn: async (ms) => {
      sleeps.push(ms);
    },
  });
  return { driver, evaluated, sleeps, reads: () => i };
}

const ok = (total: number): Sample => ({ total, loading: false });
const lastLog = () => getLogSink().filter((e) => e.event === "aw_import_expected_rows").at(-1)!;

describe("NaverLiveImportDriver.readExpectedRows", () => {
  beforeEach(() => clearLogSink());

  it("answers the total once three consecutive samples agree, evaluating the shared in-page script", async () => {
    const { driver, evaluated, sleeps } = driverOver([ok(1234)]);
    expect(await driver.readExpectedRows()).toBe(1234);
    expect(evaluated).toHaveLength(3);
    expect(evaluated.every((s) => s === buildNaverLabelledTotalScript())).toBe(true);
    expect(sleeps).toEqual([500, 500]);
    expect(lastLog().meta).toEqual({ read: "STABLE", samples: 3, expectedRowsBucket: "thousands_plus" });
  });

  it("waits out the loading overlay and a stale value, then answers the settled total", async () => {
    const { driver } = driverOver([ok(52), { total: null, loading: true }, ok(37), ok(37), ok(37)]);
    expect(await driver.readExpectedRows()).toBe(37);
  });

  it("never settles → null (a total still changing is not a total)", async () => {
    const { driver } = driverOver([ok(1), ok(2), ok(1), ok(2), ok(1), ok(2)], { max: 6 });
    expect(await driver.readExpectedRows()).toBeNull();
    expect(lastLog().meta).toMatchObject({ read: "UNSTABLE", expectedRowsBucket: "absent" });
  });

  it("label absent or ambiguous throughout → null", async () => {
    const { driver } = driverOver([{ total: null, loading: false }], { max: 4 });
    expect(await driver.readExpectedRows()).toBeNull();
    expect(lastLog().meta).toMatchObject({ read: "ABSENT", samples: 4, expectedRowsBucket: "absent" });
  });

  it("still loading at the end of the window → null", async () => {
    const { driver } = driverOver([{ total: null, loading: true }], { max: 4 });
    expect(await driver.readExpectedRows()).toBeNull();
    expect(lastLog().meta).toMatchObject({ read: "LOADING" });
  });

  it("a read that throws stops at once → null", async () => {
    const { driver, reads } = driverOver([ok(9), "throw", ok(9), ok(9)]);
    expect(await driver.readExpectedRows()).toBeNull();
    expect(reads()).toBe(2);
    expect(lastLog().meta).toMatchObject({ read: "READ_FAILED" });
  });

  it("a malformed answer is not a number", async () => {
    const { driver } = driverOver([{ total: -4 as number, loading: false }, { total: 1.5, loading: false }], { max: 3 });
    expect(await driver.readExpectedRows()).toBeNull();
  });

  it("never logs the number itself", async () => {
    const { driver } = driverOver([ok(4321)]);
    expect(await driver.readExpectedRows()).toBe(4321);
    expect(JSON.stringify(getLogSink())).not.toContain("4321");
  });
});
