#!/usr/bin/env node
// Fills the QA copy of the seller-PC stack (`SELLER_PC_DEMO=qa tools/dev/seller-pc-demo.sh up`) with a complete,
// made-up version of the kept demo — a seller with one store, this PC linked and receiving, an admin delegate
// assigned and checked, and ~300 synthetic NAVER reviews uploaded through the delegate desk — so screens can be
// tried and captured without real review data.
//
// Everything goes through the real pages and APIs the way a person would use them (seller join page, the PC screen,
// the vendor server's PC-approval page, the delegate join page and desk), plus the operator's `ops` commands. The
// PC-approval page's e-mail code is read from the QA vendor log (dev-outbox mail).
//
//   node tools/dev/seller-pc-qa-seed.mjs
//
// Re-runnable: each step first checks whether it is already done (by counts in vendor_qa / sellerpc_qa) and skips it.
// Start again from nothing:
//
//   SELLER_PC_DEMO=qa tools/dev/seller-pc-demo.sh down
//   psql -h 127.0.0.1 -U sellerops -d postgres -c 'drop database vendor_qa' -c 'drop database sellerpc_qa'
//   SELLER_PC_DEMO=qa tools/dev/seller-pc-demo.sh up && node tools/dev/seller-pc-qa-seed.mjs
//
// Generated passwords, the delegate's authenticator secret and the store id are kept in
// tools/dev/.run/seller-pc-qa/qa.env (mode 600). Nothing secret is printed — invite codes, link codes, e-mail codes
// and tokens stay inside this process.
import { createRequire } from "node:module";
import { createHmac, randomBytes } from "node:crypto";
import { execFileSync } from "node:child_process";
import { chmodSync, existsSync, readFileSync, statSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { crc32, deflateRawSync } from "node:zlib";

export const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..", "..");
export const RUN = join(ROOT, "tools", "dev", ".run", "seller-pc-qa");
const STATE = join(RUN, "qa.env");
export const VENDOR = "http://127.0.0.1:18499";
export const SCREEN = "http://127.0.0.1:5691";
const SELLER = "qa-seller@example.com";
export const DELEGATE = "qa-delegate@example.com";
export const STORE_LABEL = "나누리산업(QA)";
const PSQL = process.env.PSQL ?? "/opt/homebrew/opt/postgresql@15/bin/psql";
const { chromium } = createRequire(join(ROOT, "collector", "package.json"))("playwright");

const say = (line) => console.log(`· ${line}`);

/* ── state ─────────────────────────────────────────────────────────────────────────────────────────── */

export function loadState() {
  const s = {};
  if (existsSync(STATE)) {
    for (const line of readFileSync(STATE, "utf8").split("\n")) {
      const i = line.indexOf("=");
      if (i > 0) s[line.slice(0, i)] = line.slice(i + 1);
    }
  }
  return s;
}

export function saveState(s) {
  writeFileSync(STATE, Object.entries(s).map(([k, v]) => `${k}=${v}`).join("\n") + "\n", { mode: 0o600 });
  chmodSync(STATE, 0o600);
}

/* ── helpers ───────────────────────────────────────────────────────────────────────────────────────── */

export function sql(db, query) {
  return execFileSync(PSQL, ["-h", "127.0.0.1", "-p", "5432", "-U", "sellerops", "-tA", "-d", db, "-c", query], {
    encoding: "utf8",
  }).trim();
}

/** An operator command against vendor_qa. The output can hold an invite code: it is returned, never printed. */
function ops(...args) {
  return execFileSync(join(ROOT, "tools", "dev", "seller-pc-demo.sh"), ["ops", ...args], {
    encoding: "utf8",
    env: { ...process.env, SELLER_PC_DEMO: "qa" },
  });
}

function joinLink(output, path) {
  const m = output.match(new RegExp(`https?://\\S+${path.replace("/", "\\/")}#([A-Za-z0-9_-]+)`));
  if (!m) throw new Error(`no ${path} link in the ops output`);
  return `${VENDOR}${path}#${m[1]}`;
}

function password() {
  return randomBytes(18).toString("base64url");
}

const BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

export function totp(secret, at = Date.now()) {
  const clean = secret.replace(/[\s=]/g, "").toUpperCase();
  let bits = "";
  for (const ch of clean) bits += BASE32.indexOf(ch).toString(2).padStart(5, "0");
  const key = Buffer.from(bits.match(/.{8}/g).map((b) => parseInt(b, 2)));
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(Math.floor(at / 1000 / 30)));
  const h = createHmac("sha1", key).update(counter).digest();
  const o = h[h.length - 1] & 0x0f;
  const n = ((h[o] & 0x7f) << 24) | (h[o + 1] << 16) | (h[o + 2] << 8) | h[o + 3];
  return String(n % 1_000_000).padStart(6, "0");
}

