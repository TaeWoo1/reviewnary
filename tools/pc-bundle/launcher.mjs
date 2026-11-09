#!/usr/bin/env node
// The seller-PC launcher — the one program a seller runs (proposal §18.7 「설치 묶음 진행」, packaging option A).
//
//   reviewnary start      prepare on first run, start everything in the background, open the work screen
//   reviewnary run        the same in the foreground, supervising (what a login item runs)
//   reviewnary stop       stop everything, the database last
//   reviewnary status     what is running, in the seller's words
//   reviewnary open       open the work screen
//   reviewnary autostart on|off|print   start at login (macOS LaunchAgent); print shows the file without writing it
//
// It holds no business logic: it prepares the data folder, starts the bundled Postgres, backend and agent runtime in
// that order, restarts one that stops, and stops them in reverse. Everything it starts listens on 127.0.0.1 only.
//
// The program folder (this file's bundle) is replaced on update; the data folder never is. The data folder holds the
// keys made on first run (kept forever — a new key would leave the stored connections unreadable), the database, the
// seller's settings and the logs. Nothing here prints a key, a password or a token.
//
// First run is resumable: each step is checked before it runs and marked when it is done, a database cluster that was
// being created when the run stopped is discarded (it never held data) and made again, and the backend's migrations
// resume on their own (each one is a transaction). Only one launcher runs per data folder (run/launcher.lock).
import { spawn, spawnSync } from "node:child_process";
import { randomBytes, randomUUID } from "node:crypto";
import {
  chmodSync, closeSync, existsSync, fsyncSync, mkdirSync, openSync, readFileSync, renameSync, rmSync, unlinkSync,
  writeFileSync, writeSync,
} from "node:fs";
import { homedir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

export const PORTS = { backend: 47681, runtime: 47682, postgres: 47683 };
const DB_NAME = "reviewnary";
const DB_USER = "reviewnary";
export const KEY_NAMES = ["JWT_SECRET", "VAULT_MASTER_KEY", "VAULT_KEY_ID", "DB_PASSWORD"];

/**
 * What a seller may set in settings.env: the AI capabilities' own settings and the review rules. Everything that
 * decides where this program listens, which database it uses, its keys and its agent runtime is the launcher's and is
 * refused — the backend's own validator (LocalSellerConfigValidator) refuses the rest of what reaches past this PC.
 */
const SETTING_PREFIXES = ["SELLEROPS_AGENT_", "SELLEROPS_INQUIRY_", "SELLEROPS_KNOWLEDGE_", "SELLEROPS_REVIEW_",
  "SELLEROPS_TRIAGE_"];
const SETTING_NAMES = new Set(["SELLEROPS_DEV_FAKE_LLM"]);
const LAUNCHER_OWNED = [/^SELLEROPS_AGENT_RUNTIME_/, /^SELLEROPS_AGENT_QUOTA_/];

// ── paths ────────────────────────────────────────────────────────────────────────────────────────────────────────

export function bundleDir() {
  return resolve(dirname(fileURLToPath(import.meta.url)), "..");
}

export function dataDir(env = process.env) {
  if (env.REVIEWNARY_DATA_DIR) return resolve(env.REVIEWNARY_DATA_DIR);
  return join(homedir(), "Library", "Application Support", "Reviewnary");
}

export function layout(data) {
  return {
    data,
    keys: join(data, "keys.env"),
    settings: join(data, "settings.env"),
    pg: join(data, "pg"),
    pgInitializing: join(data, "pg.initializing"),
    state: join(data, "state"),
    run: join(data, "run"),
    logs: join(data, "logs"),
    lock: join(data, "run", "launcher.lock"),
    status: join(data, "run", "status.json"),
  };
}

function marker(L, name) {
  return join(L.state, name);
}

/** Write a file so that a stop at any instant leaves either the old file or the whole new one — never half. */
export function writeAtomic(path, content, mode = 0o600) {
  const tmp = `${path}.tmp-${process.pid}`;
  const fd = openSync(tmp, "w", mode);
  try {
    writeSync(fd, content);
    fsyncSync(fd);
  } finally {
    closeSync(fd);
  }
  renameSync(tmp, path);
}

function say(line) {
  process.stdout.write(`${line}\n`);
}

function log(L, line) {
  try {
    mkdirSync(L.logs, { recursive: true });
    writeFileSync(join(L.logs, "launcher.log"), `${new Date().toISOString()} ${line}\n`, { flag: "a" });
  } catch {
    // a log that cannot be written never stops the program
  }
}

// ── one launcher per data folder ─────────────────────────────────────────────────────────────────────────────────

function alive(pid) {
  if (!Number.isInteger(pid) || pid <= 0) return false;
  try {
    process.kill(pid, 0);
    return true;
  } catch (e) {
    return e.code === "EPERM";
  }
}

/** The command line of a process, or "" — so a reused pid is not mistaken for ours. */
function commandOf(pid) {
  const r = spawnSync("/bin/ps", ["-p", String(pid), "-o", "command="], { encoding: "utf8" });
  return r.status === 0 ? r.stdout.trim() : "";
}

/** The launcher that holds the lock, if one is running; null otherwise. */
export function holder(L) {
  let held;
  try {
    held = JSON.parse(readFileSync(L.lock, "utf8"));
  } catch {
    return null;
  }
  if (!alive(held.pid)) return null;
  const cmd = commandOf(held.pid);
  // Before `ps` answers (or where it cannot), a live pid is taken as ours: refusing a second start is the safe side.
  if (cmd && !cmd.includes("launcher")) return null;
  return held;
}

/**
 * Take the lock or say who has it. Creation is exclusive (`wx`), so of two launchers started at the same instant only
 * one wins; a lock left by a launcher that died is moved aside first, by rename, which also only one of them can do.
 */
export function acquireLock(L) {
  mkdirSync(L.run, { recursive: true });
  for (let attempt = 0; attempt < 2; attempt++) {
    try {
      const fd = openSync(L.lock, "wx", 0o600);
      writeSync(fd, JSON.stringify({ pid: process.pid, nonce: randomUUID(), startedAt: new Date().toISOString() }));
      closeSync(fd);
      return { ok: true };
    } catch (e) {
      if (e.code !== "EEXIST") throw e;
      const held = holder(L);
      if (held) return { ok: false, holder: held };
      try {
        renameSync(L.lock, `${L.lock}.stale-${process.pid}`);
        rmSync(`${L.lock}.stale-${process.pid}`, { force: true });
      } catch {
        // another launcher moved it first; the next attempt sees its lock
      }
    }
  }
  const held = holder(L);
  return held ? { ok: false, holder: held } : { ok: false, holder: null };
}

export function releaseLock(L) {
  try {
    const held = JSON.parse(readFileSync(L.lock, "utf8"));
    if (held.pid === process.pid) unlinkSync(L.lock);
  } catch {
    // already gone
  }
}

// ── keys: made once, kept forever ────────────────────────────────────────────────────────────────────────────────

export function parseEnvFile(text) {
  const out = {};
  for (const raw of text.split("\n")) {
    const line = raw.trim();
    if (!line || line.startsWith("#")) continue;
    const eq = line.indexOf("=");
    if (eq <= 0) continue;
    out[line.slice(0, eq).trim()] = line.slice(eq + 1).trim();
  }
  return out;
}

/**
 * The install's keys. Made on the first run and never again: a second key would leave the database password, the
 * stored channel connections and every session unreadable. So a keys file that is missing while a database exists, or
 * one that lacks a key, stops the program with a message instead of being made again.
 */
export function ensureKeys(L) {
  if (existsSync(L.keys)) {
    const keys = parseEnvFile(readFileSync(L.keys, "utf8"));
    const missing = KEY_NAMES.filter((k) => !keys[k]);
    if (missing.length) {
      throw new LauncherError("KEYS_DAMAGED", `보관된 키 파일에 ${missing.join(", ")}이(가) 없어요. 새로 만들면 저장된 연결 `
        + "정보를 열 수 없게 돼서 멈췄어요. 백업한 keys.env가 있으면 되돌려 주세요.");
    }
    return { keys, made: false };
  }
  if (existsSync(marker(L, "pg-initialized")) || existsSync(L.pg)) {
    throw new LauncherError("KEYS_MISSING", "데이터는 있는데 키 파일(keys.env)이 없어요. 새 키로는 기존 데이터를 열 수 없어서 "
      + "멈췄어요. 백업한 keys.env를 데이터 폴더에 되돌려 주세요.");
  }
  const keys = {
    JWT_SECRET: randomBytes(32).toString("hex"),
    VAULT_MASTER_KEY: randomBytes(32).toString("base64"),
    VAULT_KEY_ID: `pc-${new Date().toISOString().slice(0, 10)}-${randomBytes(3).toString("hex")}`,
    DB_PASSWORD: randomBytes(24).toString("base64url"),
  };
  mkdirSync(L.data, { recursive: true, mode: 0o700 });
  writeAtomic(L.keys, `# Reviewnary 설치 키 — 처음 실행할 때 한 번 만들고 바꾸지 않습니다. 지우면 저장된 데이터를 열 수 없어요.\n${
    KEY_NAMES.map((k) => `${k}=${keys[k]}`).join("\n")}\n`);
  chmodSync(L.keys, 0o600);
  return { keys, made: true };
}

// ── settings the seller may change ───────────────────────────────────────────────────────────────────────────────

export function readSettings(L) {
  if (!existsSync(L.settings)) return {};
  const settings = parseEnvFile(readFileSync(L.settings, "utf8"));
  const refused = Object.keys(settings).filter((k) => !settingAllowed(k));
  if (refused.length) {
    throw new LauncherError("SETTING_REFUSED", `settings.env의 ${refused.join(", ")}은(는) 이 프로그램이 정하는 값이라 `
      + "바꿀 수 없어요. 그 줄을 지우고 다시 실행해 주세요.");
  }
  return settings;
}

export function settingAllowed(name) {
  if (LAUNCHER_OWNED.some((re) => re.test(name))) return false;
  return SETTING_NAMES.has(name) || SETTING_PREFIXES.some((p) => name.startsWith(p));
}

export class LauncherError extends Error {
  constructor(code, message) {
    super(message);
    this.code = code;
  }
}

// ── database ─────────────────────────────────────────────────────────────────────────────────────────────────────

function pgBin(name) {
  return join(bundleDir(), "pgsql", "bin", name);
}

function runQuiet(cmd, args, opts = {}) {
  const r = spawnSync(cmd, args, { encoding: "utf8", ...opts });
  return { status: r.status, out: `${r.stdout ?? ""}${r.stderr ?? ""}` };
}

/** The cluster, made once. One left half-made by a stop during `initdb` never held data and is made again. */
export function ensureCluster(L, keys) {
  if (existsSync(marker(L, "pg-initialized")) && existsSync(join(L.pg, "PG_VERSION"))) return false;
  if (existsSync(L.pg) && !existsSync(marker(L, "pg-initialized"))) {
    // A cluster without the mark was renamed into place and the mark not yet written — still empty; keep the old
    // folder aside rather than reading what it might be.
    renameSync(L.pg, `${L.pg}.unmarked-${Date.now()}`);
  }
  rmSync(L.pgInitializing, { recursive: true, force: true });
  mkdirSync(L.run, { recursive: true });
  const pwfile = join(L.run, `pw-${process.pid}`);
  writeFileSync(pwfile, `${keys.DB_PASSWORD}\n`, { mode: 0o600 });
  try {
    const r = runQuiet(pgBin("initdb"), ["-D", L.pgInitializing, "-U", DB_USER, "-A", "scram-sha-256",
      `--pwfile=${pwfile}`, "-E", "UTF8", "--locale=C", "--no-instructions"]);
    if (r.status !== 0) {
      writeFileSync(join(L.logs, "initdb.log"), r.out);
      throw new LauncherError("INITDB_FAILED", "데이터베이스를 처음 만들지 못했어요. logs/initdb.log를 확인해 주세요.");
    }
  } finally {
    rmSync(pwfile, { force: true });
  }
  // The work database, made in single-user mode before the cluster is ever started: the bundle carries the server and
  // no client, and a cluster renamed into place always has it — there is no "cluster without its database" to resume.
  const made = runQuiet(pgBin("postgres"), ["--single", "-D", L.pgInitializing, "postgres"],
    { input: `CREATE DATABASE ${DB_NAME}\n` });
  if (made.status !== 0 || /ERROR|FATAL/.test(made.out)) {
    writeFileSync(join(L.logs, "initdb.log"), made.out);
    throw new LauncherError("INITDB_FAILED", "업무 데이터베이스를 만들지 못했어요. logs/initdb.log를 확인해 주세요.");
  }
  renameSync(L.pgInitializing, L.pg);
  mkdirSync(L.state, { recursive: true });
  writeAtomic(marker(L, "pg-initialized"), new Date().toISOString(), 0o644);
  return true;
}

function pgCtl(L, ...args) {
  return runQuiet(pgBin("pg_ctl"), ["-D", L.pg, ...args]);
}

export function postgresRunning(L) {
  return pgCtl(L, "status").status === 0;
}

function startPostgres(L) {
  if (postgresRunning(L)) return;
  // TCP on 127.0.0.1 only; no Unix socket, whose path length limit a long data-folder path would exceed.
  const opts = `-p ${PORTS.postgres} -c listen_addresses=127.0.0.1 -c unix_socket_directories=''`;
  const r = pgCtl(L, "-l", join(L.logs, "postgres.log"), "-w", "-t", "90", "-o", opts, "start");
  if (r.status !== 0) {
    throw new LauncherError("POSTGRES_FAILED", "데이터베이스를 켜지 못했어요. logs/postgres.log를 확인해 주세요.");
  }
}

function stopPostgres(L) {
  if (postgresRunning(L)) pgCtl(L, "-w", "-t", "60", "-m", "fast", "stop");
}

// ── the two programs ─────────────────────────────────────────────────────────────────────────────────────────────

const SCREEN_ORIGIN = `http://127.0.0.1:${PORTS.backend}`;

function buildInfo() {
  try {
    return parseEnvFile(readFileSync(join(bundleDir(), "BUILD.txt"), "utf8"));
  } catch {
    return {};
  }
}

export function backendEnv(L, keys, settings) {
  const b = bundleDir();
  const info = buildInfo();
  return {
    PATH: "/usr/bin:/bin",
    HOME: process.env.HOME ?? homedir(),
    JAVA_HOME: join(b, "jre"),
    ...settings,
    SPRING_PROFILES_ACTIVE: "local-seller",
    SERVER_PORT: String(PORTS.backend),
    SPRING_DATASOURCE_URL: `jdbc:postgresql://127.0.0.1:${PORTS.postgres}/${DB_NAME}`,
    SPRING_DATASOURCE_USERNAME: DB_USER,
    SPRING_DATASOURCE_PASSWORD: keys.DB_PASSWORD,
    SELLEROPS_JWT_SECRET: keys.JWT_SECRET,
    SELLEROPS_VAULT_MASTER_KEY: keys.VAULT_MASTER_KEY,
    SELLEROPS_VAULT_KEY_ID: keys.VAULT_KEY_ID,
    SELLEROPS_CORS_ORIGIN: SCREEN_ORIGIN,
    SELLEROPS_LOCAL_SELLER_SCREEN_DIR: join(b, "app", "screen"),
    // Review replies come only from the reply agent here; a missing runtime is said, never covered by another drafter.
    SELLEROPS_AGENT_RUNTIME_URL: `http://127.0.0.1:${PORTS.runtime}`,
    SELLEROPS_AGENT_RUNTIME_REQUIRED: "true",
    SELLEROPS_REVIEW_PRODUCT_DERIVE: settings.SELLEROPS_REVIEW_PRODUCT_DERIVE ?? "true",
    SELLEROPS_VENDOR_LINK_BASE_URL: info.vendor_url ?? "",
    SELLEROPS_VENDOR_REVIEW_PULL_ENABLED: info.vendor_url ? "true" : "false",
  };
}

function runtimeEnv(L) {
  return {
    PATH: "/usr/bin:/bin",
    HOME: process.env.HOME ?? homedir(),
    APP_ENV: "production",
    AGENT_RUNTIME_PORT: String(PORTS.runtime),
    AGENT_RUNTIME_HOST: "127.0.0.1",
    AGENT_RUNTIME_RUNSTORE_KIND: "spring",
    AGENT_RUNTIME_CORS_ORIGINS: SCREEN_ORIGIN,
    SELLEROPS_BASE_URL: SCREEN_ORIGIN,
  };
}

function programs(L, keys, settings) {
  const b = bundleDir();
  return [
    {
      name: "backend",
      cmd: join(b, "jre", "bin", "java"),
      args: ["-Xmx512m", "-XX:+UseSerialGC", `-Djava.io.tmpdir=${join(L.data, "tmp")}`, "-jar",
        join(b, "app", "backend.jar")],
      env: backendEnv(L, keys, settings),
      health: `http://127.0.0.1:${PORTS.backend}/health`,
      // First boot applies every migration; give it room.
      bootSeconds: 240,
    },
    {
      name: "runtime",
      cmd: join(b, "bin", "node"),
      args: [join(b, "app", "agent-runtime.mjs")],
      env: runtimeEnv(L),
      health: `http://127.0.0.1:${PORTS.runtime}/health`,
      bootSeconds: 60,
    },
  ];
}

async function healthy(url) {
  try {
    const r = await fetch(url, { signal: AbortSignal.timeout(2000) });
    return r.ok;
  } catch {
    return false;
  }
}

async function waitHealthy(url, seconds, stillRunning) {
  for (let i = 0; i < seconds * 2; i++) {
    if (await healthy(url)) return true;
    if (!stillRunning()) return false;
    await sleep(500);
  }
  return false;
}

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}

