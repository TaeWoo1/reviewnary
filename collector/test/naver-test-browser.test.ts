/**
 * The kept-alive NAVER test browser (2026-10-19): a login that does not survive a closed browser survives a walk
 * host that only disconnects. The real-Chromium half is gated behind RUN_INTEGRATION=1 and reaches no network —
 * every request is answered by a route.
 */
import { spawn, type ChildProcess } from "node:child_process";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { createServer } from "node:net";
import { join, resolve } from "node:path";
import { describe, expect, it } from "vitest";
import {
  attachTestBrowser,
  parseTestBrowserPort,
  testBrowserAnswers,
  testBrowserArgs,
  testBrowserExecutable,
} from "../src/naver-test-browser";

const COLLECTOR = resolve(__dirname, "..");
const RUN = process.env.RUN_INTEGRATION === "1";

describe("kept-alive test browser — launch arguments", () => {
  it("accepts only an unprivileged integer port", () => {
    expect(parseTestBrowserPort("47691")).toBe(47691);
    for (const bad of [undefined, "", "80", "70000", "47691x", "http://127.0.0.1:47691", "-1"]) {
      expect(parseTestBrowserPort(bad)).toBeNull();
    }
  });

  it("**binds loopback, keeps the keychain posture, and never opens CDP to web origins**", () => {
    const args = testBrowserArgs(join(COLLECTOR, ".profile/naver"), 47691, COLLECTOR);
    expect(args).toContain("--remote-debugging-address=127.0.0.1");
    expect(args).toContain("--remote-debugging-port=47691");
    expect(args).toContain("--use-mock-keychain");
    expect(args.some((a) => a.startsWith("--remote-allow-origins"))).toBe(false);
    expect(args.some((a) => a.startsWith("--enable-automation") || a.startsWith("--headless"))).toBe(false);
    // It opens nothing: the walk host navigates, under its own approval.
    expect(args[args.length - 1]).toBe("about:blank");
  });

  it("refuses a profile outside the collector tree", () => {
    expect(() => testBrowserArgs("/tmp/elsewhere", 47691, COLLECTOR)).toThrow(/inside the collector/);
  });
});

describe("the walk host's attach mode — source guard", () => {
  const host = readFileSync(join(COLLECTOR, "instruments/live-runs/run-api-issuance-live-naver.ts"), "utf8");

  it("**disconnects from an attached browser instead of closing it**", () => {
    expect(host).toMatch(/if \(attached\) await attached\.disconnect\(\);\s*else await ctx\.close\(\);/);
  });

  it("refuses, before any launch, a named port with no test browser behind it", () => {
    const refuse = host.indexOf("names no running test browser");
    const launch = host.indexOf("launchNaverContext(cfg.profileDir");
    expect(refuse).toBeGreaterThan(0);
    expect(launch).toBeGreaterThan(refuse);
  });
});

async function freePort(): Promise<number> {
  return new Promise((done, fail) => {
    const srv = createServer();
    srv.once("error", fail);
    srv.listen(0, "127.0.0.1", () => {
      const port = (srv.address() as { port: number }).port;
      srv.close(() => done(port));
    });
  });
}

describe.skipIf(!RUN)("kept-alive test browser — real Chromium, nothing leaves this machine", () => {
  it("**a session cookie outlives the walk host's disconnect, and the browser keeps running**", async () => {
    const profileRoot = mkdtempSync(join(COLLECTOR, ".profile/__test-browser-"));
    const port = await freePort();
    let child: ChildProcess | null = null;
    try {
      child = spawn(testBrowserExecutable(undefined), testBrowserArgs(join(profileRoot, "p"), port, COLLECTOR), {
        stdio: "ignore",
      });
      for (let i = 0; i < 80 && !(await testBrowserAnswers(port)); i++) await new Promise((r) => setTimeout(r, 250));
      expect(await testBrowserAnswers(port)).toBe(true);

      const site = "https://login.example.test/";
      const body = `<!doctype html><body>ok</body>`;

      // Walk 1: the seller logs in — a session cookie (no expiry), the kind a closed browser forgets.
      const first = await attachTestBrowser(port);
      await first.context.route("**/*", (route) => route.fulfill({ status: 200, contentType: "text/html", body }));
      const p1 = await first.context.newPage();
      await p1.goto(site);
      await p1.evaluate(() => {
        document.cookie = "session_marker=1; path=/";
      });
      await first.disconnect();

      expect(await testBrowserAnswers(port)).toBe(true);

      // Walk 2: a new host attaches and finds the session.
      const second = await attachTestBrowser(port);
      await second.context.route("**/*", (route) => route.fulfill({ status: 200, contentType: "text/html", body }));
      const p2 = await second.context.newPage();
      await p2.goto(site);
      expect(await p2.evaluate(() => document.cookie)).toContain("session_marker=1");
      await second.disconnect();
    } finally {
      child?.kill("SIGTERM");
      await new Promise((r) => setTimeout(r, 500));
      rmSync(profileRoot, { recursive: true, force: true });
    }
  }, 60_000);
});
