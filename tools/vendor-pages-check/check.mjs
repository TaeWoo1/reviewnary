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

/**
 * Opens one page with `routes` as the vendor server; returns the page and what it sent. `session: false` opens it
 * signed out; `hash` is the fragment it is opened with; `storage` is put in its sessionStorage first. A visit to
 * Google is recorded in `google` and answered with an empty page.
 */
async function open(browser, page, routes, viewport, at, { session = true, hash = "", storage = {} } = {}) {
  const ctx = await browser.newContext({ viewport, timezoneId: "Asia/Seoul" });
  if (at) await ctx.clock.setFixedTime(new Date(at));
  const key = page === "replies" ? "reviewnary.replies.session" : "reviewnary.delegate.session";
  const seed = { ...(session ? { [key]: "fixture-not-a-token" } : {}), ...storage };
  // Only on the first load: a page that removes a key must not find it put back by a later navigation.
  await ctx.addInitScript((kv) => {
    if (sessionStorage.getItem("check.seeded")) return;
    sessionStorage.setItem("check.seeded", "1");
    for (const [k, v] of Object.entries(kv)) sessionStorage.setItem(k, v);
  }, seed);
  const sent = [];
  const google = [];
  const problems = [];
  await ctx.route("**/*", (r) => {
    const u = new URL(r.request().url());
    if (u.hostname === "accounts.google.com") {
      google.push(u.toString());
      return r.fulfill({ status: 200, contentType: "text/html", body: "<!doctype html><title>Google</title>" });
    }
    if (u.hostname !== "vendor.test") {
      // Review photos come from NAVER's image host on the real page; nothing is fetched here.
      if (!u.hostname.endsWith("pstatic.net")) problems.push(`external request ${u.hostname}`);
      return r.abort();
    }
    if (u.pathname.startsWith("/v1/")) {
      const path = u.pathname + u.search;
      sent.push({ method: r.request().method(), path, body: r.request().postDataJSON?.() ?? null });
      const answer = routes(r.request().method(), path, sent[sent.length - 1].body);
      if (answer === undefined) problems.push(`unmocked ${path}`);
      return r.fulfill({ status: answer === undefined ? 404 : 200, contentType: "application/json",
        body: JSON.stringify(answer ?? {}) });
    }
    // /delegate/join is served from join.html (AppPagesController).
    const file = u.pathname === "/delegate/join/" ? "/delegate/join.html"
      : u.pathname.endsWith("/") ? `${u.pathname}index.html` : u.pathname;
    const type = file.endsWith(".css") ? "text/css" : file.endsWith(".js") ? "text/javascript" : "text/html";
    return r.fulfill({ status: 200, contentType: type, body: readFileSync(join(STATIC, file)) });
  });
  const p = await ctx.newPage();
  p.on("pageerror", (e) => problems.push(`page error ${String(e).slice(0, 160)}`));
  await p.goto(`http://vendor.test/${page}/${hash}`);
  return { p, ctx, sent, google, problems };
}

// The phone page's clock: 16:05 in Korea. The fixture task was last touched at 16:00, the PC used its link at 16:00.
const PHONE_NOW = "2026-10-22T07:05:00Z";
const PC_ON = [{ id: "i-1", lastUsedAt: "2026-10-22T07:00:00Z" }];
const PC_OFF = [{ id: "i-1", lastUsedAt: "2026-10-22T06:30:00Z" }];
// Which tab a state is listed under (docs/vendor_server_contract_v1.md §8 판매자 휴대폰: 내가 할 일 · 진행 중 · 끝난 일).
const TAB = { AWAITING_APPROVAL: "todo", RECHECK_NEEDED: "todo", HELD: "todo", FAILED: "todo",
  APPROVED: "moving", CLAIMED: "moving", CANCEL_REQUESTED: "moving", HOLD_REQUESTED: "moving", UNKNOWN: "moving",
  POSTED: "moving", CANCELLED: "done", INVALIDATED: "done" };

