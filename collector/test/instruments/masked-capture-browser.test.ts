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
  captureAfterPanels,
  createMaskedCapturer,
  redactionHolds,
  screenCaptureDir,
} from "../../instruments/live-runs/masked-capture";
import {
  REDACTION_APPLY_SCRIPT,
  REDACTION_CLEAR_SCRIPT,
  REDACTION_VERIFY_SCRIPT,
} from "../../src/action-window/api-issuance-calibration/visual-recon-inpage";
import { isIdentityTextToRedact } from "../../src/action-window/api-issuance-calibration/visual-recon";

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

  it("**a hash-shaped secret is a token to redact**, public prose and dates are not (2026-10-18 leak)", () => {
    for (const v of ["$2a$04$abcdEFGHijklMNOP/qrstUV", "abcdEFGHijkl/MNOPqrst+uv==", "ab.cd.efgh_ijkl-mnop$q"]) {
      expect(isIdentityTextToRedact(v), v).toBe(true);
    }
    for (const v of ["나누리샵의 api를 통한 데이터 수집용", "2026-08-04 발급 | nanu******", "애플리케이션 시크릿"]) {
      expect(isIdentityTextToRedact(v), v).toBe(false);
    }
  });

  it("a redaction holds only when every detected element is covered and the overlays are intact", () => {
    expect(redactionHolds({ integrityOk: true, detected: { form_field: 2 }, covered: { form_field: 2 } })).toBe(true);
    expect(redactionHolds({ integrityOk: true, detected: { form_field: 2 }, covered: { form_field: 1 } })).toBe(false);
    expect(redactionHolds({ integrityOk: false, detected: {}, covered: {} })).toBe(false);
    expect(redactionHolds(null)).toBe(false);
  });
});

describe("masked capture — **nothing from step 6 on** (run 5: a revealed secret reached a capture)", () => {
  it("captures steps 4–5, then stops for good at the application ID — park and completion notices included", async () => {
    const labels: string[] = [];
    const capturer = { capture: async (l: string) => (labels.push(l), true), stop: () => undefined };
    const driver = {
      highlightTarget: async (_t: string) => ({ count: 1 }),
      showParkNotice: async (_c: string) => true,
      showCompletionNotice: async () => true,
    };
    captureAfterPanels(driver, capturer, 0);
    const settle = () => new Promise((r) => setTimeout(r, 5));
    await driver.highlightTarget("open_edit");
    await driver.highlightTarget("call_ip");
    await settle();
    expect(labels).toEqual(["highlightTarget-open_edit", "highlightTarget-call_ip"]);
    await driver.highlightTarget("application_id");
    await driver.showParkNotice("UI_DRIFT");
    await driver.highlightTarget("call_ip");
    await driver.showCompletionNotice();
    await settle();
    expect(labels).toEqual(["highlightTarget-open_edit", "highlightTarget-call_ip"]);
  });

  it("the completion notice alone also stops captures", async () => {
    const labels: string[] = [];
    const capturer = { capture: async (l: string) => (labels.push(l), true), stop: () => undefined };
    const driver = { showCompletionNotice: async () => true, showParkNotice: async (_c: string) => true };
    captureAfterPanels(driver, capturer, 0);
    await driver.showCompletionNotice();
    await driver.showParkNotice("UI_DRIFT");
    await new Promise((r) => setTimeout(r, 5));
    expect(labels).toEqual([]);
  });
});

describe("masked capture — **only after a panel that painted, once** (run 6: the overlays flashed over NAVER's login form)", () => {
  const settle = () => new Promise((r) => setTimeout(r, 5));
  function rig(now: () => number = () => 0) {
    const labels: string[] = [];
    const capturer = { capture: async (l: string) => (labels.push(l), true), stop: () => undefined };
    let paints = true;
    let count = 1;
    const driver = {
      highlightTarget: async (_t: string) => ({ count }),
      showParkNotice: async (_c: string) => paints,
    };
    captureAfterPanels(driver, capturer, 0, 5_000, now);
    return { labels, driver, refuse: () => (paints = false), unlocate: () => (count = 0) };
  }

  it("**a refused notice — the login notice on NAVER's login form, every 500ms poll — is never followed by a capture**", async () => {
    const r = rig();
    r.refuse();
    for (let i = 0; i < 20; i++) await r.driver.showParkNotice("LOGIN_REQUIRED");
    await settle();
    expect(r.labels).toEqual([]);
  });

  it("an unlocated target draws no ring, so nothing is captured", async () => {
    const r = rig();
    r.unlocate();
    await r.driver.highlightTarget("open_edit");
    await settle();
    expect(r.labels).toEqual([]);
  });

  it("**a notice redrawn again and again is captured once per few seconds**", async () => {
    let t = 0;
    const r = rig(() => t);
    for (let i = 0; i <= 10; i++) { // t = 0 … 5000ms, every 500ms
      await r.driver.showParkNotice("UI_DRIFT");
      t += 500;
    }
    await settle();
    expect(r.labels).toEqual(["showParkNotice-UI_DRIFT", "showParkNotice-UI_DRIFT"]);
  });
});

