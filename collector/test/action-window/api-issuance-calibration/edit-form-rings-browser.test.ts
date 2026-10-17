/**
 * REAL-CHROMIUM test for the edit form's step (⑤ since 2026-10-18): the IP row is the primary ring, and 「저장」 and
 * the API-group heading are rung beside it — then 「저장」, which centring the row pushed above the viewport, is
 * scrolled back in while the row stays on screen. Gated behind RUN_INTEGRATION=1 (repo convention):
 *
 *   RUN_INTEGRATION=1 npx vitest run test/action-window/api-issuance-calibration/edit-form-rings-browser.test.ts
 *
 * The fixture is the shape measured value-free in the operator's Aside tab on 2026-10-18: 「저장」 is NAVER's own
 * `blocking-button` element beside 「취소」 at the top of the form, painted only while the form is open (the detail
 * page keeps it hidden); the form's 「API 그룹」 heading is a single H4 below the IP row; the IP row is ~320px under
 * 「저장」 — so a viewport-centred row leaves 「저장」 just off the top.
 */
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { chromium, type Browser, type Page } from "playwright";
import {
  buildFixedLabelAlsoRingScript,
  buildFixedLabelLocateScript,
} from "../../../src/action-window/api-issuance-calibration/visual-recon-inpage";
import { locatorFor } from "../../../src/action-window/api-issuance-calibration/issuance-highlight-selectors";
import {
  AVOID_EDIT_FORM_GROUP_LISTS,
  BRING_SECONDARY_ABOVE_INTO_VIEW,
  NaverIssuanceDriver,
} from "../../../src/action-window/naver-issuance-driver";
import { mountOverlay } from "../../../src/action-window/overlay";

const RUN = process.env.RUN_INTEGRATION === "1";

const EDIT_FORM = `<!doctype html><meta charset="utf-8"><body style="margin:0">
  <header id="bar" style="position:fixed;top:0;left:0;right:0;height:61px;background:#222;z-index:10">커머스API센터</header>
  <section id="detail" style="display:none"><button>수정</button><table><tr><th>API호출 IP</th><td>—</td></tr></table></section>
  <section id="edit" style="padding-top:500px">
    <div id="top"><blocking-button id="cancel">취소</blocking-button> <blocking-button id="save">저장</blocking-button></div>
    <table style="margin-top:290px;width:100%"><tbody>
      <tr id="ip-row" style="height:200px">
        <th><label>API호출 IP (<em>2</em>/3)</label></th>
        <td><input type="text"><button type="button">추가</button></td>
      </tr>
    </tbody></table>
    <h4 id="groups" style="margin-top:300px">API 그룹</h4>
    <div id="lists">
      <h5 id="mine">내 API 그룹 <span>4</span></h5>
      <div style="height:0"></div>
      <div class="row" style="height:91px">문의 <button>삭제</button></div>
      <div class="row" style="height:91px">주문 판매자 <button>삭제</button></div>
      <div class="row" style="height:91px">상품/N배송 <button>삭제</button></div>
      <div class="row" style="height:91px">정산 <button>삭제</button></div>
      <h5 id="all">전체 API 그룹</h5>
      <div class="row" style="height:91px">판매자정보 <button>추가</button></div>
      <h5 id="other">다른 영역</h5>
      <div id="after" style="height:91px">끝</div>
    </div>
    <div style="height:1400px"></div>
  </section>
</body>`;

