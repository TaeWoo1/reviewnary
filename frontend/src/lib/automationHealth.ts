import { COPY } from "./copy/customerOps";
import type { CustomerOperationsHome } from "./customerOperationsTypes";

/**
 * <b>Is the automatic check actually healthy?</b> (product-owner decision, 2026-10-01.)
 *
 * <p>The Home's status dot was `co.status === "ACTIVE" ? green : …` and nothing else — a read of a
 * CONFIGURATION field presented as a health signal. Measured on the live org it drew a green dot and
 * 「자동 확인 중」 beside 「마지막 확인 9월 27일 03:31」, four days stale, on the same line. A seller
 * cannot be expected to notice that the colour and the timestamp disagree; the colour is what they
 * read.
 *
 * <p>Three separate facts were collapsed into one, and they stay separate here:
 *
 * <ul>
 *   <li><b>enabled</b> — `status`. Whether the job exists and is switched on.</li>
 *   <li><b>the last run's outcome</b> — `lastRunStatus`. A refusal is a failure, and failure is what
 *       red is for ({@link ./workState} reserves `bad` for 실패·차단·실제 위험, which this is).</li>
 *   <li><b>when it last succeeded</b> — `lastCheckedAt`, judged against `cadenceMinutes`, which the
 *       server already sends as the job's own declared period.</li>
 * </ul>
 *
 * <p><b>The overdue boundary is cadence × 2, not a number chosen here</b> (product-owner decision).
 * One missed cycle can be a run still in flight or a clock skew; two is a job that is not keeping its
 * own schedule. Because the multiplier rides on the server's cadence, a job that checks every ten
 * minutes and a job that checks every six hours are both judged against what they promised.
 *
 * <p><b>ENABLED alone may never be green.</b> That is the whole rule, and it is here — in a pure
 * function over the contract's own fields — rather than in a className, so a test can state it and a
 * future tint cannot quietly restore it.
 */
export type HealthTone = "good" | "warn" | "bad" | "neutral";

export interface AutomationHealth {
  tone: HealthTone;
  /** The word on the status line. */
  label: string;
  /**
   * Why it is not green, in the seller's words — null when it is. Never a number the seller has to
   * compare themselves: the line beside this one already prints 마지막 확인.
   */
  note: string | null;
}

/** 두 주기. One missed cycle is noise; two is a schedule not being kept. */
export const OVERDUE_CADENCES = 2;

export const HEALTH_WORD = {
  failed: "자동 확인 실패",
  overdue: "자동 확인 지연",
  neverChecked: "아직 확인한 기록 없음",
} as const;

export function automationHealth(
  co: Pick<CustomerOperationsHome, "status" | "lastRunStatus" | "lastCheckedAt" | "cadenceMinutes">,
  now: Date = new Date(),
): AutomationHealth {
  // A failed run outranks everything else the fields say. The job may well be ACTIVE and on
  // schedule — what the seller needs to know is that the last thing it did was fail.
  if (co.lastRunStatus === "FAILED") {
    return { tone: "bad", label: HEALTH_WORD.failed, note: null };
  }
  if (co.status === null) return { tone: "neutral", label: COPY.off, note: null };
  if (co.status === "PAUSED") return { tone: "warn", label: COPY.paused, note: null };
  if (co.status !== "ACTIVE") return { tone: "neutral", label: COPY.off, note: null };

  // Switched on and has never run: that is not a healthy job, and it is not a failed one either.
  const at = co.lastCheckedAt ? Date.parse(co.lastCheckedAt) : NaN;
  if (Number.isNaN(at)) {
    return { tone: "warn", label: COPY.running, note: HEALTH_WORD.neverChecked };
  }
  // A cadence the server did not send cannot judge anything — an unknown schedule is not a late one.
  const window = co.cadenceMinutes > 0 ? co.cadenceMinutes * OVERDUE_CADENCES * 60_000 : null;
  if (window !== null && now.getTime() - at > window) {
    return { tone: "warn", label: HEALTH_WORD.overdue, note: null };
  }
  return { tone: "good", label: COPY.running, note: null };
}
