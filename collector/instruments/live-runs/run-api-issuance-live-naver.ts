/**
 * Live, GATED, human-attended NAVER **API-issuance guidance** entrypoint (ISOLATED, v2 — READ-ONLY guidance).
 *
 *   set -a && . ./.env && set +a          # NAVER_API_CENTER_URL (operator-owned; never logged)
 *   npx tsx instruments/live-runs/run-api-issuance-live-naver.ts -- --i-understand-this-opens-live-naver
 *
 * The ONLY entrypoint that fills the issuance carrier's `createDriver` with the REAL
 * {@link NaverIssuanceDriver} (the default/dev boot stays synthetic — `IssuanceFixtureDriver`). It opens the
 * seller's dedicated Chrome window on the API-center page and hosts ONE issuance guidance run over the
 * existing authenticated `/bridge/ws` carrier, so the SellerOps FE attaches and drives the guided walk. It
 * NEVER logs in, clicks, types, submits, creates an application, selects a group, or reads a credential value
 * — the SELLER performs every real step, and SellerOps only reads a sanitized page category, highlights the
 * one next control read-only, and observes the seller's own click.
 *
 * Gating mirrors `observe-api-center` and `run-reply-submission-live-naver`:
 *   - refuses without `--i-understand-this-opens-live-naver` (`hasLiveRunApproval`);
 *   - reads the operator-owned `NAVER_API_CENTER_URL` (never logged) and `screenApiCenterUrl`-fail-closed
 *     BEFORE launching Chrome, so the browser only ever opens the API-center / auth host;
 *   - always closes the context — or, attached to the kept-alive test browser (`SELLEROPS_NAVER_TEST_BROWSER_PORT`,
 *     `naver-test-browser.ts`), only disconnects from it, so the seller's NAVER login outlives the walk.
 *
 * ⚠ This program NEVER affirms the flag — building/verifying is offline and hermetic, and `main()` runs ONLY
 * when invoked directly (inert on import) so tests launch nothing. Current standing state: NAVER live work is
 * PAUSED — do not run this without a fresh, single-use, in-turn operator approval.
 */