describe.skipIf(!RUN)("the edit form's step rings 「저장」 and the API groups — real Chromium", () => {
  let browser: Browser;
  let page: Page;
  beforeAll(async () => {
    browser = await chromium.launch({ headless: true });
    page = await browser.newPage({ viewport: { width: 1160, height: 794 } });
    await page.setContent(EDIT_FORM);
  });
  afterAll(async () => {
    await browser?.close();
  });

  it("**rings the IP row as primary, and 「저장」 + 「API 그룹」 beside it**", async () => {
    const loc = locatorFor("call_ip");
    expect(loc.alsoRing?.map((s) => s.exactText)).toEqual(["저장", "API 그룹"]);
    await page.evaluate(
      buildFixedLabelLocateScript({ candidateQuery: loc.candidateQuery, exactText: loc.exactText, tag: true, tagAncestor: loc.tagAncestor, countSuffix: true }),
    );
    const also = (await page.evaluate(buildFixedLabelAlsoRingScript({ specs: loc.alsoRing! }))) as { rung: number };
    expect(also.rung).toBe(2);
    const tagged = await page.evaluate(() => ({
      all: Array.from(document.querySelectorAll("[data-aw-target]")).map((e) => e.id),
      primary: Array.from(document.querySelectorAll("[data-aw-primary]")).map((e) => e.id),
    }));
    expect(tagged.primary).toEqual(["ip-row"]);
    expect(tagged.all.sort()).toEqual(["groups", "ip-row", "save"]);
  });

  it("**brings 「저장」 back into view after centring the row, and keeps the row on screen**", async () => {
    await mountOverlay(page, { stepNumber: 5, totalSteps: 8, copyKey: "actionWindow.issuance.step.call_ip", guidanceEnabled: true });
    const before = await page.evaluate(() => document.getElementById("save")!.getBoundingClientRect().top);
    expect(before).toBeLessThan(0); // the defect: centring the row put 「저장」 above the viewport
    expect(await page.evaluate(BRING_SECONDARY_ABOVE_INTO_VIEW)).toBe(true);
    const after = await page.evaluate(() => {
      const s = document.getElementById("save")!.getBoundingClientRect();
      const r = document.getElementById("ip-row")!.getBoundingClientRect();
      return { saveTop: s.top, rowTop: r.top, rowBottom: r.bottom, vh: window.innerHeight };
    });
    // …and not under NAVER's fixed header, which covers whatever is scrolled to the very top (live 2026-10-18).
    expect(after.saveTop).toBeGreaterThanOrEqual(61);
    const onTop = await page.evaluate(() => {
      const r = document.getElementById("save")!.getBoundingClientRect();
      return document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2)?.id;
    });
    expect(onTop).toBe("save");
    expect(after.rowTop).toBeGreaterThanOrEqual(0);
    expect(after.rowBottom).toBeLessThanOrEqual(after.vh);
  });

  it("**marks both group lists keep-clear, and the panel steers off them once the seller scrolls there**", async () => {
    const marked = (await page.evaluate(AVOID_EDIT_FORM_GROUP_LISTS)) as { marked: number };
    // 「내 API 그룹」 + 4 rows + 「전체 API 그룹」 + 1 row; the zero-height spacer and anything after another heading are not.
    expect(marked.marked).toBe(7);
    expect(await page.evaluate(() => document.getElementById("after")!.hasAttribute("data-aw-avoid"))).toBe(false);
    await mountOverlay(page, {
      stepNumber: 5,
      totalSteps: 8,
      copyKey: "actionWindow.issuance.step.call_ip",
      guidanceEnabled: true,
      residentPanel: true,
      label: "IP · 그룹 · 저장",
      advance: { buttonLabel: "저장했어요 · 다음", token: "tok" },
    });
    // The seller scrolls down to the lists: the rings above (「저장」, the IP row) leave the viewport.
    await page.evaluate(() => window.scrollTo(0, document.getElementById("mine")!.getBoundingClientRect().top + window.scrollY - 300));
    await page.waitForTimeout(650);
    const hit = await page.evaluate(() => {
      const p = document.getElementById("__aw_advance_panel__")!.getBoundingClientRect();
      const save = document.getElementById("save")!.getBoundingClientRect();
      let overlap = 0;
      for (const e of Array.from(document.querySelectorAll("[data-aw-avoid]"))) {
        const r = e.getBoundingClientRect();
        const ox = Math.min(p.right, r.right) - Math.max(p.left, r.left);
        const oy = Math.min(p.bottom, r.bottom) - Math.max(p.top, r.top);
        if (ox > 0 && oy > 0) overlap += ox * oy;
      }
      return { overlap, saveOff: save.bottom <= 0 };
    });
    expect(hit.saveOff).toBe(true); // the case is real: a ring is off screen above
    expect(hit.overlap).toBe(0);
  });

  it("does not scroll when the row could not stay on screen", async () => {
    await page.setViewportSize({ width: 1160, height: 420 });
    try {
      await mountOverlay(page, { stepNumber: 5, totalSteps: 8, copyKey: "actionWindow.issuance.step.call_ip", guidanceEnabled: true });
      const y = await page.evaluate(() => window.scrollY);
      expect(await page.evaluate(BRING_SECONDARY_ABOVE_INTO_VIEW)).toBe(false);
      expect(await page.evaluate(() => window.scrollY)).toBe(y);
    } finally {
      await page.setViewportSize({ width: 1160, height: 794 });
    }
  });

  it("rings nothing extra when a control is not painted exactly once (the detail page's hidden 「저장」)", async () => {
    await page.evaluate(() => {
      document.getElementById("edit")!.style.display = "none";
      document.getElementById("detail")!.style.display = "block";
      for (const e of Array.from(document.querySelectorAll("[data-aw-target],[data-aw-primary]"))) {
        e.removeAttribute("data-aw-target");
        e.removeAttribute("data-aw-primary");
      }
      document.querySelector("#detail tr")!.setAttribute("data-aw-target", "");
    });
    const loc = locatorFor("call_ip");
    const also = (await page.evaluate(buildFixedLabelAlsoRingScript({ specs: loc.alsoRing! }))) as { rung: number };
    expect(also.rung).toBe(0);
  });
});

describe.skipIf(!RUN)("the park notice's 다시 확인 — real Chromium", () => {
  let browser: Browser;
  let page: Page;
  beforeAll(async () => {
    browser = await chromium.launch({ headless: true });
    page = await browser.newPage({ viewport: { width: 1160, height: 794 } });
    await page.setContent(`<!doctype html><meta charset="utf-8"><body style="margin:0"><main style="height:2000px">상세</main></body>`);
  });
  afterAll(async () => {
    await browser?.close();
  });

  it("**a real click on the notice's own button is what the driver reads** — and the notice still says what to do", async () => {
    const driver = new NaverIssuanceDriver(page, {});
    expect(await driver.showParkNotice("TARGET_NOT_FOUND")).toBe(true);
    const panel = page.locator("#__aw_advance_panel__");
    expect(await panel.getByText("화면이 모두 뜬 뒤 '다시 확인'을 눌러 주세요.").isVisible()).toBe(true);
    expect(await driver.readParkRecheck()).toBe(false);
    await panel.getByRole("button", { name: "다시 확인" }).click();
    expect(await driver.readParkRecheck()).toBe(true);
    // A script's press is not a person's: re-arm, dispatch a synthetic click, and the latch stays clear.
    expect(await driver.showParkNotice("TARGET_NOT_FOUND")).toBe(true);
    await page.evaluate(() => (document.querySelector("[data-aw-advance]") as HTMLElement).click());
    expect(await driver.readParkRecheck()).toBe(false);
  });
});