/** The demo account's password, read from the seed source — the PC screen's own fixture login. */
export function seedPassword() {
  const src = readFileSync(join(ROOT, "backend/src/main/java/com/sellerops/config/MockDataSeeder.java"), "utf8");
  const m = src.match(/setEmail\("demo@sellerops\.ai"\);\s*user\.setPasswordHash\(passwordEncoder\.encode\("([^"]+)"\)\)/);
  if (!m) throw new Error("could not find the seed account in MockDataSeeder");
  return m[1];
}

export async function until(what, fn, seconds = 120) {
  for (let i = 0; i < seconds; i++) {
    const v = await fn();
    if (v) return v;
    await new Promise((r) => setTimeout(r, 1000));
  }
  throw new Error(`timed out waiting for ${what}`);
}

/* ── the synthetic export ──────────────────────────────────────────────────────────────────────────── */

export const HEADERS = ["상품번호", "상품명", "리뷰구분", "구매자평점", "포토/영상", "리뷰상세내용", "리뷰도움수", "등록자",
  "리뷰등록일", "최종수정일", "리뷰글번호", "관련리뷰글번호", "관련리뷰상세내용", "전시상태", "답글여부", "답글등록일시",
  "베스트리뷰", "베스트리뷰선정일시", "이벤트번호", "혜택지급", "혜택지급일시", "유저정보 등록 항목", "상품주문번호",
  "풀필먼트사", "리뷰이동일"];

export const PRODUCTS = [
  ["8800000101", "스텐 종이컵 디스펜서 벽걸이형"],
  ["8800000102", "분리수거함 3단 슬림 (QA)"],
  ["8800000103", "주방 수납 선반 2단"],
  ["8800000104", "원목 컵홀더 6구"],
  ["8800000105", "욕실 코너 선반 무타공"],
];
export const GOOD = ["배송 빠르고 튼튼해요. 잘 쓰겠습니다.", "생각보다 크고 마감이 깔끔해요.", "설치가 쉬웠어요. 만족합니다.",
  "가격대비 좋아요.", "두 번째 구매입니다. 역시 좋네요.", "색감이 사진이랑 똑같아요.", "포장이 꼼꼼했어요.",
  "자리를 많이 안 차지해서 좋아요.", "부모님 댁에도 하나 보내드렸어요.", "튼튼하고 흔들림이 없어요."];
export const OK = ["쓸만해요. 나사가 하나 헐거웠어요.", "괜찮은데 생각보다 작아요.", "무난합니다. 배송이 조금 늦었어요.",
  "가격대비 그럭저럭이에요.", "설명서가 조금 불친절해요."];
export const BAD = ["한 달 만에 고정 부분이 부러졌어요.", "벽에서 자꾸 떨어져요. 접착력이 약해요.", "흠집 난 제품이 왔어요. 교환 원합니다.",
  "사진이랑 색이 너무 달라요.", "냄새가 심해서 못 쓰겠어요.", "배송 중 파손돼서 왔어요."];
export const SAFETY = ["모서리가 날카로워서 손을 베였어요.", "아이가 만지다 손가락이 끼었어요. 조심하세요.",
  "설치하다가 선반이 떨어져서 발등을 다쳤어요."];

export function rng(seed) {
  let x = seed >>> 0;
  return () => ((x = (Math.imul(x ^ (x >>> 15), 0x2c1b3c6d) + 0x9e3779b9) >>> 0) / 2 ** 32);
}

export const pad = (n) => String(n).padStart(2, "0");
/** Seller Center's own time format, Korean local time. */
export function kst(ms) {
  const d = new Date(ms + 9 * 3600_000);
  return `${d.getUTCFullYear()}.${pad(d.getUTCMonth() + 1)}.${pad(d.getUTCDate())}. ` +
    `${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}:${pad(d.getUTCSeconds())}`;
}

/** ~300 rows over 2025-10-27..2026-10-25 KST: mostly 5★, some 4/3★, 12 low (three in the last week), 3 safety. */
function reviewRows() {
  const r = rng(20261026);
  const from = Date.parse("2025-10-27T00:00:00+09:00");
  const to = Date.parse("2026-10-25T21:00:00+09:00");
  const pick = (a) => a[Math.floor(r() * a.length)];
  const rows = [];
  const add = (rating, body, at) => rows.push({ rating, body, at });
  for (let i = 0; i < 285; i++) {
    const at = from + r() * (to - from);
    const roll = r();
    if (roll < 0.78) add(5, pick(GOOD), at);
    else if (roll < 0.92) add(4, pick(GOOD), at);
    else add(3, pick(OK), at);
  }
  for (let i = 0; i < 9; i++) add(i % 3 === 0 ? 2 : 1, BAD[i % BAD.length], from + r() * (to - 8 * 86400_000 - from));
  for (let i = 0; i < 3; i++) add(i === 1 ? 2 : 1, BAD[(i + 2) % BAD.length], to - (1 + i * 2) * 86400_000 - r() * 3600_000);
  add(5, `튼튼하고 좋은데 ${SAFETY[0]}`, to - 3 * 86400_000);
  add(4, SAFETY[1], from + r() * (to - from));
  add(2, SAFETY[2], to - 40 * 86400_000);
  rows.sort((a, b) => a.at - b.at);
  // ~40 already answered, all older than two weeks.
  const answerable = rows.filter((x) => x.at < to - 14 * 86400_000);
  for (let i = 0; i < 40; i++) answerable[Math.floor((i * answerable.length) / 40)].replied = true;
  return rows.map((x, i) => {
    const [no, name] = PRODUCTS[i % PRODUCTS.length];
    const cells = Object.fromEntries(HEADERS.map((h) => [h, ""]));
    Object.assign(cells, {
      상품번호: no, 상품명: name, 리뷰구분: i % 7 === 0 ? "한달사용" : "일반", 구매자평점: String(x.rating),
      리뷰상세내용: x.body, 리뷰도움수: "0", 등록자: "qa****", 리뷰등록일: kst(x.at), 리뷰글번호: String(7100000000 + i),
      전시상태: "노출", 답글여부: x.replied ? "Y" : "N",
      답글등록일시: x.replied ? kst(x.at + 86400_000) : "", 상품주문번호: String(2025000000000 + i),
    });
    return HEADERS.map((h) => cells[h]);
  });
}

const esc = (s) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");

function colName(i) {
  let s = "";
  for (i += 1; i > 0; i = Math.floor((i - 1) / 26)) s = String.fromCharCode(65 + ((i - 1) % 26)) + s;
  return s;
}

/** A one-sheet .xlsx of inline strings — the smallest workbook Apache POI reads. Deflated, like a real export. */
export function xlsx(rows) {
  const sheetRows = rows.map((cells, r) => `<row r="${r + 1}">` + cells.map((v, c) => v === "" ? "" :
    `<c r="${colName(c)}${r + 1}" t="inlineStr"><is><t xml:space="preserve">${esc(v)}</t></is></c>`).join("") + "</row>");
  const files = {
    "[Content_Types].xml": `<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>`,
    "_rels/.rels": `<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>`,
    "xl/workbook.xml": `<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="리뷰" sheetId="1" r:id="rId1"/></sheets></workbook>`,
    "xl/_rels/workbook.xml.rels": `<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>`,
    "xl/worksheets/sheet1.xml": `<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>${sheetRows.join("")}</sheetData></worksheet>`,
  };
  const local = [];
  const central = [];
  let offset = 0;
  for (const [name, text] of Object.entries(files)) {
    const raw = Buffer.from(text, "utf8");
    const data = deflateRawSync(raw);
    const fname = Buffer.from(name, "utf8");
    const crc = crc32(raw);
    const head = Buffer.alloc(30);
    head.writeUInt32LE(0x04034b50, 0); head.writeUInt16LE(20, 4); head.writeUInt16LE(0x0800, 6);
    head.writeUInt16LE(8, 8); head.writeUInt32LE(0, 10); head.writeUInt32LE(crc, 14);
    head.writeUInt32LE(data.length, 18); head.writeUInt32LE(raw.length, 22); head.writeUInt16LE(fname.length, 26);
    head.writeUInt16LE(0, 28);
    local.push(head, fname, data);
    const cen = Buffer.alloc(46);
    cen.writeUInt32LE(0x02014b50, 0); cen.writeUInt16LE(20, 4); cen.writeUInt16LE(20, 6); cen.writeUInt16LE(0x0800, 8);
    cen.writeUInt16LE(8, 10); cen.writeUInt32LE(0, 12); cen.writeUInt32LE(crc, 16); cen.writeUInt32LE(data.length, 20);
    cen.writeUInt32LE(raw.length, 24); cen.writeUInt16LE(fname.length, 28); cen.writeUInt32LE(offset, 42);
    central.push(cen, fname);
    offset += head.length + fname.length + data.length;
  }
  const cd = Buffer.concat(central);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0); end.writeUInt16LE(central.length / 2, 8); end.writeUInt16LE(central.length / 2, 10);
  end.writeUInt32LE(cd.length, 12); end.writeUInt32LE(offset, 16);
  return Buffer.concat([...local, cd, end]);
}

