// Verify a built seller-PC bundle on its own — no Gradle, vite, Homebrew Java, Node or Postgres — with the fake model
// (owner, 2026-10-29: 「묶음과 실행기가 준비되면 개발 도구 없이 그 설치 묶음만으로 가짜 모델 전체 흐름을 검증」).
//
//   env -i PATH=/usr/bin:/bin:/usr/sbin:/sbin HOME="$T/home" \
//     "$BUNDLE/bin/node" tools/pc-bundle/verify-bundle.mjs "$BUNDLE" "$T"
//
// Run with the bundle's own node and a bare PATH, so nothing on this Mac can stand in for what the bundle carries. $T
// is an empty folder: the data folder and a home are made inside it. It goes through, in order:
//
//   1. first run stopped while the database is being made, then again while the backend applies its migrations — and
//      the third start finishes, with the keys of the first and every migration applied once;
//   2. a second launcher is refused while one runs (`run` exits 3; `start` says it is running and starts nothing);
//   3. a seller signs up, registers a file channel and uploads three made-up reviews; the fake model judges them: the
//      complaint gets a draft written ahead by the reply agent, the injury is handed to a person, the praise gets none;
//   4. the seller's press runs the agent; the seller approves the agent's draft;
//   5. the agent runtime is killed: the press says so and no other drafter writes a draft; the launcher starts the
//      runtime again;
//   6. the work screen, served by the backend, opens the agent's draft (when a Playwright browser is available);
//   7. stop leaves nothing listening; start again keeps the keys and the data.
//
// Prints one line per check and nothing secret: the seller's password is made here and stays in this process.
import { spawn, spawnSync } from "node:child_process";
import { createHash, randomBytes } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { createRequire } from "node:module";
import { join, resolve } from "node:path";

const BUNDLE = resolve(process.argv[2] ?? "");
const T = resolve(process.argv[3] ?? "");
if (!existsSync(join(BUNDLE, "reviewnary")) || !T) {
  console.error("usage: verify-bundle.mjs <bundle> <empty work folder>");
  process.exit(2);
}
const DATA = join(T, "data");
const HOME = join(T, "home");
mkdirSync(HOME, { recursive: true });
const ENV = { PATH: "/usr/bin:/bin:/usr/sbin:/sbin", HOME, REVIEWNARY_DATA_DIR: DATA };
const BASE = "http://127.0.0.1:47681";