/**
 * The vendor server for one phone task. It keeps the task's current version: a saved draft (`saved`) or an approval
 * (`approved`) replaces it, and the lists and the task read that version back. A save or approval the case did not
 * expect is unmocked and fails the run.
 */
function phoneRoutes(t, { installs = PC_ON, saved, approved } = {}) {
  let cur = t;
  return (method, path, body) => {
    if (path === "/v1/stores") return [{ id: "s-1", label: "합성 스토어", channel: "NAVER" }];
    if (path === "/v1/installs") return installs;
    if (path.startsWith("/v1/stores/s-1/tasks?state=")) return path.endsWith(`=${cur.state}`) ? [cur] : [];
    if (path === "/v1/tasks/t-1" && method === "GET") return cur;
    if (path === "/v1/tasks/t-1/draft" && method === "PUT" && saved) return (cur = saved(cur, body));
    if (path === "/v1/tasks/t-1/approve" && method === "POST" && approved) return (cur = approved(cur, body));
    return undefined;
  };
}

/** The phone page with one task: its tab counts it, and its row opens it. */
async function phone(browser, t, opts) {
  const run = await open(browser, "replies", phoneRoutes(t, opts), { width: 390, height: 844 }, PHONE_NOW);
  const { p } = run;
  const tab = TAB[t.state];
  await p.locator("#view-list").waitFor({ state: "visible" });
  for (const k of ["todo", "moving"]) expect(await text(p, `#${k}-count`), k === tab ? "1" : "0");
  await p.locator(`.tab[data-tab="${tab}"]`).click();
  const row = p.locator(`#list-${tab} .item`);
  expect(await row.count(), 1);
  run.row = await row.innerText();
  await row.click();
  await p.locator("#view-task").waitFor({ state: "visible" });
  return run;
}

/** A second page in the same case: its problems count against the case. */
async function also(run, other) {
  run.problems.push(...other.problems);
  await other.ctx.close();
}

/** Rewrites the reply box and lets the page save it on its own, as the seller would. */
async function rewrite(p, body, how) {
  const put = p.waitForRequest((r) => r.method() === "PUT" && r.url().endsWith("/v1/tasks/t-1/draft"));
  await p.locator("#reply-edit").fill(body);
  if (how === "blur") await p.locator("#reply-edit").blur(); // otherwise the 1.5 s pause saves it
  await put;
  await p.locator("#draft-status").filter({ hasText: /^PC 검사 전$/ }).waitFor();
}

const text = (p, sel) => p.locator(sel).innerText();
const shown = (p, sel) => p.locator(sel).isVisible();
const marks = (p) => p.locator("#reply-body .hl").allInnerTexts();
const BANNED = "고객님 탓이 아닙니다.";
const BANNED_NOTE = { codes: ["FORBIDDEN_PHRASE"], sources: [], promises: [], sentences: [BANNED], phrases: ["고객님 탓"] };
/** A version written on the phone and saved: the PC has not checked it, and the old version's note is not its note. */
const phoneVersion = (cur, body) => ({ ...cur, state: "AWAITING_APPROVAL", draftVersion: cur.draftVersion + 1,
  draftBody: body.body, draftFingerprint: "e".repeat(64), approvedVersion: null, editedOnMobile: true,
  pcRecheckVersion: cur.pcRecheckVersion, pcRecheck: cur.pcRecheck, updatedAt: "2026-10-22T07:05:00Z" });