describe("masked capture — a mask that does not hold writes nothing", () => {
  /** A page double whose redaction verify reports the given result; records whether it was screenshotted. */
  function fakePage(verify: unknown[]) {
    const calls: string[] = [];
    let v = 0;
    const page = {
      evaluate: async (src: string | (() => unknown)) => {
        if (typeof src === "function") return false; // the login-form check: no password field here
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

  it("**a login form is never touched: no overlay is ever drawn over it, no file**", async () => {
    await page.setContent(`<!doctype html><body><form><input id="id"><input id="pw" type="password"></form></body>`);
    await page.evaluate(() => {
      (window as unknown as { __drawn: number }).__drawn = 0;
      new MutationObserver((ms) => {
        for (const m of ms)
          for (const n of Array.from(m.addedNodes))
            if (n instanceof Element && n.hasAttribute("data-sellerops-redact"))
              (window as unknown as { __drawn: number }).__drawn += 1;
      }).observe(document.documentElement, { childList: true, subtree: true });
    });
    const writes: string[] = [];
    const cap = createMaskedCapturer(() => page, dir, (p) => writes.push(p));
    expect(await cap.capture("login")).toBe(false);
    expect(writes).toHaveLength(0);
    expect(await page.evaluate(() => (window as unknown as { __drawn: number }).__drawn)).toBe(0);
  });

});

describe.skipIf(!RUN)("**a REVEALED secret is covered** — the 2026-10-18 leak, reproduced", () => {
  let browser: Browser;
  let page: Page;
  beforeAll(async () => {
    browser = await chromium.launch({ headless: true });
    page = await browser.newPage({ viewport: { width: 1280, height: 720 } });
  });
  afterAll(async () => {
    await browser?.close();
  });

  // The shape the leak had: a key/value row with NO attribute naming it, the value in plain text after 「보기」,
  // hash-shaped (separators split it into short runs), next to 「숨기기」「복사」「재발급」 buttons that carry only
  // their visible labels. A synthetic value — never a real one.
  const ROW = (value: string) => `<!doctype html><meta charset="utf-8"><body style="margin:0;padding:40px">
    <table><tbody>
      <tr><th>애플리케이션 ID</th><td><span id="idv">${value.replace(/\$/g, "x")}</span></td></tr>
      <tr><th>애플리케이션 시크릿</th><td><span id="val">${value}</span>
        <button>숨기기</button><button>복사</button><button>재발급</button></td></tr>
      <tr><th>설명</th><td id="desc">나누리샵의 api를 통한 데이터 수집용</td></tr>
    </tbody></table></body>`;
  const covered = (id: string) =>
    page.evaluate((elId) => {
      const r = document.getElementById(elId)!.getBoundingClientRect();
      const hit = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2);
      return !!hit && hit.hasAttribute("data-sellerops-redact");
    }, id);

  it("**covers the revealed secret and the ID value; leaves the public description visible**", async () => {
    await page.setContent(ROW("$2a$04$abcdEFGHijklMNOP/qrstUV"));
    await page.evaluate(REDACTION_APPLY_SCRIPT);
    expect(redactionHolds(await page.evaluate(REDACTION_VERIFY_SCRIPT))).toBe(true);
    expect(await covered("val")).toBe(true);
    expect(await covered("idv")).toBe(true);
    expect(await covered("desc")).toBe(false);
    await page.evaluate(REDACTION_CLEAR_SCRIPT);
  });

  it("covers it by the row's label alone — no copy control, a value no token pattern would ever match", async () => {
    await page.setContent(`<!doctype html><meta charset="utf-8"><body style="margin:0;padding:40px"><table><tbody>
      <tr><th>애플리케이션 시크릿</th><td><span id="val">가나다라마바사</span></td></tr></tbody></table></body>`);
    await page.evaluate(REDACTION_APPLY_SCRIPT);
    expect(await covered("val")).toBe(true);
    await page.evaluate(REDACTION_CLEAR_SCRIPT);
  });
});
