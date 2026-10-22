#!/usr/bin/env node
// The seller's phone page and the delegate's desk, run in a real browser against a mocked vendor server — the two
// pages vendor-server serves as plain files and that no unit test drives (docs/vendor_server_contract_v1.md §4.2,
// holds after approval, owner decision 2026-10-23).
//
// Every case opens the page from vendor-server/src/main/resources/static with a fixture task, then asserts what the
// seller or the delegate is shown and what they may press. Nothing leaves the machine: every request that is not
// the page's own file or a mocked /v1 call is aborted and fails the run. Exit 0 iff every case passes.
//
//   node tools/vendor-pages-check/check.mjs
import { createRequire } from "node:module";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..", "..");
const STATIC = join(ROOT, "vendor-server", "src", "main", "resources", "static");
const { chromium } = createRequire(join(ROOT, "collector", "package.json"))("playwright");

const SENTENCE = "세탁 후 수축이 생긴 경우 무료 교환해 드리겠습니다.";
const BODY = `불편을 드려 죄송합니다. ${SENTENCE} 주문 내역에서 교환을 접수해 주세요.`;
const PROMISE = {
  codes: ["UNSUPPORTED_PROMISE"],
  sources: [{ kind: "ORG_POLICY", title: "교환 · 반품 안내", changedAt: "2026-10-22T06:40:00Z", removed: false }],
  promises: ["교환"],
  sentences: [SENTENCE],
};
const EDITED = {
  codes: ["SOURCE_CHANGED"],
  sources: [{ kind: "PRODUCT_KNOWLEDGE", title: "린넨 커튼 · 소재 안내", changedAt: "2026-10-22T06:40:00Z", removed: false }],
  promises: [],
  sentences: [],
};

function task(over) {
  return {
    id: "t-1", storeId: "s-1", reviewId: "r-1", reviewNo: "9100000310", state: "HELD", stateVersion: 4,
    draftVersion: 1, draftBody: BODY, draftFingerprint: "f".repeat(64), approvedVersion: 1,
    approvedFingerprint: "f".repeat(64), editedOnMobile: false, serverChecks: [], claimed: false,
    basisNote: PROMISE, basisNoteVersion: 1, pcRecheckVersion: null, pcRecheck: null,
    updatedAt: "2026-10-22T07:00:00Z",
    review: { productName: "린넨 커튼", rating: 1, body: "세탁 한 번에 줄어들었어요.", writtenAt: "2026-10-21T03:00:00Z",
      displayState: "노출", replied: false },
    ...over,
  };
}

/** Opens one page with `routes` as the vendor server; returns the page and what it sent. */
async function open(browser, page, routes, viewport) {
  const ctx = await browser.newContext({ viewport, timezoneId: "Asia/Seoul" });
  const key = page === "replies" ? "reviewnary.replies.session" : "reviewnary.delegate.session";
  await ctx.addInitScript((k) => sessionStorage.setItem(k, "fixture-not-a-token"), key);
  const sent = [];
  const problems = [];
  await ctx.route("**/*", (r) => {
    const u = new URL(r.request().url());
    if (u.hostname !== "vendor.test") {
      // Review photos come from NAVER's image host on the real page; nothing is fetched here.
      if (!u.hostname.endsWith("pstatic.net")) problems.push(`external request ${u.hostname}`);
      return r.abort();
    }
    if (u.pathname.startsWith("/v1/")) {
      const path = u.pathname + u.search;
      sent.push({ method: r.request().method(), path, body: r.request().postDataJSON?.() ?? null });
      const answer = routes(r.request().method(), path);
      if (answer === undefined) problems.push(`unmocked ${path}`);
      return r.fulfill({ status: answer === undefined ? 404 : 200, contentType: "application/json",
        body: JSON.stringify(answer ?? {}) });
    }
    const file = u.pathname.endsWith("/") ? `${u.pathname}index.html` : u.pathname;
    const type = file.endsWith(".css") ? "text/css" : file.endsWith(".js") ? "text/javascript" : "text/html";
    return r.fulfill({ status: 200, contentType: type, body: readFileSync(join(STATIC, file)) });
  });
  const p = await ctx.newPage();
  p.on("pageerror", (e) => problems.push(`page error ${String(e).slice(0, 160)}`));
  await p.goto(`http://vendor.test/${page}/`);
  return { p, ctx, sent, problems };
}