function portTaken(port) {
  return runQuiet("/usr/sbin/lsof", ["-nP", `-iTCP:${port}`, "-sTCP:LISTEN", "-t"]).out.trim();
}

/** Processes a launcher that died left behind: recorded in status.json and still running this bundle's programs. */
function stopOrphans(L) {
  let last;
  try {
    last = JSON.parse(readFileSync(L.status, "utf8"));
  } catch {
    return;
  }
  const left = Object.values(last.programs ?? {})
    .filter((p) => p.pid && alive(p.pid) && commandOf(p.pid).includes(bundleDir()));
  for (const p of left) {
    try {
      process.kill(p.pid, "SIGTERM");
    } catch {
      // gone
    }
  }
  // Stopped before the ports are checked: a backend stopped mid-migration rolls that migration back and redoes it.
  const deadline = Date.now() + 30_000;
  while (left.some((p) => alive(p.pid)) && Date.now() < deadline) sleepSync(250);
  for (const p of left.filter((q) => alive(q.pid))) process.kill(p.pid, "SIGKILL");
}

function sleepSync(ms) {
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
}

function writeStatus(L, status) {
  writeAtomic(L.status, JSON.stringify(status, null, 2), 0o644);
}

// ── run: prepare, start, supervise ───────────────────────────────────────────────────────────────────────────────

