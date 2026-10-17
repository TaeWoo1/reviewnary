/**
 * **Masked screenshots of a live guided walk, for a UX pass** — dev instrument, opt-in, never product behaviour.
 *
 * PO request (2026-10-18): run the real 8-step walk and look at it the way the Aside pass did — the panel and the
 * rings as the seller sees them — without ever capturing a value. After each panel the walk draws (a step's
 * highlight, step 3's advisory, a park notice, the completion notice), this:
 *
 *   1. applies the repo's redaction overlays (`REDACTION_APPLY_SCRIPT`: form fields, read-only/code values,
 *      credential areas, copy-linked values, account handles / IPs / long tokens in text),
 *   2. verifies EVERY detected sensitive element is covered by an opaque overlay that wins the hit test,
 *   3. screenshots the viewport only,
 *   4. verifies again, then removes the overlays,
 *
 * and writes the PNG only if both verifications passed. A failed verification writes nothing and logs a reason
 * enum. Nothing is clicked or typed; the overlays are visible to the seller for the moment of the capture.
 *
 * There is deliberately NO file trigger: a file is something a model can write, and the live CLIs keep no
 * file-driven action on a live page (`test/cli/operator-advance-channel-guard.test.ts`). Captures follow the
 * panels the walk itself draws, nothing else.
 *
 * The folder must be absolute and outside the repository, so a capture can never be staged by accident.
 */
import { mkdirSync, writeFileSync } from "node:fs";
import { isAbsolute, join, relative, resolve } from "node:path";
import type { Page } from "playwright";
import { log } from "../../src/log";
import {
  REDACTION_APPLY_SCRIPT,
  REDACTION_CLEAR_SCRIPT,
  REDACTION_VERIFY_SCRIPT,
} from "../../src/action-window/api-issuance-calibration/visual-recon-inpage";

interface RedactionReport {
  integrityOk?: boolean;
  detected?: Record<string, number>;
  covered?: Record<string, number>;
}

function sum(rec: Record<string, number> | undefined): number {
  return Object.values(rec ?? {}).reduce((a, b) => a + (typeof b === "number" ? b : 0), 0);
}

/** A redaction report is good only when the overlays are intact and cover every detected element. */
export function redactionHolds(r: unknown): boolean {
  const rep = (r ?? {}) as RedactionReport;
  return rep.integrityOk === true && sum(rep.detected) === sum(rep.covered);
}

/** Refuse a capture folder that is relative or inside the repository. Returns the absolute folder or null. */
export function screenCaptureDir(dir: string | undefined, repoRoot: string): string | null {
  if (!dir || !isAbsolute(dir)) return null;
  const rel = relative(resolve(repoRoot), resolve(dir));
  if (rel === "" || (!rel.startsWith("..") && !isAbsolute(rel))) return null;
  return resolve(dir);
}

export interface MaskedCapturer {
  capture(label: string): Promise<boolean>;
  stop(): void;
}

/**
 * One masked capture of `page()` at a time, numbered in order. `page` is read at capture time so a newer tab is
 * followed. `writeFile` is injectable for tests.
 */
export function createMaskedCapturer(
  page: () => Page | undefined,
  dir: string,
  writeFile: (path: string, data: Buffer) => void = (p, d) => writeFileSync(p, d),
): MaskedCapturer {
  let seq = 0;
  let busy: Promise<boolean> = Promise.resolve(true);
  const once = async (label: string): Promise<boolean> => {
    const pg = page();
    if (!pg) return false;
    const safeLabel = label.replace(/[^a-z0-9_-]/gi, "").slice(0, 40) || "shot";
    try {
      await pg.evaluate(REDACTION_APPLY_SCRIPT);
      const before = await pg.evaluate(REDACTION_VERIFY_SCRIPT);
      if (!redactionHolds(before)) {
        log("aw_masked_capture", { label: safeLabel, ok: false, reason: "UNVERIFIED_BEFORE" });
        return false;
      }
      const buf = await pg.screenshot({ animations: "disabled" });
      const after = await pg.evaluate(REDACTION_VERIFY_SCRIPT);
      if (!redactionHolds(after)) {
        log("aw_masked_capture", { label: safeLabel, ok: false, reason: "UNVERIFIED_AFTER" });
        return false;
      }
      seq += 1;
      writeFile(join(dir, `${String(seq).padStart(3, "0")}-${safeLabel}.png`), buf);
      log("aw_masked_capture", { label: safeLabel, ok: true, seq });
      return true;
    } catch {
      log("aw_masked_capture", { label: safeLabel, ok: false, reason: "PAGE_UNAVAILABLE" });
      return false;
    } finally {
      await pg.evaluate(REDACTION_CLEAR_SCRIPT).catch(() => undefined);
    }
  };
  const capturer: MaskedCapturer = {
    capture(label: string) {
      busy = busy.then(() => once(label), () => once(label));
      return busy;
    },
    stop() {
      /* nothing scheduled of its own; panel-triggered captures already in flight finish on their own */
    },
  };
  return capturer;
}

/** The panels a walk draws — each one is followed by a capture once it has had a moment to settle. */
const CAPTURE_AFTER = ["highlightTarget", "showAppUsageNotice", "showParkNotice", "showCompletionNotice"] as const;

/**
 * Wrap a driver so each panel it draws is followed by one masked capture, `delayMs` later (the panel's own
 * placement and the copy rows settle first). The wrapped method's result and timing are unchanged.
 */
export function captureAfterPanels<T extends object>(driver: T, capturer: MaskedCapturer, delayMs = 800): T {
  const d = driver as unknown as Record<string, unknown>;
  for (const name of CAPTURE_AFTER) {
    const fn = d[name];
    if (typeof fn !== "function") continue;
    d[name] = async (...args: unknown[]) => {
      const out = await (fn as (...a: unknown[]) => Promise<unknown>).apply(driver, args);
      const label = `${name}${typeof args[0] === "string" ? `-${args[0]}` : ""}`;
      setTimeout(() => void capturer.capture(label), delayMs);
      return out;
    };
  }
  return driver;
}

/** Make sure the folder exists (it is outside the repo by construction). */
export function ensureCaptureDir(dir: string): void {
  mkdirSync(dir, { recursive: true });
}
