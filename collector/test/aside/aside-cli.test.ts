/**
 * `aside-cli.ts` against a fake CLI: the result protocol, every no-result classification, the timeout, the
 * missing executable, and the invocation shape (argv head is `repl`, the program is the last argument).
 */
import { afterEach, describe, expect, it } from "vitest";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import {
  ASIDE_ALLOWED_INVOCATIONS,
  ASIDE_REPORTABLE_SIGNALS,
  classifyAsideNoResult,
  parseAsideResultLine,
  readAsideCliVersion,
  reportableSignal,
  runAsideRepl,
} from "../../src/aside/aside-cli";

const HERE = resolve(fileURLToPath(import.meta.url), "..");
const FAKE = resolve(HERE, "../support/fake-aside-cli.mjs");

let dir = "";
function argvOut(): string {
  dir = mkdtempSync(join(tmpdir(), "aside-cli-"));
  return join(dir, "argv.json");
}
afterEach(() => {
  if (dir) rmSync(dir, { recursive: true, force: true });
  dir = "";
  delete process.env.FAKE_ASIDE_MODE;
  delete process.env.FAKE_ASIDE_RESULT;
  delete process.env.FAKE_ASIDE_ARGV_OUT;
});

const cli = { command: process.execPath, prefixArgs: [FAKE] };

describe("result protocol", () => {
  it("parses the LAST result line and ignores everything else on stdout", () => {
    const parsed = parseAsideResultLine('✔ Opened a tab (http://x)\nASIDE_RESULT {"a":1}\nASIDE_RESULT {"a":2}\n[ok | 3ms]');
    expect(parsed).toEqual({ found: true, result: { a: 2 } });
  });
  it("a non-JSON payload is malformed, not a result", () => {
    expect(parseAsideResultLine("ASIDE_RESULT {oops")).toEqual({ found: false, malformed: true });
    expect(parseAsideResultLine("nothing here")).toEqual({ found: false, malformed: false });
  });
  it("classifies the observed daemon-down and bad-account texts", () => {
    expect(classifyAsideNoResult("fetch failed: connect ECONNREFUSED 127.0.0.1:21420", 0)).toBe("UNAVAILABLE");
    expect(classifyAsideNoResult("Aside isn't running", 0)).toBe("UNAVAILABLE");
    // The real CLI, observed 2026-09-12 with the app quit (exit 1): two lines, the first a transport error.
    expect(
      classifyAsideNoResult(
        "Failed to request daemon auth challenge: fetch failed\nAside isn't running on this machine.\n  - Start Aside Browser and retry, or\n",
        1,
      ),
    ).toBe("UNAVAILABLE");
    expect(classifyAsideNoResult("Account not found: u9", 1)).toBe("REFUSED");
    expect(classifyAsideNoResult("Error: boom\n at repl.js:1:7", 0)).toBe("FAULT");
  });
});

