/**
 * REAL-CHROMIUM test for the recon fixed-label probe's paint count. Gated behind RUN_INTEGRATION=1 (repo convention):
 *
 *   RUN_INTEGRATION=1 npx vitest run test/action-window/api-issuance-calibration/fixed-label-probe-browser.test.ts
 *
 * Hermetic (`setContent`, no network). It reproduces the edit-form shape that stopped the 2026-10-17 Phase B run at
 * ⑥: a node whose whole text is exactly 「API호출 IP」 that never paints, beside the label the seller actually sees,
 * 「API호출 IP (1/3)」, and the paste form's 「추가」. The probe must report the hidden match as matched-but-unseen,
 * and the highlight's own locate script must agree with that visible count.
 */
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { chromium, type Browser, type Page } from "playwright";
import { buildFixedLabelLocateScript, buildFixedLabelProbeScript } from "../../../src/action-window/api-issuance-calibration/visual-recon-inpage";
import { labelProbesForScreen } from "../../../src/action-window/api-issuance-calibration/visual-recon-candidates";

const RUN = process.env.RUN_INTEGRATION === "1";

const EDIT_FORM = `<!doctype html><meta charset="utf-8"><body>
  <section id="detail" style="display:none"><table><tr><th>API호출 IP</th><td>—</td></tr></table></section>
  <section id="edit">
    <button>취소</button><button>저장</button>
    <div style="margin-top:1400px"><span>API호출 IP <em>(1/3)</em></span></div>
    <form><input type="text"><button type="button">추가</button></form>
    <form><span>—</span><button type="button">삭제</button></form>
  </section>
</body>`;

describe.skipIf(!RUN)("recon fixed-label probe — real Chromium paint count", () => {
  let browser: Browser;
  let page: Page;
  beforeAll(async () => {
    browser = await chromium.launch({ headless: true });
    page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
    await page.setContent(EDIT_FORM);
  });
  afterAll(async () => {
    await browser?.close();
  });

  it("counts the never-painted exact label as matched but not visible, and the below-the-fold 「추가」 as visible", async () => {
    const out = (await page.evaluate(buildFixedLabelProbeScript(labelProbesForScreen("app_edit")))) as unknown;
    expect(out).toEqual([
      { targetId: "app_edit.call_ip_label", matchCount: 1, visibleCount: 0 },
      { targetId: "app_edit.call_ip_add_button", matchCount: 1, visibleCount: 1 },
    ]);
  });

  it("agrees with the highlight's locate script, which sees only painted matches", async () => {
    for (const p of labelProbesForScreen("app_edit")) {
      const loc = (await page.evaluate(buildFixedLabelLocateScript({ candidateQuery: p.candidateQuery, exactText: p.exactText, tag: false }))) as {
        count: number;
      };
      const probed = ((await page.evaluate(buildFixedLabelProbeScript([p]))) as { visibleCount: number }[])[0]!;
      expect(loc.count, p.targetId).toBe(probed.visibleCount);
    }
  });
});
