/**
 * Start / stop / check the **kept-alive NAVER test browser** (dev only; see `src/naver-test-browser.ts`).
 *
 *   npx tsx instruments/live-runs/naver-test-browser.ts start [--port 47691]
 *   npx tsx instruments/live-runs/naver-test-browser.ts status
 *   npx tsx instruments/live-runs/naver-test-browser.ts stop
 *
 * `start` opens an empty window on the walk's own profile and returns; the browser keeps running. It navigates
 * nowhere — the walk host does that, under its own approval, after attaching with
 * `SELLEROPS_NAVER_TEST_BROWSER_PORT`. `stop` ends it with SIGTERM, so Chromium exits through its own clean path.
 *
 * Prints a port and a pid, never a path, URL or cookie. Inert on import.
 */
import { execFileSync, spawn } from "node:child_process";
import { mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { loadConfig } from "../../src/config";
import { parseTestBrowserPort, testBrowserAnswers, testBrowserArgs, testBrowserExecutable } from "../../src/naver-test-browser";

const DEFAULT_PORT = 47691;
const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "../../..");
const STATE_FILE = resolve(REPO_ROOT, "tools/naver-local/.run/test-browser.json");

interface State {
  pid: number;
  port: number;
}

function readState(): State | null {
  try {
    const s = JSON.parse(readFileSync(STATE_FILE, "utf8")) as Partial<State>;
    return typeof s.pid === "number" && typeof s.port === "number" ? { pid: s.pid, port: s.port } : null;
  } catch {
    return null;
  }
}

/** The pid is still OUR browser: alive, and its command line carries this profile's user-data-dir. */
function ownsPid(pid: number, userDataArg: string): boolean {
  try {
    const cmd = execFileSync("ps", ["-o", "command=", "-p", String(pid)], { encoding: "utf8" });
    return cmd.includes(userDataArg);
  } catch {
    return false;
  }
}

async function waitFor(check: () => Promise<boolean> | boolean, ms: number): Promise<boolean> {
  for (let waited = 0; waited < ms; waited += 250) {
    if (await check()) return true;
    await new Promise((r) => setTimeout(r, 250));
  }
  return check();
}

async function main(): Promise<void> {
  const [cmd, ...rest] = process.argv.slice(2).filter((a) => a !== "--");
  const cfg = loadConfig();
  const portFlag = rest.indexOf("--port");
  const port = portFlag >= 0 ? parseTestBrowserPort(rest[portFlag + 1]) : DEFAULT_PORT;
  if (port === null) {
    console.error("Refusing: --port must be an integer in 1024–65535.");
    process.exit(2);
  }
  const args = testBrowserArgs(cfg.profileDir, port);
  const userDataArg = args[0]!;
  const state = readState();
  const running = state !== null && ownsPid(state.pid, userDataArg);

  if (cmd === "status") {
    const answers = running ? await testBrowserAnswers(state.port) : false;
    console.log(JSON.stringify({ event: "NAVER_TEST_BROWSER", running, cdpAnswers: answers, ...(running ? state : {}) }));
    return;
  }

  if (cmd === "stop") {
    if (!running) {
      rmSync(STATE_FILE, { force: true });
      console.log(JSON.stringify({ event: "NAVER_TEST_BROWSER", stopped: false, reason: "not_running" }));
      return;
    }
    process.kill(state.pid, "SIGTERM");
    const gone = await waitFor(() => !ownsPid(state.pid, userDataArg), 10_000);
    if (gone) rmSync(STATE_FILE, { force: true });
    console.log(JSON.stringify({ event: "NAVER_TEST_BROWSER", stopped: gone }));
    return;
  }

  if (cmd === "start") {
    if (running) {
      console.log(JSON.stringify({ event: "NAVER_TEST_BROWSER", started: false, reason: "already_running", ...state }));
      return;
    }
    if (await testBrowserAnswers(port)) {
      console.error("Refusing: something else already answers CDP on that port.");
      process.exit(2);
    }
    mkdirSync(args[0]!.slice("--user-data-dir=".length), { recursive: true });
    const child = spawn(testBrowserExecutable(cfg.browserChannel), args, { detached: true, stdio: "ignore" });
    child.unref();
    const up = await waitFor(() => testBrowserAnswers(port), 20_000);
    if (!up || child.pid === undefined) {
      if (child.pid !== undefined) process.kill(child.pid, "SIGTERM");
      console.error("The test browser did not answer on its debugging port; stopped it.");
      process.exit(1);
    }
    mkdirSync(dirname(STATE_FILE), { recursive: true });
    writeFileSync(STATE_FILE, JSON.stringify({ pid: child.pid, port }));
    console.log(JSON.stringify({ event: "NAVER_TEST_BROWSER", started: true, pid: child.pid, port }));
    return;
  }

  console.error("Usage: naver-test-browser.ts start [--port N] | status | stop");
  process.exit(2);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) void main();