const cases = [
  ["phone · a held reply says why, cannot be requested again unchanged, and an edit makes it requestable", async (b) => {
    const edited = BODY.replace(SENTENCE, "세탁 방법을 다시 안내해 드리겠습니다.");
    const run = await phone(b, task({ state: "HELD" }), { saved: phoneVersion });
    const { p } = run;
    expectHas(run.row, "고칠 곳 1");
    expect(await text(p, "#task-state"), "고칠 곳 1");
    expect(await text(p, "#why-text"),
      "운영 정책 「교환 · 반품 안내」 문서가 10월 22일에 고쳐져, 이 답글의 「교환」 약속이 지금 근거와 맞지 않습니다.");
    expect(await text(p, "#why-todo"), "문장을 고쳐 다시 요청하세요. 그 약속이 맞다면 PC에서 근거를 먼저 고쳐 주세요.");
    expect(await text(p, "#draft-status"), "고칠 곳 1");
    expect(await shown(p, "#reply-edit"), true);
    expect(await p.locator("#reply-edit").inputValue(), BODY);
    expect(await shown(p, "#approve"), false);
    expect(await shown(p, "#approve-note"), false);
    expect(await text(p, "#cancel"), "요청 취소");
    await rewrite(p, edited, "blur");
    const put = run.sent.filter((s) => s.method === "PUT");
    expect(put.map((s) => s.body), [{ baseVersion: 1, body: edited }]);
    expect(await shown(p, "#why"), false);
    expect(await shown(p, "#approve"), true);
    expect(await p.locator("#approve").isEnabled(), true);
    return run;
  }],
  ["phone · a hold sent to the delegate says the stop was asked of them, and marks the sentence", async (b) => {
    const run = await phone(b, task({ state: "HOLD_REQUESTED", claimed: true }));
    const { p } = run;
    expect(await text(p, "#task-state"), "멈춤 요청 중");
    expect(await text(p, "#state-note"), "담당자가 등록 전인지 확인하면 바뀝니다");
    expectHas(await text(p, "#why-text"), "이 답글의 「교환」 약속이 지금 근거와 맞지 않습니다.");
    expect(await text(p, "#reply-body"), BODY);
    expect(await marks(p), [SENTENCE]);
    expect(await shown(p, "#reply-edit"), false);
    expect(await shown(p, "#approve"), false);
    expect(await text(p, "#cancel"), "취소 요청");
    await p.locator("#cancel").click();
    expect(await text(p, "#confirm-cancel-text"), "담당자에게 등록을 멈춰 달라고 요청할까요?");
    return run;
  }],
  ["phone · a sent-back draft names the edited document and does not guess the sentence", async (b) => {
    const run = await phone(b, task({ state: "RECHECK_NEEDED", approvedVersion: null, basisNote: EDITED }));
    const { p } = run;
    expect(await text(p, "#task-state"), "다시 검사 중");
    expect(await text(p, "#why-text"), "이 초안이 근거로 쓴 상품 지식 「린넨 커튼 · 소재 안내」 문서가 10월 22일에 고쳐졌습니다."
      + " 어느 문장이 달라졌는지는 아직 짚지 못합니다.");
    expect(await text(p, "#why-todo"), "바뀐 문서와 비교해 보고, 맞으면 그대로, 아니면 고쳐 주세요. PC가 다시 검사합니다.");
    expect(await text(p, "#draft-status"), "다시 검사 중");
    expect(await p.locator("#reply-edit").inputValue(), BODY);
    expect(await p.locator(".hl").count(), 0);
    expect(await shown(p, "#state-note"), false);
    expect(await shown(p, "#approve"), false);
    return run;
  }],
  ["phone · an approval kept after a document edit says so in one line, with no sentence marked", async (b) => {
    const run = await phone(b, task({ state: "APPROVED", basisNote: EDITED }));
    const { p } = run;
    expect(await text(p, "#task-state"), "담당자 대기");
    expect(await text(p, "#why-text"),
      "근거로 쓴 상품 지식 「린넨 커튼 · 소재 안내」 문서가 10월 22일에 고쳐졌지만, 이 답글과 부딪히는 곳이 없어 승인을 그대로 둡니다.");
    expect(await shown(p, "#why-todo"), false);
    expect(await text(p, "#why-todo"), "");
    expect(await text(p, "#reply-body"), BODY);
    expect(await marks(p), []);
    expect(await text(p, "#state-note"), "16:00 요청");
    expect(await text(p, "#cancel"), "요청 취소");
    return run;
  }],
  ["phone · a phone edit the PC flagged is advice before approval, not a hold", async (b) => {
    const run = await phone(b, task({
      state: "AWAITING_APPROVAL", approvedVersion: null, editedOnMobile: true, pcRecheckVersion: 1,
      pcRecheck: "FLAGGED:UNSUPPORTED_PROMISE", basisNote: { ...PROMISE, sources: [] } }), {
      approved: (cur) => ({ ...cur, state: "APPROVED", approvedVersion: 1, approvedAt: "2026-10-22T07:04:00Z" }) });
    const { p } = run;
    expect(await shown(p, "#task-state"), false);
    expect(await text(p, "#why-text"), "이 답글의 「교환」 약속이 지금 근거와 맞지 않습니다.");
    expect(await text(p, "#why-todo"), "이 약속이 맞는지 보고 요청하세요. 맞다면 PC에서 근거에 먼저 적어 두면 좋습니다.");
    expect(await text(p, "#draft-status"), "고칠 곳 1");
    expect(await shown(p, "#approve"), true);
    expect(await p.locator("#approve").isEnabled(), true);
    await p.locator("#approve").click();
    await p.locator("#task-state").filter({ hasText: "담당자 대기" }).waitFor();
    const sent = run.sent.find((s) => s.path === "/v1/tasks/t-1/approve");
    expect([sent?.body?.version, sent?.body?.fingerprint], [1, "f".repeat(64)]);
    expect(await text(p, "#why-text"), "확인하고 승인한 답글입니다 — 근거에 없는 약속 「교환」.");
    expect(await text(p, "#state-note"), "16:04 요청");
    return run;
  }],
  ["phone · a reply held for a forbidden phrase names it, says to take it out, and marks its sentence", async (b) => {
    const body = `불편을 드려 죄송합니다. ${BANNED}`;
    const run = await phone(b, task({ draftBody: body, basisNote: BANNED_NOTE }));
    const { p } = run;
    expect(await text(p, "#task-state"), "고칠 곳 1");
    expect(await text(p, "#why-text"), "회사가 쓰지 않기로 한 표현이 있습니다: 「고객님 탓」.");
    expect(await text(p, "#why-todo"), "그 표현을 빼고 고친 뒤 다시 요청하세요.");
    expect(await p.locator("#reply-edit").inputValue(), body);
    expect(await shown(p, "#approve"), false);
    // The box the seller edits cannot carry a mark; the read-only reply does (see the report on HELD).
    const asked = await phone(b, task({ state: "HOLD_REQUESTED", claimed: true, draftBody: body, basisNote: BANNED_NOTE }));
    expect(await text(asked.p, "#why-text"), "회사가 쓰지 않기로 한 표현이 있습니다: 「고객님 탓」.");
    expect(await marks(asked.p), [BANNED]);
    await also(run, asked);
    return run;
  }],
  ["phone · a phone edit carrying a banned phrase cannot be requested until fixed", async (b) => {
    const fixed = "불편을 드려 죄송합니다. 다음에는 더 꼼꼼히 안내하겠습니다.";
    const run = await phone(b, task({ state: "AWAITING_APPROVAL", approvedVersion: null, editedOnMobile: true,
      draftBody: `불편을 드려 죄송합니다. ${BANNED}`, pcRecheckVersion: 1, pcRecheck: "FLAGGED:FORBIDDEN_PHRASE",
      basisNote: BANNED_NOTE }), { saved: phoneVersion });
    const { p } = run;
    expect(await text(p, "#why-text"), "회사가 쓰지 않기로 한 표현이 있습니다: 「고객님 탓」.");
    expect(await text(p, "#why-todo"), "그 표현을 빼고 고치면 요청할 수 있습니다.");
    expect(await text(p, "#draft-status"), "고칠 곳 1");
    expect(await shown(p, "#approve"), false);
    expect(await shown(p, "#approve-note"), false);
    expect(await shown(p, "#reply-edit"), true);
    await rewrite(p, fixed, "pause");
    expect(run.sent.filter((s) => s.method === "PUT").map((s) => s.body), [{ baseVersion: 1, body: fixed }]);
    expect(await shown(p, "#why"), false);
    expect(await shown(p, "#approve"), true);
    expect(await p.locator("#approve").isEnabled(), true);
    return run;
  }],
  ["phone · a phone edit approved before the PC checked it waits for the PC, and says so", async (b) => {
    const run = await phone(b, task({ state: "AWAITING_APPROVAL", approvedVersion: null, editedOnMobile: true,
      draftVersion: 2, pcRecheckVersion: null, pcRecheck: null, basisNote: null }), { installs: PC_OFF,
      approved: (cur) => ({ ...cur, state: "APPROVED", approvedVersion: 2, approvedAt: "2026-10-22T07:04:00Z" }) });
    const { p } = run;
    expect(await text(p, "#draft-status"), "PC 검사 전");
    expect(await shown(p, "#approve"), true);
    expect(await text(p, "#approve-note"), "이 문장 그대로 승인 · 담당자가 네이버에 등록");
    await p.locator("#approve").click();
    await p.locator("#task-state").filter({ hasText: /^검사 대기$/ }).waitFor();
    expect(run.sent.find((s) => s.path === "/v1/tasks/t-1/approve")?.body?.version, 2);
    expect(await text(p, "#state-note"), "16:04 요청 · PC가 켜지면 검사 후 담당자에게 넘어갑니다.");
    expect(await shown(p, "#approve"), false);
    await p.locator("#back").click();
    await p.locator("#view-list").waitFor({ state: "visible" });
    await p.locator("#pc-off").waitFor({ state: "visible", timeout: 3000 });
    expect(await text(p, "#pc-off"), "PC 꺼짐 · 새 초안과 검사는 PC가 켜진 뒤");
    expect(await text(p, "#todo-count"), "0");
    expect(await text(p, "#moving-count"), "1");
    await p.locator('.tab[data-tab="moving"]').click();
    expectHas(await text(p, "#list-moving"), "검사 대기");
    expectHas(await text(p, "#list-moving"), "PC가 켜지면 검사");
    return run;
  }],
  ["phone · the server's words are drawn as text, never as markup", async (b) => {
    const html = "<b id=\"injected\">굵게</b> 교환해 드리겠습니다.";
    const body = `안녕하세요. ${html}`;
    const fixture = (state) => task({ state, claimed: state === "HOLD_REQUESTED", draftBody: body,
      review: { productName: "<u id=\"p\">린넨 커튼</u>", rating: 1, body: "<s id=\"r\">줄었어요</s>",
        writtenAt: "2026-10-21T03:00:00Z", displayState: "노출", replied: false },
      basisNote: { ...PROMISE, sources: [{ ...PROMISE.sources[0], title: "<i id=\"t\">안내</i>" }], sentences: [html] } });
    const run = await phone(b, fixture("HOLD_REQUESTED"));
    const { p } = run;
    expect(await marks(p), [html]);
    expectHas(await text(p, "#why-text"), "운영 정책 「<i id=\"t\">안내</i>」 문서가");
    expect(await text(p, "#review-body"), "<s id=\"r\">줄었어요</s>");
    expectHas(run.row, "<u id=\"p\">린넨 커튼</u>");
    for (const id of ["#injected", "#t", "#r", "#p"]) expect(await p.locator(id).count(), 0);
    const held = await phone(b, fixture("HELD"));
    expect(await held.p.locator("#reply-edit").inputValue(), body);
    expectHas(await text(held.p, "#why-text"), "운영 정책 「<i id=\"t\">안내</i>」 문서가");
    for (const id of ["#injected", "#t", "#r", "#p"]) expect(await held.p.locator(id).count(), 0);
    await also(run, held);
    return run;
  }],
  ["delegate · the pre-save check stops a held reply, says why, and settles it as not posted", async (b) => {
    const mine = { id: "t-1", storeId: "s-1", storeLabel: "합성 스토어", state: "HOLD_REQUESTED", stateVersion: 4,
      reviewNo: "9100000310", productName: "린넨 커튼", rating: 1, reviewBody: "세탁 한 번에 줄어들었어요.",
      approvedBody: BODY, approvedFingerprint: "f".repeat(64), approvedAt: "2026-10-21T05:00:00Z", mine: true };
    const run = await open(b, "delegate", (method, path) => {
      if (path === "/v1/delegate/auth/methods") return { google: false };
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
  ["delegate · the upload tab says which period to download: from the last upload, or a year the first time", async (b) => {
    const run = await open(b, "delegate", (method, path) => {
      if (path === "/v1/delegate/auth/methods") return { google: false };
      if (path === "/v1/delegate/me") return { email: "delegate@example.invalid", stores: [
        // 01:30 on the 13th in Korea is still the 12th in UTC: the period is Seller Center's date, not UTC's.
        { id: "s-1", channel: "NAVER", label: "합성 스토어", lastUploadAt: "2026-10-12T16:30:00Z" },
        { id: "s-2", channel: "NAVER", label: "새 스토어", lastUploadAt: null }] };
      if (path.startsWith("/v1/delegate/tasks?state=")) return [];
      return undefined;
    }, { width: 1100, height: 900 }, "2026-10-25T03:00:00Z");
    const { p } = run;
    await p.locator("#tab-upload").click();
    await p.locator("#period").waitFor({ state: "visible" });
    expect(await text(p, "#period-range"), "2026.10.13 ~ 2026.10.25");
    expect(await text(p, "#period-why"), "· 마지막으로 올린 날부터");
    await p.locator("#store").selectOption("s-2");
    expect(await text(p, "#period-range"), "2025.10.25 ~ 2026.10.25");
    expect(await text(p, "#period-why"), "· 처음 올리는 스토어라 판매자센터 최대 기간 1년");
    return run;
  }],
  ["delegate · with Google on, the login offers it first and the password form stays below", async (b) => {
    const run = await open(b, "delegate", (method, path, body) => {
      if (path === "/v1/delegate/auth/methods") return { google: true };
      if (path === "/v1/delegate/auth/google/start" && method === "POST" && body?.purpose === "LOGIN")
        return { url: "https://accounts.google.com/o/oauth2/v2/auth?client_id=fixture" };
      return undefined;
    }, { width: 1100, height: 900 }, null, { session: false });
    const { p } = run;
    await p.locator("#google-login").waitFor({ state: "visible" });
    expect(await shown(p, "#login-form"), true);
    expect(await text(p, "#app-login-title"), "비밀번호와 인증 앱으로 등록한 담당자");
    await p.locator("#google-signin").click();
    await p.waitForURL(/accounts\.google\.com/);
    const start = run.sent.find((s) => s.path === "/v1/delegate/auth/google/start");
    expect(start?.body?.binding, (v) => /^[A-Za-z0-9_-]{43}$/.test(v));
    expect(start?.body?.inviteCode, undefined);
    expect(run.google.length, 1);
    return run;
  }],
  ["delegate · back from Google, the tab that started it finishes with its binding and opens signed in", async (b) => {
    const BINDING = "b".repeat(43);
    const run = await open(b, "delegate", (method, path, body) => {
      if (path === "/v1/delegate/auth/methods") return { google: true };
      if (path === "/v1/delegate/auth/google/finish" && method === "POST" && body?.binding === BINDING
        && body?.handoff === "h".repeat(43)) return { token: "fixture-session", expiresAt: "2026-10-27T00:00:00Z" };
      if (path === "/v1/delegate/me") return { email: "owner@example.invalid", role: "ADMIN", stores: [] };
      if (path.startsWith("/v1/delegate/tasks?state=")) return [];
      return undefined;
    }, { width: 1100, height: 900 }, null,
    { session: false, hash: `#google=${"h".repeat(43)}`, storage: { "reviewnary.delegate.google": BINDING } });
    const { p } = run;
    await p.locator("#view-list").waitFor({ state: "visible" });
    await p.locator("#who").filter({ hasText: "owner@example.invalid" }).waitFor();
    expect(await p.evaluate(() => window.location.hash), "");
    expect(await p.evaluate(() => sessionStorage.getItem("reviewnary.delegate.google")), null);
    expect(await p.evaluate(() => sessionStorage.getItem("reviewnary.delegate.session")), "fixture-session");
    return run;
  }],
  ["delegate · a Google return link opened in another tab finishes nothing and says why", async (b) => {
    const run = await open(b, "delegate", (method, path) => {
      if (path === "/v1/delegate/auth/methods") return { google: true };
      return undefined;
    }, { width: 1100, height: 900 }, null, { session: false, hash: `#google=${"h".repeat(43)}` });
    const { p } = run;
    await p.locator("#google-error").waitFor({ state: "visible" });
    expectHas(await text(p, "#google-error"), "구글 로그인을 마치지 못했습니다");
    expect(run.sent.some((s) => s.path === "/v1/delegate/auth/google/finish"), false);
    expect(await p.evaluate(() => window.location.hash), "");
    const mismatch = await open(b, "delegate", (method, path) => (path === "/v1/delegate/auth/methods"
      ? { google: true } : undefined), { width: 1100, height: 900 }, null,
    { session: false, hash: "#google-error=email_mismatch" });
    await mismatch.p.locator("#google-error").waitFor({ state: "visible" });
    expectHas(await text(mismatch.p, "#google-error"), "초대받은 주소와 다른 구글 계정입니다");
    await mismatch.ctx.close();
    if (mismatch.problems.length) throw new Failure(mismatch.problems.join("; "));
    return run;
  }],
  ["join · an invite opens on Google first; the password path is one press away, and alone when Google is off", async (b) => {
    const CODE = "i".repeat(43);
    const run = await open(b, "delegate/join", (method, path, body) => {
      if (path === "/v1/delegate/auth/methods") return { google: true };
      if (path === "/v1/delegate/auth/google/start" && method === "POST" && body?.purpose === "JOIN"
        && body?.inviteCode === CODE) return { url: "https://accounts.google.com/o/oauth2/v2/auth?client_id=fixture" };
      return undefined;
    }, { width: 390, height: 844 }, null, { session: false, hash: `#${CODE}` });
    const { p } = run;
    await p.locator("#view-google").waitFor({ state: "visible" });
    expect(await p.evaluate(() => window.location.hash), "");
    expect(await shown(p, "#steps"), false);
    await p.locator("#use-app").click();
    expect(await shown(p, "#view-password"), true);
    expect(await shown(p, "#steps"), true);

    const again = await open(b, "delegate/join", (method, path, body) => {
      if (path === "/v1/delegate/auth/methods") return { google: true };
      if (path === "/v1/delegate/auth/google/start" && method === "POST" && body?.purpose === "JOIN"
        && body?.inviteCode === CODE) return { url: "https://accounts.google.com/o/oauth2/v2/auth?client_id=fixture" };
      return undefined;
    }, { width: 390, height: 844 }, null, { session: false, hash: `#${CODE}` });
    await again.p.locator("#google-join").click();
    await again.p.waitForURL(/accounts\.google\.com/);
    const start = again.sent.find((s) => s.path === "/v1/delegate/auth/google/start");
    expect(start?.body?.binding, (v) => /^[A-Za-z0-9_-]{43}$/.test(v));
    await again.ctx.close();
    if (again.problems.length) throw new Failure(again.problems.join("; "));

    const off = await open(b, "delegate/join", (method, path) => (path === "/v1/delegate/auth/methods"
      ? { google: false } : undefined), { width: 390, height: 844 }, null, { session: false, hash: `#${CODE}` });
    await off.p.locator("#view-password").waitFor({ state: "visible" });
    expect(await shown(off.p, "#view-google"), false);
    await off.ctx.close();
    if (off.problems.length) throw new Failure(off.problems.join("; "));
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