const RESTART_LIMIT = 5;
const RESTART_WINDOW_MS = 10 * 60 * 1000;

export async function run(L, { onReady } = {}) {
  const lock = acquireLock(L);
  if (!lock.ok) {
    throw new LauncherError("ALREADY_RUNNING", "Reviewnary가 이미 실행 중이에요. 같은 데이터로 두 번 켜지 않아요.");
  }
  const children = new Map();
  let stopping = false;
  const status = { startedAt: new Date().toISOString(), launcherPid: process.pid, phase: "preparing", programs: {} };
  const shutdown = async (code) => {
    if (stopping) return;
    stopping = true;
    status.phase = "stopping";
    writeStatus(L, status);
    for (const name of ["runtime", "backend"]) {
      const c = children.get(name);
      if (c && c.child.exitCode === null) {
        c.child.kill("SIGTERM");
        for (let i = 0; i < 60 && c.child.exitCode === null; i++) await sleep(500);
        if (c.child.exitCode === null) c.child.kill("SIGKILL");
      }
    }
    stopPostgres(L);
    status.phase = "stopped";
    status.programs = {};
    writeStatus(L, status);
    log(L, "stopped");
    releaseLock(L);
    process.exit(code);
  };
  process.on("SIGTERM", () => shutdown(0));
  process.on("SIGINT", () => shutdown(0));

  try {
    for (const dir of [L.state, L.run, L.logs, join(L.data, "tmp")]) mkdirSync(dir, { recursive: true });
    chmodSync(L.data, 0o700);
    const { keys, made } = ensureKeys(L);
    if (made) log(L, "keys made");
    const settings = readSettings(L);
    stopOrphans(L);
    for (const [name, port] of Object.entries(PORTS)) {
      if (name !== "postgres" || !postgresRunning(L)) {
        if (portTaken(port)) {
          throw new LauncherError("PORT_TAKEN", `다른 프로그램이 ${port}번 포트를 쓰고 있어서 켜지 못했어요.`);
        }
      }
    }
    if (ensureCluster(L, keys)) log(L, "database cluster made");
    startPostgres(L);
    status.programs.postgres = { state: "running", port: PORTS.postgres };

    for (const p of programs(L, keys, settings)) {
      const c = { spec: p, restarts: [], child: null, gaveUp: false };
      children.set(p.name, c);
      startChild(L, c, status);
      const up = await waitHealthy(p.health, p.bootSeconds, () => c.child.exitCode === null);
      if (!up) {
        throw new LauncherError("START_FAILED", `${label(p.name)}이(가) 켜지지 않았어요. logs/${p.name}.log를 확인해 주세요.`);
      }
      status.programs[p.name] = { state: "running", pid: c.child.pid, port: PORTS[p.name] };
      writeStatus(L, status);
    }
    status.phase = "running";
    status.fakeModel = settings.SELLEROPS_DEV_FAKE_LLM === "true";
    writeStatus(L, status);
    log(L, "running");
    onReady?.();
  } catch (e) {
    log(L, `start failed: ${e.code ?? e.name}`);
    status.phase = "failed";
    status.error = e instanceof LauncherError ? e.message : "시작하지 못했어요.";
    writeStatus(L, status);
    await shutdownChildrenOnly(children);
    stopPostgres(L);
    releaseLock(L);
    throw e;
  }

  // Supervise: a program that stops is started again, a few times; the database is checked every few seconds.
  for (;;) {
    await sleep(5000);
    if (stopping) return;
    if (!postgresRunning(L)) {
      log(L, "postgres stopped; starting again");
      try {
        startPostgres(L);
      } catch {
        status.programs.postgres = { state: "stopped", port: PORTS.postgres };
        writeStatus(L, status);
      }
    }
  }

  function startChild(L, c, status) {
    const out = openSync(join(L.logs, `${c.spec.name}.log`), "a");
    const child = spawn(c.spec.cmd, c.spec.args, { env: c.spec.env, stdio: ["ignore", out, out], cwd: L.data });
    closeSync(out);
    c.child = child;
    // Recorded before it is healthy, so a launcher that dies during a first boot leaves a pid the next one can stop.
    status.programs[c.spec.name] = { state: "starting", pid: child.pid };
    writeStatus(L, status);
    child.on("exit", (code, signal) => {
      if (stopping) return;
      log(L, `${c.spec.name} exited code=${code} signal=${signal}`);
      const now = Date.now();
      c.restarts = c.restarts.filter((t) => now - t < RESTART_WINDOW_MS);
      if (c.restarts.length >= RESTART_LIMIT) {
        c.gaveUp = true;
        status.programs[c.spec.name] = { state: "stopped", reason: "되살리기를 여러 번 실패했어요" };
        writeStatus(L, status);
        return;
      }
      c.restarts.push(now);
      status.programs[c.spec.name] = { state: "restarting" };
      writeStatus(L, status);
      setTimeout(async () => {
        if (stopping) return;
        startChild(L, c, status);
        if (await waitHealthy(c.spec.health, c.spec.bootSeconds, () => c.child.exitCode === null)) {
          status.programs[c.spec.name] = { state: "running", pid: c.child.pid, port: PORTS[c.spec.name] };
          writeStatus(L, status);
        }
      }, 2000 * 2 ** (c.restarts.length - 1));
    });
  }
}

