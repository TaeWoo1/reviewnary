/**
 * Where the walk's last button is allowed to send the seller.
 *
 * This navigation is triggered by a button on a MARKETPLACE page, against a destination read from operator
 * configuration — so it is screened like the WING landing is, and refuses rather than guesses.
 */
import { describe, it, expect } from "vitest";
import {
  SELLEROPS_COUPANG_CONNECT_PATH,
  SELLEROPS_ISSUANCE_RESUME_QUERY,
  SELLEROPS_NAVER_CONNECT_PATH,
  SELLEROPS_WALKTHROUGH_RUN_QUERY,
  isSellerOpsReturnUrl,
  screenSellerOpsReturnUrl,
} from "../../src/cli/sellerops-return-url";

describe("screenSellerOpsReturnUrl", () => {
  it("accepts the local SellerOps UI and returns the connect route", () => {
    expect(screenSellerOpsReturnUrl("http://localhost:5173")).toEqual({
      ok: true,
      url: `http://localhost:5173${SELLEROPS_COUPANG_CONNECT_PATH}?${SELLEROPS_ISSUANCE_RESUME_QUERY}`,
    });
  });

  it("accepts the other spelling of loopback, and a non-default port", () => {
    expect(screenSellerOpsReturnUrl("http://127.0.0.1:4173")).toEqual({
      ok: true,
      url: `http://127.0.0.1:4173${SELLEROPS_COUPANG_CONNECT_PATH}?${SELLEROPS_ISSUANCE_RESUME_QUERY}`,
    });
  });

  it("**keeps only the ORIGIN** — nothing configured can ride along in the destination", () => {
    // A path, a query and a fragment on the configured value are all dropped. The route is ours to decide.
    expect(screenSellerOpsReturnUrl("http://localhost:5173/somewhere?next=x#y")).toEqual({
      ok: true,
      url: `http://localhost:5173${SELLEROPS_COUPANG_CONNECT_PATH}?${SELLEROPS_ISSUANCE_RESUME_QUERY}`,
    });
  });

  it("**refuses any host that is not this machine**", () => {
    for (const off of ["http://example.com", "https://wing.coupang.com", "http://localhost.evil.com", "http://192.168.1.9:5173"]) {
      expect(screenSellerOpsReturnUrl(off), off).toEqual({ ok: false, reason: "NOT_LOOPBACK" });
    }
  });

  it("refuses a non-http scheme — a browser window is not something to point at file: or javascript:", () => {
    expect(screenSellerOpsReturnUrl("file:///Users/someone/x.html").ok).toBe(false);
    expect(screenSellerOpsReturnUrl("javascript:alert(1)")).toEqual({ ok: false, reason: "NOT_HTTP" });
  });

  it("refuses an empty or unparseable value rather than defaulting to something", () => {
    expect(screenSellerOpsReturnUrl(undefined)).toEqual({ ok: false, reason: "EMPTY" });
    expect(screenSellerOpsReturnUrl("   ")).toEqual({ ok: false, reason: "EMPTY" });
    expect(screenSellerOpsReturnUrl("not a url")).toEqual({ ok: false, reason: "UNPARSEABLE" });
  });

  it("its refusal reasons carry no URL and no host — they are logged", () => {
    const res = screenSellerOpsReturnUrl("https://wing.coupang.com/secret-path");
    expect(res.ok).toBe(false);
    expect(JSON.stringify(res)).not.toContain("coupang");
    expect(JSON.stringify(res)).not.toContain("secret-path");
  });
});

describe("screenSellerOpsReturnUrl — the NAVER walk returns to the NAVER screen (live-found 2026-10-17)", () => {
  it("**the NAVER walk's return is the NAVER connect route, never Coupang's**", () => {
    expect(screenSellerOpsReturnUrl("http://localhost:5173/x?y=1", "naver")).toEqual({
      ok: true,
      url: `http://localhost:5173${SELLEROPS_NAVER_CONNECT_PATH}?${SELLEROPS_ISSUANCE_RESUME_QUERY}`,
    });
    expect(SELLEROPS_NAVER_CONNECT_PATH).toBe("/connect/naver");
  });

  it("the same refusals hold for the NAVER route", () => {
    expect(screenSellerOpsReturnUrl("http://example.com", "naver")).toEqual({ ok: false, reason: "NOT_LOOPBACK" });
    expect(screenSellerOpsReturnUrl("", "naver")).toEqual({ ok: false, reason: "EMPTY" });
  });

  it("the launcher's re-screen accepts exactly the two screened routes", () => {
    expect(isSellerOpsReturnUrl(`http://localhost:5173/connect/naver?${SELLEROPS_ISSUANCE_RESUME_QUERY}`)).toBe(true);
    expect(isSellerOpsReturnUrl(`http://localhost:5173/connect/coupang?${SELLEROPS_ISSUANCE_RESUME_QUERY}`)).toBe(true);
    // The marker-less route is no longer what the screen produces, so the launcher refuses it too.
    expect(isSellerOpsReturnUrl("http://localhost:5173/connect/naver")).toBe(false);
    expect(isSellerOpsReturnUrl("http://localhost:5173/connect/naver?next=x")).toBe(false);
    expect(isSellerOpsReturnUrl("http://example.com/connect/naver")).toBe(false);
  });
});