/* ── steps ─────────────────────────────────────────────────────────────────────────────────────────── */

async function seller(browser, s) {
  if (sql("vendor_qa", `select count(*) from seller_accounts where email='${SELLER}'`) === "1" && s.SELLER_PASSWORD) {
    say("seller: already joined");
    return;
  }
  const link = joinLink(ops("invite", SELLER), "/join");
  s.SELLER_PASSWORD = password();
  saveState(s);
  const ctx = await browser.newContext();
  const page = await ctx.newPage();
  await page.goto(link);
  await page.locator("#view-password").waitFor();
  await page.fill("#password", s.SELLER_PASSWORD);
  await page.fill("#password2", s.SELLER_PASSWORD);
  await page.click("#join");
  await page.locator("#view-done").waitFor();
  await ctx.close();
  say("seller: joined through the join page");
}

function store(s) {
  const existing = sql("vendor_qa", `select id from stores where label='${STORE_LABEL}'`);
  if (existing) {
    s.STORE_ID = existing;
    saveState(s);
    say("store: already there");
    return;
  }
  const m = ops("add-store", SELLER, STORE_LABEL).match(/store id: ([0-9a-f-]{36})/);
  if (!m) throw new Error("add-store did not answer a store id");
  s.STORE_ID = m[1];
  saveState(s);
  say("store: added");
}

