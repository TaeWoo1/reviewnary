/**
 * REAL-CHROMIUM test for the edit form's call-IP target. Gated behind RUN_INTEGRATION=1 (repo convention):
 *
 *   RUN_INTEGRATION=1 npx vitest run test/action-window/api-issuance-calibration/fixed-label-probe-browser.test.ts
 *
 * Hermetic (`setContent`, no network). The fixture is the structure measured on 2026-10-17 (value-free, in the
 * operator's own browser): the detail view's exact 「API호출 IP」 TH survives under a display:none ancestor; the edit
 * form's label is a LABEL inside the row's TH reading 「API호출 IP (2/3)」; its TR holds the text inputs, one 「추가」
 * and a 「삭제」 per registered address; a second visible 「추가」 sits outside any TR. That shape stopped the 3차
 * Phase B run at ⑥, so each matcher here — recon probe, highlight locate/tag, the driver's Playwright regex — must
 * agree on it.
 */
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { chromium, type Browser, type Page } from "playwright";
import { buildFixedLabelLocateScript, buildFixedLabelProbeScript } from "../../../src/action-window/api-issuance-calibration/visual-recon-inpage";
import { labelProbesForScreen } from "../../../src/action-window/api-issuance-calibration/visual-recon-candidates";
import { locatorFor } from "../../../src/action-window/api-issuance-calibration/issuance-highlight-selectors";
import { exactLabelRegex } from "../../../src/action-window/naver-issuance-driver";

const RUN = process.env.RUN_INTEGRATION === "1";

const EDIT_FORM = `<!doctype html><meta charset="utf-8"><body>
  <section id="detail" style="display:none"><table><tr><th>API호출 IP</th><td>—</td></tr></table></section>
  <section id="edit">
    <button>취소</button><button>저장</button>
    <table style="margin-top:1400px"><tbody>
      <tr id="other-row"><th><label>설명</label></th><td><textarea></textarea></td></tr>
      <tr id="ip-row">
        <th><label>API호출 IP (<em>2</em>/3)</label></th>
        <td>
          <form><input type="text"><button type="button">추가</button></form>
          <div><input type="text" readonly><button type="button">삭제</button></div>
          <div><input type="text" readonly><button type="button">삭제</button></div>
        </td>
      </tr>
    </tbody></table>
    <div><span>API호출 IP (2/3) 외</span><button type="button">추가</button></div>
  </section>
</body>`;

describe.skipIf(!RUN)("edit-form call-IP target — real Chromium", () => {
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

  it("the recon probe sees the exact label as hidden, 「추가」 as non-unique, and the counted label as one visible match", async () => {
    const out = (await page.evaluate(buildFixedLabelProbeScript(labelProbesForScreen("app_edit")))) as unknown;
    expect(out).toEqual([
      { targetId: "app_edit.call_ip_label", matchCount: 1, visibleCount: 0 },
      { targetId: "app_edit.call_ip_add_button", matchCount: 2, visibleCount: 2 },
      { targetId: "app_edit.call_ip_count_label", matchCount: 1, visibleCount: 1 },
    ]);
  });

  it("the highlight tags the counted label's TR — the row with the input and 「추가」 — below the fold", async () => {
    const loc = locatorFor("call_ip");
    const res = (await page.evaluate(
      buildFixedLabelLocateScript({ candidateQuery: loc.candidateQuery, exactText: loc.exactText, tag: true, tagAncestor: loc.tagAncestor, countSuffix: loc.countSuffix === true }),
    )) as { count: number; sig?: string };
    expect(res.count).toBe(1);
    expect(res.sig).toMatch(/^[0-9a-f]{16}$/);
    expect(await page.evaluate(() => Array.from(document.querySelectorAll("[data-aw-target]")).map((e) => e.id))).toEqual(["ip-row"]);
  });

  it("the driver's Playwright matcher resolves exactly the same one element", async () => {
    const loc = locatorFor("call_ip");
    const located = page.locator(loc.candidateQuery, { hasText: exactLabelRegex(loc.exactText, loc.countSuffix === true) });
    expect(await located.count()).toBe(1);
    expect(await located.first().evaluate((e) => e.closest("tr")?.id)).toBe("ip-row");
    await located.first().scrollIntoViewIfNeeded({ timeout: 2000 }); // the step that timed out live on the hidden TH
  });

  it("admits only NAVER's own count after the label — not arbitrary trailing text", async () => {
    const bad = await page.evaluate(
      buildFixedLabelLocateScript({ candidateQuery: "span", exactText: "API호출 IP", tag: false, countSuffix: true }),
    );
    expect((bad as { count: number }).count).toBe(0);
    const re = exactLabelRegex("API호출 IP", true);
    for (const ok of ["API호출 IP", "API호출 IP (2/3)", "API호출 IP(0/3)"]) expect(re.test(ok), ok).toBe(true);
    for (const no of ["API호출 IP (2/3) 외", "API호출 IP (a/3)", "API호출 IP 2/3", "xAPI호출 IP (2/3)"]) expect(re.test(no), no).toBe(false);
  });
});