describe("the NAVER carrier asks for the NAVER route", () => {
  it("`naverReturnToSellerOps` screens with channel naver, and both NAVER entrypoints use it", async () => {
    const { readFileSync } = await import("node:fs");
    const agent = readFileSync(new URL("../../src/cli/local-agent.ts", import.meta.url), "utf8");
    const start = agent.indexOf("export async function naverReturnToSellerOps(");
    const end = agent.indexOf("\nexport ", start + 1);
    expect(start).toBeGreaterThan(-1);
    expect(end).toBeGreaterThan(start);
    const body = agent.slice(start, end);
    expect(body).toContain('screenSellerOpsReturnUrl(loadConfig().appUrl, "naver", walkthroughRunId)');
    expect(agent).toContain("returnToSellerOps: naverReturnToSellerOps,");
    const harness = readFileSync(new URL("../../instruments/live-runs/run-api-issuance-live-naver.ts", import.meta.url), "utf8");
    // The live harness returns to ITS bootstrap run; the product carrier above passes no run at all.
    expect(harness).toContain("returnToSellerOps: () => naverReturnToSellerOps(process.env.WALKTHROUGH_RUN_ID)");
  });
});

describe("a NAVER local proof returns to its own bound walk tab (run 9, 2026-10-19)", () => {
  const RUN = "wt-2b54572d84c8";

  it("**carries the bootstrap run, so the walk frontend opens instead of refusing with MISSING_URL_RUN**", () => {
    expect(screenSellerOpsReturnUrl("http://localhost:5173/x?y=1", "naver", RUN)).toEqual({
      ok: true,
      url: `http://localhost:5173${SELLEROPS_NAVER_CONNECT_PATH}?${SELLEROPS_WALKTHROUGH_RUN_QUERY}=${RUN}&${SELLEROPS_ISSUANCE_RESUME_QUERY}`,
    });
    expect(SELLEROPS_WALKTHROUGH_RUN_QUERY).toBe("walkthroughRun");
  });

  it("refuses any run that is not bootstrap's shape, rather than carrying it", () => {
    for (const bad of ["", "wt-2B54572D84C8", "wt-2b54572d84c", "wt-2b54572d84c8&next=x", "x wt-2b54572d84c8", "../wt-2b54572d84c8"]) {
      expect(screenSellerOpsReturnUrl("http://localhost:5173", "naver", bad), bad).toEqual({ ok: false, reason: "BAD_WALKTHROUGH_RUN" });
    }
  });

  it("the Coupang route and the run-less NAVER route are unchanged", () => {
    expect(screenSellerOpsReturnUrl("http://localhost:5173", "coupang", RUN)).toEqual(screenSellerOpsReturnUrl("http://localhost:5173"));
    expect(screenSellerOpsReturnUrl("http://localhost:5173", "naver")).toEqual({
      ok: true,
      url: `http://localhost:5173${SELLEROPS_NAVER_CONNECT_PATH}?${SELLEROPS_ISSUANCE_RESUME_QUERY}`,
    });
  });

  it("the launcher's re-screen accepts the carried run and nothing riding beside it", () => {
    expect(isSellerOpsReturnUrl(`http://localhost:5173/connect/naver?walkthroughRun=${RUN}&issuance=resume`)).toBe(true);
    expect(isSellerOpsReturnUrl(`http://localhost:5173/connect/naver?walkthroughRun=${RUN}`)).toBe(false);
    expect(isSellerOpsReturnUrl(`http://localhost:5173/connect/naver?walkthroughRun=${RUN}&next=x`)).toBe(false);
    expect(isSellerOpsReturnUrl(`http://localhost:5173/connect/naver?walkthroughRun=${RUN}&issuance=resume&next=x`)).toBe(false);
    expect(isSellerOpsReturnUrl("http://localhost:5173/connect/naver?walkthroughRun=wt-bad")).toBe(false);
    expect(isSellerOpsReturnUrl(`http://localhost:5173/connect/coupang?walkthroughRun=${RUN}`)).toBe(false);
    expect(isSellerOpsReturnUrl(`http://example.com/connect/naver?walkthroughRun=${RUN}`)).toBe(false);
  });
});