function phoneRoutes(t) {
  return (method, path) => {
    if (path === "/v1/stores") return [{ id: "s-1", label: "합성 스토어", channel: "NAVER" }];
    if (path.startsWith("/v1/stores/s-1/tasks?state=")) return path.endsWith(`=${t.state}`) ? [t] : [];
    if (path === "/v1/tasks/t-1") return t;
    return undefined;
  };
}

/** The phone page with one task, opened from the list. */
async function phone(browser, t) {
  const run = await open(browser, "replies", phoneRoutes(t), { width: 390, height: 844 });
  await run.p.getByText("린넨 커튼").first().click();
  await run.p.locator("#task-state").waitFor();
  return run;
}

const text = (p, sel) => p.locator(sel).innerText();
const shown = (p, sel) => p.locator(sel).isVisible();

const cases = [
  ["phone · a held reply says why, marks its sentence, and offers an edit", async (b) => {
    const { p, ...run } = await phone(b, task({ state: "HELD" }));
    expect(await text(p, "#task-state"), "승인 보류 · 다시 승인 필요");
    expectHas(await text(p, "#why-text"), "운영 정책 「교환 · 반품 안내」 문서가");
    expectHas(await text(p, "#why-text"), "이 답글의 「교환」 약속이 지금 근거와 맞지 않습니다.");
    expectHas(await text(p, "#why-todo"), "문장을 고쳐 다시 승인하세요.");
    expect(await p.locator("#reply-body .hl").allInnerTexts(), [SENTENCE]);
    expect(await shown(p, "#edit"), true);
    expect(await shown(p, "#approve"), false);
    expect(await text(p, "#cancel"), "답글 취소");
    return run;
  }],
  ["phone · a hold sent to the delegate says the delegate was told", async (b) => {
    const { p, ...run } = await phone(b, task({ state: "HOLD_REQUESTED", claimed: true }));
    expect(await text(p, "#task-state"), "승인 보류 · 담당자 확인 중");
    expectHas(await text(p, "#state-note"), "담당자에게도 멈춤을 전했습니다.");
    expect(await shown(p, "#edit"), false);
    expect(await shown(p, "#approved-note"), false);
    expect(await text(p, "#cancel"), "취소 요청");
    return run;
  }],
  ["phone · a sent-back draft names the edited document and does not guess the sentence", async (b) => {
    const { p, ...run } = await phone(b, task({ state: "RECHECK_NEEDED", approvedVersion: null, basisNote: EDITED }));
    expectHas(await text(p, "#why-text"), "상품 지식 「린넨 커튼 · 소재 안내」 문서가");
    expectHas(await text(p, "#why-text"), "어느 문장이 달라졌는지는 아직 짚지 못합니다.");
    expect(await p.locator("#reply-body .hl").count(), 0);
    expect(await shown(p, "#state-note"), false);
    return run;
  }],
  ["phone · an approval kept after a document edit says so in one line", async (b) => {
    const { p, ...run } = await phone(b, task({ state: "APPROVED", basisNote: EDITED }));
    expectHas(await text(p, "#why-text"), "이 답글과 부딪히는 곳이 없어 승인을 그대로 둡니다.");
    expect(await shown(p, "#why-todo"), false);
    return run;
  }],
  ["phone · a phone edit the PC flagged is advice before approval, not a hold", async (b) => {
    const { p, ...run } = await phone(b, task({
      state: "AWAITING_APPROVAL", approvedVersion: null, editedOnMobile: true, pcRecheckVersion: 1,
      pcRecheck: "FLAGGED:UNSUPPORTED_PROMISE", basisNote: { ...PROMISE, sources: [] } }));
    expectHas(await text(p, "#why-todo"), "이 약속이 맞는지 확인하고 승인하세요.");
    expect(await shown(p, "#unchecked"), false);
    expect(await shown(p, "#approve"), true);
    return run;
  }],
  ["phone · the server's words are drawn as text, never as markup", async (b) => {
    const html = "<b id=\"injected\">굵게</b> 교환해 드리겠습니다.";
    const { p, ...run } = await phone(b, task({ draftBody: `안녕하세요. ${html}`,
      basisNote: { ...PROMISE, sources: [{ ...PROMISE.sources[0], title: "<i id=\"t\">안내</i>" }], sentences: [html] } }));
    expect(await p.locator("#injected").count(), 0);
    expect(await p.locator("#t").count(), 0);
    expect(await p.locator("#reply-body .hl").allInnerTexts(), [html]);
    return run;
  }],
  ["delegate · the pre-save check stops a held reply, says why, and settles it as not posted", async (b) => {
    const mine = { id: "t-1", storeId: "s-1", storeLabel: "합성 스토어", state: "HOLD_REQUESTED", stateVersion: 4,
      reviewNo: "9100000310", productName: "린넨 커튼", rating: 1, reviewBody: "세탁 한 번에 줄어들었어요.",
      approvedBody: BODY, approvedFingerprint: "f".repeat(64), approvedAt: "2026-10-21T05:00:00Z", mine: true };
    const run = await open(b, "delegate", (method, path) => {
      if (path === "/v1/delegate/me") return { email: "delegate@example.invalid", stores: [] };
      if (path.startsWith("/v1/delegate/tasks?state=")) return path.endsWith("=HOLD_REQUESTED") ? [mine] : [];
      if (path === "/v1/delegate/tasks/t-1/preflight") return { taskId: "t-1", state: "HOLD_REQUESTED",
        approvedBody: BODY, approvedFingerprint: "f".repeat(64), reviewChanged: false, displayChanged: false,
        alreadyReplied: false, cancelRequested: false, holdRequested: true, holdNote: PROMISE, clearToPost: false };
      if (path === "/v1/delegate/tasks/t-1/report" && method === "POST") return { ...task({ state: "HELD" }) };
      return undefined;
    }, { width: 1100, height: 900 });
    const { p } = run;
    expect(await text(p, "#mine"), (s) => s.includes("판매자가 승인 보류"));
    await p.getByRole("button", { name: "열기" }).first().click();
    await p.locator("#preflight").click();
    await p.locator("#pre-result").waitFor({ state: "visible" });
    expect(await text(p, "#pre-chip"), "등록하지 마세요");
    expectHas(await text(p, "#pre-text"), "판매자가 이 답글의 승인을 보류했습니다.");
    expectHas(await text(p, "#pre-text"), "교환");
    expectHas(await text(p, "#pre-text"), "판매자센터에 붙여 넣지 마세요.");
    expect(await p.locator("#copy-reply").isDisabled(), true);
    expect(await text(p, "#stop-failed"), "등록 안 했음 · 보류 확인");
    expect(await shown(p, "#stop-posted"), true);
    await p.locator("#stop-failed").click();
    await p.locator("#list-notice").filter({ hasText: "보류를 확인했습니다" }).waitFor();
    const report = run.sent.find((s) => s.path === "/v1/delegate/tasks/t-1/report");
    expect(report?.body?.outcome, "FAILED");
    return run;
  }],
];

class Failure extends Error {}
function expect(actual, wanted) {
  const ok = typeof wanted === "function" ? wanted(actual) : JSON.stringify(actual) === JSON.stringify(wanted);
  if (!ok) throw new Failure(`expected ${typeof wanted === "function" ? "a match" : JSON.stringify(wanted)}, `
    + `got ${JSON.stringify(actual)}`);
}
function expectHas(actual, part) {
  if (!String(actual).includes(part)) throw new Failure(`expected to contain ${JSON.stringify(part)}, got ${JSON.stringify(actual)}`);
}

const browser = await chromium.launch();
let failed = 0;
for (const [name, run] of cases) {
  let ctx;
  try {
    const result = await run(browser);
    ctx = result.ctx;
    if (result.problems.length) throw new Failure(result.problems.join("; "));
    console.log(`PASS ${name}`);
  } catch (e) {
    failed++;
    console.log(`FAIL ${name} — ${e instanceof Failure ? e.message : String(e).split("\n")[0]}`);
  } finally {
    await ctx?.close();
  }
}
await browser.close();
console.log(`${cases.length - failed}/${cases.length} passed`);
process.exit(failed ? 1 : 0);