export async function pcSignIn(page) {
  await page.goto(`${SCREEN}/login`);
  await page.fill("#login-email", "demo@sellerops.ai");
  await page.fill("#login-password", seedPassword());
  await page.click("form[aria-label='로그인'] button[type=submit]");
  await page.waitForURL((u) => !u.pathname.startsWith("/login"), { timeout: 30_000 });
}

async function pcLink(browser, s) {
  if (sql("sellerpc_qa", "select count(*) from vendor_store_binding") !== "0") {
    say("PC: already linked and receiving");
    return;
  }
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await ctx.newPage();
  await pcSignIn(page);
  await page.goto(`${SCREEN}/connect`);
  const section = page.getByTestId("vendor-reviews");
  await section.waitFor({ timeout: 30_000 });

  const linkButton = page.getByTestId("vendor-reviews-link");
  if (await linkButton.isVisible().catch(() => false)) {
    const popup = ctx.waitForEvent("page");
    await linkButton.click();
    const approve = await popup;
    await approve.waitForLoadState();
    await approve.locator("#view-login").waitFor();
    await approve.fill("#email", SELLER);
    await approve.fill("#password", s.SELLER_PASSWORD);
    await approve.click("#login-form button[type=submit]");
    await approve.locator("#view-confirm").waitFor();
    if (await approve.locator("#retype").isVisible()) {
      // The code must be typed from the PC's own screen.
      const shown = await page.getByTestId("vendor-reviews-pending").innerText();
      const code = shown.match(/([A-Z0-9]{4})-([A-Z0-9]{4})/);
      if (!code) throw new Error("the PC screen did not show its link code");
      await approve.fill("#retype-input", code[1] + code[2]);
    }
    const log = join(RUN, "vendor.log");
    const before = statSync(log).size;
    await approve.click("#allow");
    await approve.locator("#view-stepup").waitFor();
    const mailed = await until("the e-mail code in the QA vendor log", () => {
      const tail = readFileSync(log).subarray(before).toString("utf8");
      const all = [...tail.matchAll(/PC 연결 확인 코드 (\d{6})/g)];
      return all.length ? all[all.length - 1][1] : null;
    }, 30);
    await approve.fill("#stepup-code", mailed);
    await approve.click("#stepup-submit");
    await approve.locator("#view-done").waitFor();
    await approve.close();
    say("PC: allowed on the vendor server's approval page (e-mail code from the dev outbox)");
  }
  const start = page.getByTestId("vendor-reviews-start");
  // The screen polls the server while the link is pending and shows 「리뷰 받기 시작」 once it is linked; wait on it
  // like a person would, and look again with a fresh visit if it has not come.
  await until("「리뷰 받기 시작」 on the PC screen", async () => {
    if (await start.waitFor({ timeout: 45_000 }).then(() => true, () => false)) return true;
    await page.goto(`${SCREEN}/connect`);
    return false;
  }, 4);
  await start.click();
  await until("the store binding", () => sql("sellerpc_qa", "select count(*) from vendor_store_binding") !== "0", 30);
  await ctx.close();
  say("PC: pressed 「리뷰 받기 시작」");
}