import { randomUUID } from "node:crypto";
import { dirname, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import type { Page } from "playwright";
import { loadConfig } from "../../src/config";
import { log } from "../../src/log";
import { launchNaverContext } from "../../src/profile";
import { attachTestBrowser, parseTestBrowserPort, testBrowserAnswers } from "../../src/naver-test-browser";
import { NaverIssuanceDriver } from "../../src/action-window/naver-issuance-driver";
import { createAgentBridge, type AgentBridge } from "../../src/agent/agent-bridge";
import {
  createApprovalPresenterFor,
  decideApprovalPresenter,
  naverCallIpSource,
  naverReturnToSellerOps,
  resolveAgentBridgeConfig,
} from "../../src/cli/local-agent";
import { screenApiCenterUrl } from "../../src/cli/observe-api-center";
import { approvalRequiredMessage, hasLiveRunApproval } from "../../src/cli/live-run-approval";
import { captureAfterPanels, createMaskedCapturer, ensureCaptureDir, screenCaptureDir, type MaskedCapturer } from "./masked-capture";

const CHANNEL_CODE = "naver";

function banner(): void {
  const line = "─".repeat(64);
  console.error(line);
  console.error(" LIVE NAVER API-issuance GUIDED walk — explicit per-run approval required.");
  console.error(" Read-only guidance: the SELLER logs in, opens/creates the API application, adds the API");
  console.error(" group, and copies the Client ID/Secret MANUALLY. This tool never logs in, clicks, types,");
  console.error(" submits, creates, selects, autofills, or reads any value (incl. Client ID/Secret) — it only");
  console.error(" reads a SANITIZED page category, highlights the next control read-only, and OBSERVES.");
  console.error(line);
}

/** Mint an opaque run id in the same shape the synthetic boot uses (`run_<12 hex>`). */
function mintIssuanceRunId(): string {
  return `run_${randomUUID().replace(/-/g, "").slice(0, 12)}`;
}

/** Block until the operator stops the run (SIGINT/SIGTERM), then close the bridge. */
async function waitForShutdown(bridge: AgentBridge): Promise<void> {
  await new Promise<void>((resolve) => {
    const stop = (): void => resolve();
    process.on("SIGINT", stop);
    process.on("SIGTERM", stop);
  });
  await bridge.close().catch(() => undefined);
}

async function main(): Promise<void> {
  banner();
  const args = process.argv.slice(2);
  if (!hasLiveRunApproval(args)) {
    console.error(approvalRequiredMessage());
    process.exit(3);
    return;
  }
  const url = process.env.NAVER_API_CENTER_URL;
  if (!url) {
    console.error("Set NAVER_API_CENTER_URL (operator-owned; never logged) to the API-center page first.");
    process.exit(2);
    return;
  }
  // Fail closed BEFORE launching Chrome: reject placeholders, unparseable URLs, and off-target hosts, so the
  // browser only ever opens the API-center / auth host (the raw URL is never printed — only a reason enum).
  const screen = screenApiCenterUrl(url);
  if (!screen.ok) {
    console.error(
      `Refusing to launch: NAVER_API_CENTER_URL failed screening (reason=${screen.reason}). It must be the ` +
        "NAVER API-center or auth host and not a placeholder. No browser launched.",
    );
    process.exit(2);
    return;
  }

  // The kept-alive test browser, when named: attach, never launch beside it (the profile is locked by it) and
  // never fall back to a launch — a walk that silently got a fresh browser would start logged out again.
  const attachRaw = process.env.SELLEROPS_NAVER_TEST_BROWSER_PORT;
  const attachPort = attachRaw === undefined ? null : parseTestBrowserPort(attachRaw);
  if (attachRaw !== undefined && (attachPort === null || !(await testBrowserAnswers(attachPort)))) {
    console.error("Refusing: SELLEROPS_NAVER_TEST_BROWSER_PORT names no running test browser. No browser launched.");
    process.exit(2);
    return;
  }

  const cfg = loadConfig();
  const attached = attachPort === null ? null : await attachTestBrowser(attachPort);
  const ctx = attached?.context ?? (await launchNaverContext(cfg.profileDir, cfg.browserChannel));
  log("aw_issuance_browser", { mode: attached ? "attached_test_browser" : "launched" });
  try {
    // Attached, the walk gets a tab of its own: the newest tab is the one the driver works on, and the seller's
    // earlier tabs are theirs.
    const page = (attached ? await ctx.newPage() : (ctx.pages()[0] ?? (await ctx.newPage()))) as Page;
    await page.goto(url, { waitUntil: "domcontentloaded" });

    const runId = mintIssuanceRunId();
    // The call-IP step shows the address this machine's backend reports — the same source the resident helper uses.
    const driver = new NaverIssuanceDriver(page, { context: ctx, callIps: naverCallIpSource(cfg), returnToSellerOps: () => naverReturnToSellerOps(process.env.WALKTHROUGH_RUN_ID) });
    // Opt-in dev instrument (2026-10-18): masked screenshots after each panel, for a UX pass on the real walk.
    // Off unless SELLEROPS_MASKED_CAPTURE_DIR names an absolute folder OUTSIDE the repository.
    let capturer: MaskedCapturer | null = null;
    if (process.env.SELLEROPS_MASKED_CAPTURE_DIR !== undefined) {
      const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../../..");
      const dir = screenCaptureDir(process.env.SELLEROPS_MASKED_CAPTURE_DIR, repoRoot);
      if (!dir) {
        console.error("Refusing masked captures: SELLEROPS_MASKED_CAPTURE_DIR must be absolute and outside the repository.");
        process.exit(2);
        return;
      }
      ensureCaptureDir(dir);
      capturer = createMaskedCapturer(() => {
        const pages = ctx.pages();
        return pages[pages.length - 1] as Page | undefined;
      }, dir);
      captureAfterPanels(driver, capturer);
      log("aw_masked_capture_armed", {});
    }
    const bridge = createAgentBridge({
      ...resolveAgentBridgeConfig(args, process.env),
      approvalPresenter: createApprovalPresenterFor(decideApprovalPresenter(process.env, process.platform)),
      apiIssuance: { runId, channelCode: CHANNEL_CODE, createDriver: () => driver },
    });
    const listen = await bridge.listen();
    // Sanitized: the bridge listen result + the opaque runId + channel enum. No URL / value / secret.
    console.log(JSON.stringify({ event: "ISSUANCE_BRIDGE", ...listen, runId, channelCode: CHANNEL_CODE }));
    log("aw_issuance_live_hosted", { runId, channelCode: CHANNEL_CODE });
    await waitForShutdown(bridge);
    capturer?.stop();
  } finally {
    if (attached) await attached.disconnect();
    else await ctx.close();
  }
}

// Run the live path ONLY when invoked directly (never on import) so hermetic tests launch nothing.
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) void main();