async function shutdownChildrenOnly(children) {
  for (const c of children.values()) {
    if (c.child && c.child.exitCode === null) {
      c.child.kill("SIGTERM");
      for (let i = 0; i < 40 && c.child.exitCode === null; i++) await sleep(500);
      if (c.child.exitCode === null) c.child.kill("SIGKILL");
    }
  }
}

function label(name) {
  return { backend: "업무 서버", runtime: "AI 답글 에이전트", postgres: "데이터베이스" }[name] ?? name;
}

// ── the commands ─────────────────────────────────────────────────────────────────────────────────────────────────

function openScreen() {
  spawnSync("/usr/bin/open", [SCREEN_ORIGIN]);
}

async function start(L, { open }) {
  const held = holder(L);
  if (held) {
    say("Reviewnary가 이미 실행 중이에요.");
    if (open) openScreen();
    return 0;
  }
  mkdirSync(L.logs, { recursive: true });
  const out = openSync(join(L.logs, "launcher.out"), "a");
  const child = spawn(process.execPath, [fileURLToPath(import.meta.url), "run"], {
    detached: true, stdio: ["ignore", out, out], env: process.env,
  });
  closeSync(out);
  child.unref();
  say("Reviewnary를 켜는 중이에요. 처음 실행이면 몇 분 걸릴 수 있어요.");
  for (let i = 0; i < 600; i++) {
    await sleep(1000);
    let s;
    try {
      s = JSON.parse(readFileSync(L.status, "utf8"));
    } catch {
      continue;
    }
    if (s.launcherPid !== child.pid) continue;
    if (s.phase === "running") {
      say(`켜졌어요. 작업 화면: ${SCREEN_ORIGIN}`);
      if (s.fakeModel) say("주의: 가짜 모델(점검용)로 실행 중이에요. 초안은 실제 답글이 아니에요.");
      if (open) openScreen();
      return 0;
    }
    if (s.phase === "failed") {
      say(s.error ?? "시작하지 못했어요.");
      return 1;
    }
    if (!alive(child.pid)) break;
  }
  say("시작을 확인하지 못했어요. logs 폴더를 확인해 주세요.");
  return 1;
}

