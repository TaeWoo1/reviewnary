/**
 * **The kept-alive NAVER test browser** (2026-10-19, operator): a dev-only Chromium that outlives the walk host.
 *
 * Every live walk launched its own persistent context and closed it on exit, and NAVER's login does not survive
 * a closed browser — 「로그인 상태 유지」 included (live, runs 7 and 8). A walk that starts with the session still
 * logged in therefore never happened live. This browser is started once, stays up between walks, and each walk
 * host ATTACHES to it over CDP and only DISCONNECTS when it stops — the seller's login stays where it was.
 *
 * Same binary, same profile, same keychain posture as {@link launchNaverContext} (bundled Chromium unless a
 * channel is configured; `--use-mock-keychain` is what Playwright passes on macOS, so a profile written by either
 * reads the same). Never `--enable-automation`, never headless. The debugging port binds loopback only, and no
 * `--remote-allow-origins` is passed: a web page cannot open the CDP socket, only a local process can.
 *
 * LIVE-ONLY to start; the pure parts (args, port screen) are unit-tested.
 */
import { chromium, type Browser, type BrowserContext } from "playwright";
import { resolveProfileDir } from "./profile";

export const TEST_BROWSER_HOST = "127.0.0.1";

/** A port the walk host may attach to: an integer in the unprivileged range, nothing else. */
export function parseTestBrowserPort(raw: string | undefined): number | null {
  if (raw === undefined || !/^\d{4,5}$/.test(raw.trim())) return null;
  const port = Number(raw.trim());
  return port >= 1024 && port <= 65535 ? port : null;
}

const REFUSED_ARGS = ["--enable-automation", "--headless", "--remote-allow-origins"];

/** The launch arguments. The profile dir passes the in-tree guard first; a refused flag throws. */
export function testBrowserArgs(profileDir: string, port: number, root?: string): string[] {
  const userDataDir = root === undefined ? resolveProfileDir(profileDir) : resolveProfileDir(profileDir, root);
  const args = [
    `--user-data-dir=${userDataDir}`,
    `--remote-debugging-address=${TEST_BROWSER_HOST}`,
    `--remote-debugging-port=${port}`,
    "--no-first-run",
    "--no-default-browser-check",
    "--use-mock-keychain",
    "about:blank",
  ];
  for (const a of args) {
    if (REFUSED_ARGS.some((r) => a.startsWith(r))) throw new Error("naver test browser: refused launch argument");
  }
  return args;
}

/** The executable for the configured channel: the installed Chrome for `chrome`, else Playwright's Chromium. */
export function testBrowserExecutable(channel: string | undefined): string {
  const trimmed = channel?.trim();
  if (!trimmed) return chromium.executablePath();
  if (trimmed === "chrome") return "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
  throw new Error("naver test browser: only the bundled Chromium or `chrome` channel is supported");
}

/** Is a CDP endpoint answering on this loopback port? Bounded; never throws. */
export async function testBrowserAnswers(port: number, timeoutMs = 1500): Promise<boolean> {
  try {
    const res = await fetch(`http://${TEST_BROWSER_HOST}:${port}/json/version`, { signal: AbortSignal.timeout(timeoutMs) });
    return res.ok;
  } catch {
    return false;
  }
}

/**
 * Attach to the kept-alive browser. `disconnect()` lets go WITHOUT closing it: for a CDP-connected browser,
 * `browser.close()` drops the connection and the contexts this side created, and the browser's own default
 * context — the one with the seller's login — stays open.
 */
export async function attachTestBrowser(port: number): Promise<{ context: BrowserContext; disconnect: () => Promise<void> }> {
  const browser: Browser = await chromium.connectOverCDP(`http://${TEST_BROWSER_HOST}:${port}`);
  const context = browser.contexts()[0];
  if (!context) {
    await browser.close().catch(() => undefined);
    throw new Error("naver test browser: no default context to attach to");
  }
  return { context, disconnect: () => browser.close() };
}