async function delegate(browser, s) {
  const active = sql("vendor_qa", `select count(*) from delegates where email='${DELEGATE}'`) === "1";
  if (!active || !s.DELEGATE_TOTP) {
    const link = joinLink(ops("invite-delegate", DELEGATE, "admin"), "/delegate/join");
    s.DELEGATE_PASSWORD = password();
    saveState(s);
    const ctx = await browser.newContext();
    const page = await ctx.newPage();
    await page.goto(link);
    await page.locator("#view-password").waitFor();
    await page.fill("#password", s.DELEGATE_PASSWORD);
    await page.fill("#password2", s.DELEGATE_PASSWORD);
    await page.click("#password-next");
    await page.locator("#view-totp").waitFor();
    s.DELEGATE_TOTP = (await page.locator("#secret").innerText()).replace(/\s/g, "");
    saveState(s);
    await page.fill("#otp", totp(s.DELEGATE_TOTP));
    await page.click("#totp-confirm");
    await page.locator("#view-codes").waitFor();
    await ctx.close();
    say("delegate: joined with a password and an authenticator");
  } else {
    say("delegate: already joined");
  }
  const assigned = sql("vendor_qa", `select count(*) from store_assignments a join delegates d on d.id=a.delegate_id ` +
    `where d.email='${DELEGATE}' and a.store_id='${s.STORE_ID}' and a.ended_at is null`);
  if (assigned === "0") ops("assign", DELEGATE, s.STORE_ID);
  if (sql("vendor_qa", `select owner_checked_at is null from stores where id='${s.STORE_ID}'`) === "t") {
    ops("check-owner", s.STORE_ID, "seller-center-screen", DELEGATE);
  }
  const checklist = ops("checklist");
  if (!/\[x\] 담당자 배정 qa-delegate@example\.com/.test(checklist)) {
    ops("check-assignment", DELEGATE, s.STORE_ID, DELEGATE);
  }
  if (!/\[x\] 담당자 배정 qa-delegate@example\.com/.test(ops("checklist"))) {
    throw new Error("the assignment is still unchecked");
  }
  say("delegate: store assigned, owner and assignment checked");
}