async function stop(L) {
  const held = holder(L);
  if (!held) {
    say("실행 중이 아니에요.");
    return 0;
  }
  process.kill(held.pid, "SIGTERM");
  for (let i = 0; i < 120 && alive(held.pid); i++) await sleep(500);
  say(alive(held.pid) ? "끄는 중이에요. 잠시 뒤 다시 확인해 주세요." : "껐어요.");
  return alive(held.pid) ? 1 : 0;
}

async function status(L) {
  const held = holder(L);
  if (!held) {
    say("Reviewnary: 꺼져 있어요.");
    return 3;
  }
  let s = {};
  try {
    s = JSON.parse(readFileSync(L.status, "utf8"));
  } catch {
    // starting
  }
  say(`Reviewnary: ${{ preparing: "켜는 중", running: "실행 중", stopping: "끄는 중", failed: "시작 실패" }[s.phase] ?? "확인 중"}`);
  const checks = [
    ["postgres", postgresRunning(L)],
    ["backend", await healthy(`http://127.0.0.1:${PORTS.backend}/health`)],
    ["runtime", await healthy(`http://127.0.0.1:${PORTS.runtime}/health`)],
  ];
  for (const [name, ok] of checks) {
    const recorded = s.programs?.[name];
    say(`  ${label(name)}: ${ok ? "응답함" : recorded?.reason ?? (recorded?.state === "restarting" ? "다시 켜는 중" : "응답 없음")}`);
  }
  if (s.fakeModel) say("  주의: 가짜 모델(점검용)로 실행 중");
  return checks.every(([, ok]) => ok) ? 0 : 4;
}