describe("runAsideRepl over the fake CLI", () => {
  it("returns the parsed result and spawns exactly `repl [--account] <program>`", async () => {
    process.env.FAKE_ASIDE_MODE = "result";
    process.env.FAKE_ASIDE_RESULT = JSON.stringify({ ok: true, hostPath: "/x" });
    process.env.FAKE_ASIDE_ARGV_OUT = argvOut();
    const run = await runAsideRepl("console.log(1)", { ...cli, account: "u0" });
    expect(run.kind).toBe("RESULT");
    if (run.kind !== "RESULT") throw new Error("unreachable");
    expect(run.result).toEqual({ ok: true, hostPath: "/x" });
    const argv = JSON.parse(readFileSync(process.env.FAKE_ASIDE_ARGV_OUT, "utf8")) as string[];
    expect(argv).toEqual(["repl", "--account", "u0", "console.log(1)"]);
    expect(ASIDE_ALLOWED_INVOCATIONS).toContain(argv[0]);
  });

  it("malformed output → FAULT", async () => {
    process.env.FAKE_ASIDE_MODE = "malformed";
    expect(await runAsideRepl("x", cli)).toMatchObject({ kind: "NO_RESULT", reason: "FAULT" });
  });

  it("a program that threw (stderr error, exit 0, no result line) → FAULT", async () => {
    process.env.FAKE_ASIDE_MODE = "silent";
    expect(await runAsideRepl("x", cli)).toMatchObject({ kind: "NO_RESULT", reason: "FAULT" });
  });

  it("daemon down → UNAVAILABLE", async () => {
    process.env.FAKE_ASIDE_MODE = "down";
    expect(await runAsideRepl("x", cli)).toMatchObject({ kind: "NO_RESULT", reason: "UNAVAILABLE" });
  });

  it("unknown account → REFUSED", async () => {
    process.env.FAKE_ASIDE_MODE = "account";
    expect(await runAsideRepl("x", cli)).toMatchObject({ kind: "NO_RESULT", reason: "REFUSED" });
  });

  it("our ceiling elapses → TIMEOUT (and the child is killed)", async () => {
    process.env.FAKE_ASIDE_MODE = "hang";
    const t0 = Date.now();
    const run = await runAsideRepl("x", { ...cli, timeoutMs: 300 });
    expect(run).toMatchObject({ kind: "NO_RESULT", reason: "TIMEOUT" });
    expect(Date.now() - t0).toBeLessThan(5_000);
  });

  it("no executable → UNAVAILABLE", async () => {
    const run = await runAsideRepl("x", { command: "/nonexistent/aside-binary-for-test" });
    expect(run).toMatchObject({ kind: "NO_RESULT", reason: "UNAVAILABLE" });
  });

  it("reads the CLI version through the only other allowed invocation", async () => {
    expect(await readAsideCliVersion(cli)).toBe("9.9.9-fake");
    expect(await readAsideCliVersion({ command: "/nonexistent/aside-binary-for-test" })).toBeNull();
  });
});

/**
 * <b>How the process ended, beside the reason word.</b>
 *
 * The reason has always been classified here and the caller used to flatten all four into one. These two
 * values exist so the record can go one step further than the word — and they are deliberately the only two,
 * because stdout and stderr stop in this file and that is what keeps page text out of an operator record.
 */
describe("the bounded metadata a failed call carries", () => {
  it("a refusal names its exit code", async () => {
    process.env.FAKE_ASIDE_MODE = "account";
    // The fake exits 1 on an unknown account, as the real CLI was observed to.
    expect(await runAsideRepl("x", cli)).toMatchObject({
      kind: "NO_RESULT", reason: "REFUSED", exitCode: 1, signal: null,
    });
  });

  it("a clean exit with no result line names exit 0 — the shape of a program that threw", async () => {
    process.env.FAKE_ASIDE_MODE = "silent";
    expect(await runAsideRepl("x", cli)).toMatchObject({
      kind: "NO_RESULT", reason: "FAULT", exitCode: 0, signal: null,
    });
  });

  it("our own kill shows up as the signal we sent", async () => {
    process.env.FAKE_ASIDE_MODE = "hang";
    const run = await runAsideRepl("x", { ...cli, timeoutMs: 300 });
    expect(run).toMatchObject({ kind: "NO_RESULT", reason: "TIMEOUT", signal: "SIGKILL" });
  });

  it("a process that never started has neither", async () => {
    expect(await runAsideRepl("x", { command: "/nonexistent/aside-binary-for-test" })).toMatchObject({
      kind: "NO_RESULT", reason: "UNAVAILABLE", exitCode: null, signal: null,
    });
  });

  it("the signal name is from a set we chose, never a string the platform handed us", () => {
    expect(reportableSignal("SIGKILL")).toBe("SIGKILL");
    for (const unknown of ["SIGUSR2", "SIGWINCH", "", "whatever", "SIGKILL ", "sigkill"]) {
      expect(reportableSignal(unknown), unknown).toBeNull();
    }
    expect(reportableSignal(null)).toBeNull();
    expect([...ASIDE_REPORTABLE_SIGNALS]).toEqual(["SIGKILL", "SIGTERM", "SIGINT", "SIGSEGV", "SIGABRT"]);
  });
});