let failures = 0;
function check(ok, what) {
  console.log(`${ok ? "PASS" : "FAIL"}  ${what}`);
  if (!ok) failures++;
  return ok;
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function until(fn, seconds) {
  for (let i = 0; i < seconds * 2; i++) {
    const v = await fn();
    if (v) return v;
    await sleep(500);
  }
  return null;
}

function cli(...args) {
  const r = spawnSync(join(BUNDLE, "reviewnary"), args, { env: ENV, encoding: "utf8", timeout: 900_000 });
  return { code: r.status, out: (r.stdout ?? "") + (r.stderr ?? "") };
}
function runForeground() {
  return spawn(join(BUNDLE, "bin", "node"), [join(BUNDLE, "app", "launcher.mjs"), "run"], { env: ENV, stdio: "ignore" });
}
const sha = (p) => createHash("sha256").update(readFileSync(p)).digest("hex");

function fileText(p) {
  try {
    return readFileSync(p, "utf8");
  } catch {
    return "";
  }
}

let token = null;
async function api(method, path, body, { form } = {}) {
  const headers = token ? { Authorization: `Bearer ${token}` } : {};
  if (body && !form) headers["Content-Type"] = "application/json";
  const send = () => fetch(BASE + path, { method, headers, body: form ?? (body ? JSON.stringify(body) : undefined) });
  // A kept-alive connection to a backend that was stopped is reset on first use after a restart; send once more.
  const r = await send().catch(async () => {
    await sleep(500);
    return send();
  });
  const text = await r.text();
  let json = null;
  try {
    json = JSON.parse(text);
  } catch {
    // not JSON
  }
  return { status: r.status, json };
}

// ── 1. interrupted first runs ────────────────────────────────────────────────────────────────────────────────────
writeFileSync(join(T, "settings.env"), [
  "SELLEROPS_DEV_FAKE_LLM=true",
  "SELLEROPS_AGENT_REPLY_ENABLED=true", "SELLEROPS_AGENT_REPLY_ORG_IDS=*", "SELLEROPS_AGENT_REPLY_API_KEY=fake",
  "SELLEROPS_AGENT_DRAFT_ENABLED=true", "SELLEROPS_AGENT_DRAFT_ORG_IDS=*", "SELLEROPS_AGENT_DRAFT_API_KEY=fake",
].join("\n"));
mkdirSync(DATA, { recursive: true });
writeFileSync(join(DATA, "settings.env"), readFileSync(join(T, "settings.env")));

let launcher = runForeground();
await until(() => existsSync(join(DATA, "pg.initializing")) || existsSync(join(DATA, "state", "pg-initialized")), 60);
launcher.kill("SIGKILL");
await sleep(3000); // initdb, if it was mid-way, ends on its own
const firstKeys = sha(join(DATA, "keys.env"));
check(!existsSync(join(DATA, "state", "pg-initialized")), "stop 1: the launcher died while the database was being made");

launcher = runForeground();
const migrating = await until(() => /Migrating schema|Successfully applied/.test(fileText(join(DATA, "logs", "backend.log"))), 240);
launcher.kill("SIGKILL");
check(!!migrating, "stop 2: the launcher died while the backend was applying migrations");

const started = cli("start", "--no-open");
check(started.code === 0 && started.out.includes("켜졌어요"), "the third start finishes");
check(sha(join(DATA, "keys.env")) === firstKeys, "the keys are the ones the first, interrupted run made");
check(!existsSync(join(DATA, "pg.initializing")), "the half-made database cluster is gone");
// A failed or half-applied migration stops the backend at boot (Flyway validates before it serves), so a backend that
// answers is one whose migrations all applied; the log must also show none failed.
const backendLog = fileText(join(DATA, "logs", "backend.log"));
check(/Successfully applied \d+ migration/.test(backendLog) && !/Migration .* failed|FlywayValidateException|FlywayException/.test(backendLog),
  "the migrations the stopped boot left were applied, none failed");
check(cli("status").code === 0, "status: database, backend and agent runtime all answer");
const procs = spawnSync("/bin/ps", ["-axo", "command="], { encoding: "utf8" }).stdout.split("\n");
check(procs.some((c) => c.startsWith(join(BUNDLE, "jre", "bin", "java")))
  && procs.some((c) => c.startsWith(join(BUNDLE, "pgsql", "bin", "postgres")))
  && procs.some((c) => c.startsWith(join(BUNDLE, "bin", "node")) && c.includes("agent-runtime.mjs")),
"java, postgres and node run from the bundle's own folders");

// ── 2. one launcher ──────────────────────────────────────────────────────────────────────────────────────────────
const dup = cli("run");
check(dup.code === 3 && dup.out.includes("이미 실행 중"), "a second `run` is refused");
const again = cli("start", "--no-open");
check(again.code === 0 && again.out.includes("이미 실행 중"), "a second `start` says it is running and starts nothing");

// ── 3. the fake model's flow ─────────────────────────────────────────────────────────────────────────────────────
const password = randomBytes(12).toString("base64url");
const signup = await api("POST", "/api/auth/signup", { email: "pc-verify@example.com", password, name: "합성 판매자",
  orgName: "합성 스토어", termsAccepted: true });
token = signup.json?.token;
check(signup.status === 200 && !!token, "a seller signs up on the PC");
const channels = await api("GET", "/api/channels");
const naver = (channels.json ?? []).find((c) => c.code === "NAVER");
const account = await api("POST", "/api/seller-accounts/file-channel", { channelId: naver?.id, alias: "합성 스토어" });
check(account.status === 200 && !!account.json?.id, "a NAVER file channel is registered");

const yesterday = new Date(Date.now() - 86_400_000).toISOString().slice(0, 10);
const rows = [
  ["synthetic-complaint", 2, "합성 리뷰: 부품이 하나 빠져 있었어요"],
  ["synthetic-injury", 5, "합성 리뷰: 디자인은 좋은데 모서리에 손을 베였어요"],
  ["synthetic-praise", 5, "합성 리뷰: 디자인이 예뻐요"],
];
const csv = `review_id,product,rating,date,content\n${rows.map(([id, r, b]) => `${id},합성 선반,${r},${yesterday},${b}`).join("\n")}\n`;
const form = new FormData();
form.append("channelId", naver.id);
form.append("uploadType", "REVIEW");
form.append("file", new Blob([csv], { type: "text/csv" }), "reviews.csv");
const upload = await api("POST", "/api/uploads", null, { form });
check(upload.status === 200, "three made-up reviews are uploaded");

const reply = (id, tail = "") => `/api/seller-accounts/${account.json.id}/attention/items/review:${id}/reply${tail}`;
const view = async (id) => (await api("GET", reply(id))).json;
const outcomes = async () => (await api("GET", "/api/agent/reply/outcomes?days=1")).json;
const ids = await until(async () => {
  const items = (await api("GET", "/api/reviews/record?size=50")).json?.items ?? [];
  const find = (word) => items.find((r) => (r.review?.preview ?? "").includes(word))?.review?.id;
  const found = { complaint: find("부품이"), injury: find("손을 베였"), praise: find("예뻐요") };
  return found.complaint && found.injury && found.praise ? found : null;
}, 60);
check(!!ids, "the three reviews are on the review list");
const { complaint, injury, praise } = ids ?? {};
const drafted = await until(async () => (await view(complaint))?.draft, 180);
check(!!drafted && ((await outcomes())?.byOutcome?.SAVED ?? 0) >= 1, "the complaint: the reply agent wrote a draft ahead");
const handed = await until(async () => ((await api("GET", "/api/agent/reply/handoffs")).json ?? [])
  .some((h) => h.reviewId === injury), 60);
check(!!handed, "the injury: handed to a person");
await sleep(5000);
check(!(await view(injury))?.draft && !(await view(praise))?.draft, "the injury and the praise have no draft");

// ── 4. the seller's press and approval ───────────────────────────────────────────────────────────────────────────
// As on the screen: the seller marks the review 「대응 필요」 first, then presses for a draft.
const marked = await api("POST", `/api/seller-accounts/${account.json.id}/attention/items/review:${praise}/triage`,
  { commandId: randomBytes(8).toString("hex"), disposition: "RESPONSE_NEEDED" });
check(marked.status === 200, "the seller marks the praise 「대응 필요」");
const runsBefore = (await outcomes())?.runs ?? 0;
const press = await api("POST", reply(praise, "/draft/generate"));
check(press.status === 200 && ((await outcomes())?.runs ?? 0) === runsBefore + 1,
  `the seller's press runs the reply agent (one more run) (${press.status}: ${
    press.json?.unavailableMessage ?? press.json?.message ?? "draft"})`);
const approve = await api("POST", reply(complaint, "/approval"), { commandId: randomBytes(8).toString("hex"),
  state: "APPROVED", baseVersion: drafted?.version });
check(approve.status === 200 && (await view(complaint))?.approval?.state === "APPROVED",
  "the seller approves the agent's draft");

// ── 5. the agent runtime is gone ─────────────────────────────────────────────────────────────────────────────────
const praiseVersion = (await view(praise))?.draft?.version ?? null;
const runtimePid = JSON.parse(readFileSync(join(DATA, "run", "status.json"), "utf8")).programs.runtime.pid;
process.kill(runtimePid, "SIGKILL");
await sleep(300);
const missing = await api("POST", reply(praise, "/draft/generate"));
check(missing.status === 200 && !missing.json?.draft && /에이전트/.test(missing.json?.unavailableMessage ?? ""),
  `with the runtime gone the press says the agent could not write (${missing.status}: ${
    missing.json?.unavailableMessage ?? missing.json?.message ?? ""})`);
check(((await view(praise))?.draft?.version ?? null) === praiseVersion,
  "and no other drafter wrote a draft instead (the review's draft version is unchanged)");
check(await until(() => cli("status").code === 0, 60), "the launcher started the agent runtime again");

// ── 6. the work screen ───────────────────────────────────────────────────────────────────────────────────────────
let chromium = null;
try {
  chromium = createRequire(join(resolve(BUNDLE, "..", ".."), "collector", "package.json"))("playwright").chromium;
} catch {
  // no browser on this machine: the screen check is skipped, and said so
}
if (chromium) {
  const browser = await chromium.launch();
  const page = await browser.newPage();
  await page.goto(`${BASE}/login`);
  await page.fill("#login-email", "pc-verify@example.com");
  await page.fill("#login-password", password);
  await page.click("form[aria-label='로그인'] button[type=submit]");
  await page.waitForURL((u) => !u.pathname.startsWith("/login"), { timeout: 30_000 });
  await page.goto(`${BASE}/reviews/reply/${complaint}`);
  const body = drafted?.body ?? "";
  const shown = await until(async () => (await page.content()).includes(body.slice(0, 12)), 20);
  check(!!shown && body.length > 0, "the work screen, served by the PC itself, shows the agent's draft");
  await browser.close();
} else {
  console.log("SKIP  the work screen (no Playwright browser on this machine)");
}

// ── 7. stop and start again ──────────────────────────────────────────────────────────────────────────────────────
const total = async () => (await api("GET", "/api/reviews/record?size=1")).json?.total;
const reviewsBefore = await total();
check(cli("stop").code === 0, "stop");
const listening = ["47681", "47682", "47683"].filter((p) =>
  spawnSync("/usr/sbin/lsof", ["-nP", `-iTCP:${p}`, "-sTCP:LISTEN", "-t"], { encoding: "utf8" }).stdout.trim());
check(listening.length === 0, "after stop nothing listens on the three ports");
check(cli("start", "--no-open").code === 0, "start again");
check(sha(join(DATA, "keys.env")) === firstKeys && (await total()) === reviewsBefore && reviewsBefore === 3,
  "the same keys and the same reviews after a restart");
check(cli("stop").code === 0, "stop again");

console.log(failures === 0 ? "\nall checks passed" : `\n${failures} check(s) failed — see ${join(DATA, "logs")}`);
process.exit(failures === 0 ? 0 : 1);
