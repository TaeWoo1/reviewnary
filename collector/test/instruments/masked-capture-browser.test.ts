/**
 * The masked-capture dev instrument (2026-10-18): it writes a screenshot ONLY behind a verified redaction, and
 * only to a folder outside the repository. Pure checks run offline; the capture itself runs in real Chromium,
 * gated behind RUN_INTEGRATION=1 (repo convention).
 */
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { chromium, type Browser, type Page } from "playwright";
import {
  createMaskedCapturer,
  redactionHolds,
  screenCaptureDir,
} from "../../instruments/live-runs/masked-capture";

const RUN = process.env.RUN_INTEGRATION === "1";
const REPO = resolve(__dirname, "../../..");

describe("masked capture — the guards", () => {
  it("refuses a relative folder or one inside the repository", () => {
    expect(screenCaptureDir(undefined, REPO)).toBeNull();
    expect(screenCaptureDir("shots", REPO)).toBeNull();
    expect(screenCaptureDir(REPO, REPO)).toBeNull();
    expect(screenCaptureDir(join(REPO, "collector", "shots"), REPO)).toBeNull();
    expect(screenCaptureDir(join(tmpdir(), "aw-shots"), REPO)).toBe(join(tmpdir(), "aw-shots"));
  });

  it("a redaction holds only when every detected element is covered and the overlays are intact", () => {
    expect(redactionHolds({ integrityOk: true, detected: { form_field: 2 }, covered: { form_field: 2 } })).toBe(true);
    expect(redactionHolds({ integrityOk: true, detected: { form_field: 2 }, covered: { form_field: 1 } })).toBe(false);
    expect(redactionHolds({ integrityOk: false, detected: {}, covered: {} })).toBe(false);
    expect(redactionHolds(null)).toBe(false);
  });
});

describe("masked capture — a mask that does not hold writes nothing", () => {
  /** A page double whose redaction verify reports the given result; records whether it was screenshotted. */
  function fakePage(verify: unknown[]) {
    const calls: string[] = [];
    let v = 0;
    const page = {
      evaluate: async (src: string) => {
        if (src.includes("visual-recon-redaction (verify)")) return verify[Math.min(v++, verify.length - 1)];
        calls.push(src.includes("(apply)") ? "apply" : "clear");
        return { integrityOk: true, detected: {}, covered: {} };
      },
      screenshot: async () => {
        calls.push("screenshot");
        return Buffer.from("png");
      },
    };
    return { page: page as unknown as Page, calls };
  }
  const HOLD = { integrityOk: true, detected: { form_field: 1 }, covered: { form_field: 1 } };
  const GAP = { integrityOk: true, detected: { form_field: 2 }, covered: { form_field: 1 } };

  it("**an uncovered element before the shot: no screenshot, no file, overlays removed**", async () => {
    const { page, calls } = fakePage([GAP]);
    const writes: string[] = [];
    const cap = createMaskedCapturer(() => page, tmpdir(), (p) => writes.push(p));
    expect(await cap.capture("x")).toBe(false);
    cap.stop();
    expect(calls).not.toContain("screenshot");
    expect(writes).toHaveLength(0);
    expect(calls[calls.length - 1]).toBe("clear");
  });

  it("**the mask broke during the shot: the screenshot is discarded**", async () => {
    const { page, calls } = fakePage([HOLD, GAP]);
    const writes: string[] = [];
    const cap = createMaskedCapturer(() => page, tmpdir(), (p) => writes.push(p));
    expect(await cap.capture("x")).toBe(false);
    cap.stop();
    expect(calls).toContain("screenshot");
    expect(writes).toHaveLength(0);
  });
});

describe.skipIf(!RUN)("masked capture — real Chromium", () => {
  let browser: Browser;
  let page: Page;
  let dir: string;
  beforeAll(async () => {
    browser = await chromium.launch({ headless: true });
    page = await browser.newPage({ viewport: { width: 900, height: 600 } });
    dir = mkdtempSync(join(tmpdir(), "aw-masked-"));
  });
  afterAll(async () => {
    await browser?.close();
    rmSync(dir, { recursive: true, force: true });
  });

  it("**writes a capture when the mask holds, and leaves no overlay behind**", async () => {
    await page.setContent(`<!doctype html><body><table><tr><th>애플리케이션 ID</th><td><input value="v"></td></tr></table></body>`);
    const writes: string[] = [];
    const cap = createMaskedCapturer(() => page, dir, (p) => writes.push(p));
    expect(await cap.capture("step")).toBe(true);
    cap.stop();
    expect(writes).toHaveLength(1);
    expect(writes[0]).toMatch(/001-step\.png$/);
    expect(await page.evaluate(() => document.querySelectorAll("[data-sellerops-redact]").length)).toBe(0);
  });

});
