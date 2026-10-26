#!/usr/bin/env node
// Measures how fast the QA copy of the seller-PC stack does the three things a seller waits on — on made-up data,
// through the real pages, the way a person uses them:
//
//   1. screens  — how long Home and the review list take to settle, and which calls behind them are slow;
//   2. upload   — a delegate uploads a NAVER review export on the desk: how long the desk takes to answer, and how long
//                 until the PC holds every review (the PC pulls on a timer, SELLER_PC_DEMO_PULL_MS, 30 s by default);
//   3. drafts   — 「AI 초안 준비」 on unanswered reviews: how long until the draft is on screen, and how many fail.
//   4. live     — the review list and Home left open on the PC while a delegate uploads: how long until each reads
//                 again by itself and shows the new reviews, with no reload.
//
//   node tools/dev/seller-pc-qa-seed.mjs            # first, once
//   node tools/dev/seller-pc-qa-perf.mjs screens
//   node tools/dev/seller-pc-qa-perf.mjs upload 3000
//   node tools/dev/seller-pc-qa-perf.mjs drafts 20
//   node tools/dev/seller-pc-qa-perf.mjs live 300
//
// Uploads add reviews to the QA store for good (new review numbers each time, dated over the last year). Drafts mark
// the reviews they touch 「대응 필요」 and spend one model call each, as the button does. Results are appended to
// tools/dev/.run/seller-pc-qa/perf.jsonl. Nothing secret is printed; the review text here is synthetic.
import { createRequire } from "node:module";
import { appendFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import {
  BAD, GOOD, HEADERS, OK, PRODUCTS, ROOT, RUN, SAFETY, SCREEN,
  deskSignIn, deskUpload, kst, loadState, pcSignIn, rng, saveState, sql, until, xlsx,
} from "./seller-pc-qa-seed.mjs";

const { chromium } = createRequire(join(ROOT, "collector", "package.json"))("playwright");
const OUT = join(RUN, "perf.jsonl");
const say = (line) => console.log(line);

const median = (xs) => {
  if (!xs.length) return null;
  const s = [...xs].sort((a, b) => a - b);
  return s[Math.floor((s.length - 1) / 2)];
};
const pct = (xs, p) => {
  if (!xs.length) return null;
  const s = [...xs].sort((a, b) => a - b);
  return s[Math.min(s.length - 1, Math.ceil((p / 100) * s.length) - 1)];
};
const secs = (ms) => (ms == null ? "—" : `${(ms / 1000).toFixed(ms < 10_000 ? 2 : 1)}s`);

function record(kind, data) {
  appendFileSync(OUT, JSON.stringify({ at: new Date().toISOString(), kind, ...data }) + "\n", { mode: 0o600 });
}

function pcReviewCount() {
  return Number(sql("sellerpc_qa", "select count(*) from reviews"));
}

/** Reviews the PC has classified (the rule analysis every new review gets before the screens can sort it). */
function pcAnalysedCount() {
  return Number(sql("sellerpc_qa", "select count(*) from item_analyses where source_type = 'REVIEW'"));
}

/** An /api path with its ids folded, so the same call on two reviews counts as one call. */
function endpoint(url) {
  const u = new URL(url);
  return u.pathname
    .replace(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/g, ":id")
    .replace(/review%3A[^/]+|review:[^/]+/g, ":ref")
    .replace(/\/\d+(?=\/|$)/g, "/:n");
}

/**
 * Loads `path` and waits until no /api call has been in flight for `quietMs`. Returns the time to that point and every
 * /api call made on the way, with its own duration. Polling calls that start after the page settled are not counted.
 */
async function settle(page, path, quietMs = 800) {
  const calls = [];
  const open = new Map();
  let last = Date.now();
  const onRequest = (req) => {
    if (!req.url().includes("/api/")) return;
    open.set(req, Date.now());
    last = Date.now();
  };
  const onDone = (req) => {
    if (!open.has(req)) return;
    const started = open.get(req);
    open.delete(req);
    last = Date.now();
    calls.push({ endpoint: endpoint(req.url()), method: req.method(), ms: Date.now() - started,
      status: req.failure() ? "failed" : null });
  };
  page.on("request", onRequest);
  page.on("requestfinished", onDone);
  page.on("requestfailed", onDone);
  const t0 = Date.now();
  await page.goto(`${SCREEN}${path}`, { waitUntil: "domcontentloaded" });
  while (!(open.size === 0 && Date.now() - last >= quietMs)) {
    if (Date.now() - t0 > 120_000) throw new Error(`${path} did not settle in 120 s`);
    await new Promise((r) => setTimeout(r, 50));
  }
  page.off("request", onRequest);
  page.off("requestfinished", onDone);
  page.off("requestfailed", onDone);
  return { ms: last - t0, calls };
}

/* ── 1. screens ─────────────────────────────────────────────────────────────────────────────────────── */

const SCREENS = [
  ["홈", "/"],
  ["리뷰 목록", "/reviews"],
];

async function screens(browser, runs = 5) {
  const reviews = pcReviewCount();
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await ctx.newPage();
  await pcSignIn(page);
  const result = [];
  for (const [name, path] of SCREENS) {
    await settle(page, path); // warm: the first visit compiles the dev server's modules
    const times = [];
    const byEndpoint = new Map();
    for (let i = 0; i < runs; i++) {
      const { ms, calls } = await settle(page, path);
      times.push(ms);
      for (const c of calls) {
        const key = `${c.method} ${c.endpoint}`;
        byEndpoint.set(key, [...(byEndpoint.get(key) ?? []), c.ms]);
      }
    }
    const slowest = [...byEndpoint.entries()]
      .map(([key, ms]) => ({ call: key, median: median(ms), max: Math.max(...ms), perVisit: ms.length / runs }))
      .sort((a, b) => b.median - a.median);
    result.push({ name, path, median: median(times), max: Math.max(...times), calls: slowest.length, slowest });
  }
  await ctx.close();
  record("screens", { reviews, runs, result });
  say(`화면 — PC에 리뷰 ${reviews.toLocaleString()}건, 화면마다 ${runs}번`);
  for (const r of result) {
    say(`  ${r.name}: 다 뜨기까지 중간 ${secs(r.median)} · 가장 느림 ${secs(r.max)} · 서버 호출 ${r.calls}종`);
    for (const c of r.slowest.slice(0, 4)) {
      say(`      ${c.call}  중간 ${secs(c.median)} · 최대 ${secs(c.max)}${c.perVisit > 1 ? ` · 한 번 열 때 ${c.perVisit}회` : ""}`);
    }
  }
}

/* ── 2. upload ──────────────────────────────────────────────────────────────────────────────────────── */

/** `n` new rows over the last year: ratings and texts like the seed's, older ones mostly answered. */
function perfRows(n, firstNumber, seed) {
  const r = rng(seed);
  const to = Date.now() - 3600_000;
  const from = to - 365 * 86400_000;
  const pick = (a) => a[Math.floor(r() * a.length)];
  const rows = [];
  for (let i = 0; i < n; i++) {
    const at = from + r() * (to - from);
    const roll = r();
    const [rating, body] = roll < 0.75 ? [5, pick(GOOD)] : roll < 0.88 ? [4, pick(GOOD)] : roll < 0.95 ? [3, pick(OK)]
      : roll < 0.995 ? [roll < 0.97 ? 2 : 1, pick(BAD)] : [2, pick(SAFETY)];
    const replied = at < to - 14 * 86400_000 && r() < 0.7;
    const [no, name] = PRODUCTS[i % PRODUCTS.length];
    const cells = Object.fromEntries(HEADERS.map((h) => [h, ""]));
    Object.assign(cells, {
      상품번호: no, 상품명: name, 리뷰구분: i % 7 === 0 ? "한달사용" : "일반", 구매자평점: String(rating),
      리뷰상세내용: body, 리뷰도움수: "0", 등록자: "qa****", 리뷰등록일: kst(at), 리뷰글번호: String(firstNumber + i),
      전시상태: "노출", 답글여부: replied ? "Y" : "N", 답글등록일시: replied ? kst(at + 86400_000) : "",
      상품주문번호: String(2026000000000 + firstNumber - 7200000000 + i),
    });
    rows.push({ at, cells: HEADERS.map((h) => cells[h]) });
  }
  return rows.sort((a, b) => a.at - b.at).map((x) => x.cells);
}

async function upload(browser, s, n) {
  const first = Number(s.PERF_NEXT_NUMBER ?? 7200000000);
  const rows = perfRows(n, first, first);
  const file = join(RUN, `perf-upload-${n}.xlsx`);
  const bytes = xlsx([HEADERS, ...rows]);
  writeFileSync(file, bytes, { mode: 0o600 });
  s.PERF_NEXT_NUMBER = String(first + n);
  saveState(s);

  const vendorBefore = Number(sql("vendor_qa", `select count(*) from store_reviews where store_id='${s.STORE_ID}'`));
  const pcBefore = pcReviewCount();
  const analysedBefore = pcAnalysedCount();
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await ctx.newPage();
  await deskSignIn(page, s);
  const t0 = Date.now();
  const deskMs = await deskUpload(page, s, file, 600_000);
  await ctx.close();
  const vendorAfter = Number(sql("vendor_qa", `select count(*) from store_reviews where store_id='${s.STORE_ID}'`));
  const want = pcBefore + (vendorAfter - vendorBefore);

  // The PC's side: when the first new review lands, and when the last one does.
  let firstMs = null;
  let allMs = null;
  let analysedMs = null;
  const curve = [];
  const added = vendorAfter - vendorBefore;
  while (analysedMs == null) {
    const now = Date.now() - t0;
    if (now > 900_000) throw new Error(`the PC did not take every review in 15 min (holds ${pcReviewCount()}/${want})`);
    const have = pcReviewCount();
    const analysed = pcAnalysedCount() - analysedBefore;
    if (have > pcBefore && firstMs == null) firstMs = now;
    if (!curve.length || curve[curve.length - 1].have !== have - pcBefore || curve[curve.length - 1].analysed !== analysed) {
      curve.push({ ms: now, have: have - pcBefore, analysed });
    }
    if (have >= want && allMs == null) allMs = now;
    if (allMs != null && analysed >= added) analysedMs = now;
    else await new Promise((r) => setTimeout(r, 250));
  }
  const data = { rows: n, fileBytes: bytes.length, deskMs, vendorAdded: added, firstMs, allMs, analysedMs,
    pcTotal: pcReviewCount(), curve };
  record("upload", data);
  say(`업로드 ${n.toLocaleString()}건 (파일 ${(bytes.length / 1024).toFixed(0)}KB)`);
  say(`  담당자 화면이 결과를 보여 주기까지 ${secs(deskMs)} · 서버에 새로 저장 ${data.vendorAdded.toLocaleString()}건`);
  say(`  업로드 버튼부터 — PC에 첫 리뷰 ${secs(firstMs)} · 전부 저장 ${secs(allMs)} · 분류까지 끝 ${secs(analysedMs)}` +
    ` · PC 전체 ${data.pcTotal.toLocaleString()}건`);
  const steps = curve.filter((c, i) => i === 0 || i === curve.length - 1 || c.have - curve[i - 1].have >= 1000
    || c.analysed - curve[i - 1].analysed >= 1000);
  say(`  흐름(저장/분류): ${steps.map((c) => `${secs(c.ms)} ${c.have.toLocaleString()}/${c.analysed.toLocaleString()}`).join(" · ")}`);
}

/* ── 4. live ────────────────────────────────────────────────────────────────────────────────────────── */

/** The review list and Home stay open on the PC; a delegate uploads; each screen must read again by itself. */
async function live(browser, s, n) {
  const pcCtx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const list = await pcCtx.newPage();
  await pcSignIn(list);
  // Not "networkidle": an open screen always holds one question open with the PC now, so the network is never idle.
  const settled = (page, path) => page.waitForResponse((r) => r.url().includes(path) && r.status() === 200);
  const home = await pcCtx.newPage();
  await Promise.all([settled(home, "/api/operations/review-work"), home.goto(`${SCREEN}/`)]);
  let loads = 0;
  for (const page of [list, home]) page.on("load", () => loads++);

  let t0 = null;
  let listTotal = null;
  let listMs = null;
  let homeMs = null;
  list.on("response", async (res) => {
    if (!res.url().includes("/api/reviews/record") || res.status() !== 200) return;
    const total = (await res.json().catch(() => null))?.total ?? null;
    if (process.env.PERF_DEBUG) say(`  list read ${t0 == null ? "before" : secs(Date.now() - t0)} total=${total}`);
    if (t0 == null) listTotal = total;
    else if (listMs == null && total != null && listTotal != null && total > listTotal) listMs = Date.now() - t0;
  });
  home.on("response", (res) => {
    if (t0 != null && homeMs == null && res.url().includes("/api/operations/review-work") && res.status() === 200) {
      homeMs = Date.now() - t0;
    }
  });
  await Promise.all([settled(list, "/api/reviews/record"), list.goto(`${SCREEN}/reviews`)]);
  loads = 0;

  const first = Number(s.PERF_NEXT_NUMBER ?? 7200000000);
  const file = join(RUN, `perf-live-${n}.xlsx`);
  writeFileSync(file, xlsx([HEADERS, ...perfRows(n, first, first)]), { mode: 0o600 });
  s.PERF_NEXT_NUMBER = String(first + n);
  saveState(s);
  const deskCtx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const desk = await deskCtx.newPage();
  await deskSignIn(desk, s);
  if (process.env.PERF_DEBUG) {
    say(`  visible: list=${await list.evaluate(() => document.visibilityState)} home=${await home.evaluate(() => document.visibilityState)}`);
  }
  t0 = Date.now();
  await deskUpload(desk, s, file, 600_000);
  await until("the open screens to read again by themselves", () => listMs != null && homeMs != null, 120);
  const data = { rows: n, listMs, homeMs, reloads: loads };
  record("live", data);
  say(`업로드 ${n.toLocaleString()}건 — 열려 있던 리뷰 목록이 새 리뷰를 보이기까지 ${secs(listMs)} · 홈이 다시 읽기까지 ` +
    `${secs(homeMs)} · 새로고침 ${loads}번`);
  await deskCtx.close();
  await pcCtx.close();
}

/* ── 3. drafts ──────────────────────────────────────────────────────────────────────────────────────── */

/** Unanswered, undecided, undrafted reviews, spread over ratings: the low ones first, then 3★, then 4–5★. */
function draftTargets(k) {
  const pick = (where, limit) => sql("sellerpc_qa",
    `select r.id || '|' || r.rating || '|' || replace(left(r.body, 60), '|', ' ') from reviews r ` +
    `where r.reply_state = 'PENDING' and ${where} ` +
    `and not exists (select 1 from review_triage t where t.review_id = r.id) ` +
    `and not exists (select 1 from review_reply_draft d where d.review_id = r.id) ` +
    `order by r.received_at desc limit ${limit}`).split("\n").filter(Boolean);
  const low = Math.max(1, Math.round(k * 0.3));
  const mid = Math.max(1, Math.round(k * 0.2));
  const rows = [...pick("r.rating <= 2", low), ...pick("r.rating = 3", mid)];
  rows.push(...pick("r.rating >= 4", k - rows.length));
  return rows.map((line) => {
    const [id, rating, ...body] = line.split("|");
    return { id, rating: Number(rating), body: body.join("|") };
  });
}

async function drafts(browser, k) {
  const targets = draftTargets(k);
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await ctx.newPage();
  await pcSignIn(page);
  const results = [];
  for (const t of targets) {
    await page.goto(`${SCREEN}/reviews/reply/${t.id}`, { waitUntil: "domcontentloaded" });
    const starter = page.getByTestId("reply-starter");
    if (!(await starter.waitFor({ timeout: 30_000 }).then(() => true, () => false))) {
      results.push({ ...t, outcome: "no-starter" });
      continue;
    }
    let api = null;
    const onResponse = async (res) => {
      if (!res.url().includes("/reply/draft/generate")) return;
      const timing = res.request().timing();
      let body = null;
      try { body = await res.json(); } catch { /* an error page */ }
      api = { status: res.status(), ms: timing.responseEnd > 0 ? timing.responseEnd : null, body };
    };
    page.on("response", onResponse);
    const t0 = Date.now();
    await starter.getByRole("button", { name: "AI 초안 준비" }).click();
    const shown = await Promise.race([
      page.getByTestId("grounded-review-basis").waitFor({ timeout: 120_000 }).then(() => "draft"),
      page.locator("[data-testid=grounded-review-draft] [role=alert], [data-testid=reply-starter] [role=alert]")
        .first().waitFor({ timeout: 120_000 }).then(() => "error"),
    ]).catch(() => "timeout");
    const screenMs = Date.now() - t0;
    await until("the draft response", () => api, 10).catch(() => {});
    page.off("response", onResponse);
    const draft = api?.body?.draft;
    results.push({
      ...t, outcome: shown, screenMs, apiStatus: api?.status ?? null,
      basis: draft?.answerBasis ?? api?.body?.answerBasis ?? null, model: draft?.modelVersion ?? null,
      author: api?.body?.authorKind ?? draft?.authorKind ?? null, note: api?.body?.unavailableMessage ?? null,
      reply: draft?.body ?? null,
    });
  }
  await ctx.close();
  record("drafts", { results });

  const ok = results.filter((r) => r.outcome === "draft");
  const times = ok.map((r) => r.screenMs);
  const byBasis = Object.entries(ok.reduce((m, r) => ({ ...m, [r.basis ?? "?"]: (m[r.basis ?? "?"] ?? 0) + 1 }), {}));
  const byAuthor = Object.entries(ok.reduce((m, r) => ({ ...m, [r.author ?? "?"]: (m[r.author ?? "?"] ?? 0) + 1 }), {}));
  say(`AI 초안 ${results.length}건 — 성공 ${ok.length} · 실패 ${results.filter((r) => r.outcome === "error").length}` +
    ` · 시간 초과 ${results.filter((r) => r.outcome === "timeout").length}` +
    ` · 시작 버튼 없음 ${results.filter((r) => r.outcome === "no-starter").length}`);
  say(`  누르고 초안이 보이기까지 중간 ${secs(median(times))} · 90% ${secs(pct(times, 90))} · 최대 ${secs(times.length ? Math.max(...times) : null)}`);
  say(`  근거: ${byBasis.map(([b, n]) => `${b} ${n}`).join(" · ")}  ·  작성: ${byAuthor.map(([a, n]) => `${a} ${n}`).join(" · ")}`);
  for (const r of results) {
    say(`  ★${r.rating} ${secs(r.screenMs)} ${r.outcome}${r.basis ? ` ${r.basis}` : ""} | ${r.body}`);
    if (r.reply) say(`      → ${r.reply.replace(/\s+/g, " ")}`);
  }
}

/* ── main ──────────────────────────────────────────────────────────────────────────────────────────── */

const [what, arg] = process.argv.slice(2);
const s = loadState();
if (!s.STORE_ID || !s.DELEGATE_TOTP) throw new Error("the QA stack is not seeded: node tools/dev/seller-pc-qa-seed.mjs");
const browser = await chromium.launch();
try {
  if (what === "screens") await screens(browser, Number(arg ?? 5));
  else if (what === "upload") await upload(browser, s, Number(arg ?? 3000));
  else if (what === "drafts") await drafts(browser, Number(arg ?? 20));
  else if (what === "live") await live(browser, s, Number(arg ?? 300));
  else throw new Error("screens [runs] | upload <rows> | drafts <count> | live <rows>");
} finally {
  await browser.close();
}