const PLIST_LABEL = "kr.reviewnary.pc";

export function launchAgentPlist(L) {
  const exe = join(bundleDir(), "reviewnary");
  return `<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>${PLIST_LABEL}</string>
  <key>ProgramArguments</key><array><string>${exe}</string><string>run</string></array>
  <key>EnvironmentVariables</key><dict><key>REVIEWNARY_DATA_DIR</key><string>${L.data}</string></dict>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><dict><key>SuccessfulExit</key><false/></dict>
  <key>ProcessType</key><string>Background</string>
</dict>
</plist>
`;
}

function autostart(L, mode) {
  const path = join(homedir(), "Library", "LaunchAgents", `${PLIST_LABEL}.plist`);
  const uid = process.getuid?.();
  if (mode === "print") {
    process.stdout.write(launchAgentPlist(L));
    return 0;
  }
  if (mode === "on") {
    mkdirSync(dirname(path), { recursive: true });
    writeAtomic(path, launchAgentPlist(L), 0o644);
    runQuiet("/bin/launchctl", ["bootstrap", `gui/${uid}`, path]);
    say("로그인하면 Reviewnary가 저절로 켜져요.");
    return 0;
  }
  if (mode === "off") {
    runQuiet("/bin/launchctl", ["bootout", `gui/${uid}/${PLIST_LABEL}`]);
    rmSync(path, { force: true });
    say("로그인할 때 저절로 켜지지 않아요.");
    return 0;
  }
  say("autostart on | off | print");
  return 2;
}

async function main(argv) {
  const L = layout(dataDir());
  const [cmd, arg] = argv;
  try {
    switch (cmd) {
      case "start": return await start(L, { open: !argv.includes("--no-open") });
      case "run": await run(L); return 0;
      case "stop": return await stop(L);
      case "status": return await status(L);
      case "open": openScreen(); return 0;
      case "autostart": return autostart(L, arg);
      default:
        say("reviewnary start | run | stop | status | open | autostart on|off|print");
        return 2;
    }
  } catch (e) {
    if (e instanceof LauncherError) {
      say(e.message);
      return e.code === "ALREADY_RUNNING" ? 3 : 1;
    }
    log(L, `unexpected: ${e?.name}`);
    say("예상하지 못한 문제로 멈췄어요. logs 폴더를 확인해 주세요.");
    return 1;
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main(process.argv.slice(2)).then((code) => process.exit(code));
}