/** Signs the QA delegate in on the desk, or finds them signed in. */
export async function deskSignIn(page, s) {
  await page.goto(`${VENDOR}/delegate/`);
  await page.locator("#view-login, #view-list").first().waitFor();
  if (await page.locator("#view-login").isVisible()) {
    // A fresh code: the one the join page spent may be the current step, which the server will not take twice.
    const now = Date.now();
    await new Promise((r) => setTimeout(r, 30_000 - (now % 30_000) + 500));
    await page.fill("#email", DELEGATE);
    await page.fill("#password", s.DELEGATE_PASSWORD);
    await page.fill("#second", totp(s.DELEGATE_TOTP));
    await page.click("#login-form button[type=submit]");
  }
  await page.locator("#view-list").waitFor();
}

/**
 * Uploads `file` for the QA store from the desk's 「리뷰 파일 올리기」 and waits for the desk's answer; returns the
 * milliseconds from pressing 올리기 to that answer. The desk opens the panel itself when one store is assigned.
 */
export async function deskUpload(page, s, file, timeout = 60_000) {
  const panel = page.locator("#upload-panel");
  if (!(await panel.isVisible())) await page.locator(`#store-list button[data-store="${s.STORE_ID}"]`).click();
  await page.locator("#upload-form").waitFor();
  await page.setInputFiles("#file", file);
  const t0 = Date.now();
  await page.click("#upload");
  await page.waitForSelector("#upload-result:not([hidden]), #upload-refused:not([hidden])", { timeout });
  const ms = Date.now() - t0;
  if (await page.locator("#upload-refused").isVisible()) {
    throw new Error(`the desk refused the file: ${(await page.locator("#refused-text").innerText()).slice(0, 200)}`);
  }
  return ms;
}

async function upload(browser, s) {
  if (sql("vendor_qa", `select count(*) from store_reviews where store_id='${s.STORE_ID}'`) !== "0") {
    say("upload: reviews already on the server");
    return;
  }
  const rows = reviewRows();
  const file = join(RUN, "qa-naver-reviews.xlsx");
  writeFileSync(file, xlsx([HEADERS, ...rows]), { mode: 0o600 });
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await ctx.newPage();
  await deskSignIn(page, s);
  await deskUpload(page, s, file);
  await ctx.close();
  say(`upload: ${rows.length} synthetic reviews uploaded on the delegate desk`);
}

async function relay(s) {
  const want = Number(sql("vendor_qa", `select count(*) from store_reviews where store_id='${s.STORE_ID}'`));
  const got = await until("the PC to receive the uploaded reviews", () => {
    const n = Number(sql("sellerpc_qa", "select coalesce(max(acked_cursor),0) from vendor_store_binding"));
    return n >= want ? n : null;
  }, 180);
  say(`relay: the PC holds ${got} of ${want}`);
}

/* ── main ──────────────────────────────────────────────────────────────────────────────────────────── */

// Run only when started as a script: seller-pc-qa-perf.mjs imports the helpers above.
if (resolve(process.argv[1] ?? "") === fileURLToPath(import.meta.url)) {
  const s = loadState();
  const browser = await chromium.launch();
  try {
    await seller(browser, s);
    store(s);
    await pcLink(browser, s);
    await delegate(browser, s);
    await upload(browser, s);
    await relay(s);
    const v = sql("vendor_qa", `select count(*) || ' reviews · ' || count(*) filter (where replied) || ' replied · ' || ` +
      `count(*) filter (where rating <= 2) || ' at 1–2★' from store_reviews`);
    const pc = sql("sellerpc_qa", "select count(*) from reviews");
    say(`done — vendor_qa: ${v} · sellerpc_qa: ${pc} reviews`);
    say(`PC screen ${SCREEN} (demo account) · delegate desk ${VENDOR}/delegate/ (${DELEGATE}; secrets in qa.env)`);
  } finally {
    await browser.close();
  }
}
