/**
 * REAL-CHROMIUM test: **a ring yields to whatever the page puts on top of its control.** Gated behind
 * RUN_INTEGRATION=1 (repo convention):
 *
 *   RUN_INTEGRATION=1 npx vitest run test/action-window/overlay-occlusion-browser.test.ts
 *
 * Live 2026-10-17 (4차 Phase B): pressing NAVER's 시크릿 「보기」 opened an anti-automation challenge over the ringed
 * row, and the ring with its page-wide shroud — at the top z-index — sat over the challenge the seller had to read
 * and solve. The decision rests on the browser's hit test, which the DOM double does not have, so this runs it in
 * the real thing: a page dialog over the control hides the ring, its removal brings the ring back, and neither our
 * own panel nor a part of the control counts as a cover.
 */
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { chromium, type Browser, type Page } from "playwright";
import { mountOverlay, unmountOverlay } from "../../src/action-window/overlay";

const RUN = process.env.RUN_INTEGRATION === "1";

const PAGE = `<!doctype html><meta charset="utf-8"><body style="margin:0">
  <table style="margin:300px 0 0 200px"><tbody>
    <tr id="row" data-aw-target><th>애플리케이션 시크릿</th><td><button type="button">보기</button></td></tr>
  </tbody></table>
</body>`;

const OPTS = {
  stepNumber: 8,
  totalSteps: 9,
  copyKey: "actionWindow.issuance.step.application_secret",
  guidanceEnabled: true,
};

/** The ring's display after the tracker has had a frame (and its 500ms backstop) to react. */
async function ringDisplay(page: Page): Promise<string> {
  await page.waitForTimeout(650);
  return page.evaluate(() => getComputedStyle(document.getElementById("__aw_overlay__")!).display);
}

describe.skipIf(!RUN)("a ring yields to a page dialog over its control — real Chromium", () => {
  let browser: Browser;
  let page: Page;
  beforeAll(async () => {
    browser = await chromium.launch({ headless: true });
    page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
  });
  afterAll(async () => {
    await browser?.close();
  });
  beforeEach(async () => {
    await page.setContent(PAGE);
  });

  it("an uncovered control keeps its ring — a hit on a part of the control is the control", async () => {
    await mountOverlay(page, OPTS);
    expect(await ringDisplay(page)).toBe("block");
  });

  it("**a dialog the page opens over the control hides the ring and its shroud, and its removal restores them**", async () => {
    await mountOverlay(page, OPTS);
    expect(await ringDisplay(page)).toBe("block");
    await page.evaluate(() => {
      const d = document.createElement("div");
      d.id = "challenge";
      d.style.cssText = "position:fixed;inset:0;z-index:1000;background:rgba(0,0,0,0.4)";
      d.innerHTML = '<div style="margin:200px auto;width:400px;height:300px;background:#fff">자동입력 방지</div>';
      document.body.appendChild(d);
    });
    expect(await ringDisplay(page)).toBe("none");
    await page.evaluate(() => document.getElementById("challenge")!.remove());
    expect(await ringDisplay(page)).toBe("block");
  });

  it("our own panel over the control is not a cover", async () => {
    // A short window filled by the control: every placement the panel has sits on it, so it lands bottom-centre —
    // over the control's centre. (Pinning the panel by hand does not hold: the tracker re-places it.)
    await page.setViewportSize({ width: 1280, height: 120 });
    await page.setContent(`<!doctype html><meta charset="utf-8"><body style="margin:0">
      <div id="row" data-aw-target style="height:120px;width:100%;background:#eee">애플리케이션 시크릿</div></body>`);
    try {
      await mountOverlay(page, { ...OPTS, residentPanel: true, label: "시크릿을 확인해 주세요", advance: { buttonLabel: "다음", token: "tok" } });
      const onPanel = await page.evaluate(() => {
        const r = document.getElementById("row")!.getBoundingClientRect();
        const hit = document.elementFromPoint((r.left + r.right) / 2, (r.top + r.bottom) / 2);
        return document.getElementById("__aw_advance_panel__")!.contains(hit);
      });
      expect(onPanel).toBe(true); // the case is real: the hit test answers with our panel
      expect(await ringDisplay(page)).toBe("block");
    } finally {
      await page.setViewportSize({ width: 1280, height: 800 });
    }
  });

  it("a guidance-disabled mount stays hidden whatever the hit test says", async () => {
    await mountOverlay(page, { ...OPTS, guidanceEnabled: false });
    expect(await ringDisplay(page)).toBe("none");
    await unmountOverlay(page);
  });
});
