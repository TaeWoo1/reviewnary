// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createAnalytics } from "./analytics";
import { createHackleSink, type HackleSdk } from "./hackleSink";

/**
 * The REAL @hackler/javascript-sdk 12.2.1 browser build, offline (2026-11-07). Vitest resolves the package's `node`
 * export (a different SDK), so the browser file Vite bundles is imported by path. Nothing leaves the process:
 * `XMLHttpRequest` (the SDK's only transport here) and `sendBeacon` are replaced by recorders, and the SDK's hosts are
 * pointed at a closed local port as well. What is checked is what each request would carry — the browser check in the
 * test environment still has to confirm the hosts and the wire.
 */
const BROWSER_BUILD = `${process.cwd()}/node_modules/@hackler/javascript-sdk/lib/index.browser.es.js`;
const KEY = "hk_offline_test_key_01";
const OLD = "uuid-old-visitor";
const NOWHERE = { sdkUrl: "http://127.0.0.1:9", eventUrl: "http://127.0.0.1:9", monitoringUrl: "http://127.0.0.1:9" };
const GRANTED = { analytics: true, marketing: false };

type Sent = { path: string; body: string };
let sent: Sent[] = [];

class RecordingXhr {
  static readonly DONE = 4;
  readonly DONE = 4;
  readyState = 0;
  status = 0;
  responseText = "";
  response = "";
  timeout = 0;
  onreadystatechange: null | (() => void) = null;
  private url = "";
  open(_method: string, url: string) {
    this.url = url;
  }
  setRequestHeader() {}
  getAllResponseHeaders() {
    return "";
  }
  send(body: unknown) {
    sent.push({ path: new URL(this.url).pathname, body: String(body ?? "") });
    setTimeout(() => {
      this.readyState = 4;
      this.status = 200;
      this.onreadystatechange?.();
    }, 0);
  }
  abort() {}
}

/** A fresh module instance = a fresh page load; cookies (where the SDK keeps the previous visitor) stay in jsdom. */
async function pageLoad(): Promise<HackleSdk> {
  vi.resetModules();
  return (await import(/* @vite-ignore */ BROWSER_BUILD)) as unknown as HackleSdk;
}

/** The SDK as the sink sees it, but sending nowhere; records the config the sink asked for. */
function offline(sdk: HackleSdk, asked: Array<Record<string, unknown> | undefined>): HackleSdk {
  return { createInstance: (key, config) => (asked.push(config), sdk.createInstance(key, { ...config, ...NOWHERE })) };
}

const userLookups = () => sent.filter((s) => s.path === "/api/v1/cohorts" || s.path === "/api/v1/user-targets");
const carries = (id: string) => sent.some((s) => s.body.includes(id));

beforeEach(async () => {
  sent = [];
  for (const c of document.cookie.split(";")) document.cookie = `${c.split("=")[0].trim()}=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/`;
  localStorage.clear();
  vi.stubGlobal("XMLHttpRequest", RecordingXhr);
  Object.defineProperty(navigator, "sendBeacon", {
    configurable: true,
    value: (url: string, body: unknown) => (sent.push({ path: new URL(url).pathname, body: String(body) }), true),
  });
  vi.spyOn(console, "log").mockImplementation(() => {}); // the SDK logs through console.log
  // an earlier visit by OLD on this browser, made with the real SDK
  const earlier = await pageLoad();
  const c = earlier.createInstance(KEY, { ...NOWHERE, optOutTracking: true, user: { userId: OLD } });
  await c.onInitialized?.({ timeout: 2000 });
  expect(carries(OLD)).toBe(true); // the seed really is stored and sent by that visit
  sent = [];
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("Hackle sink × the real 12.2.1 SDK — no previous visitor in any request (2026-11-07)", () => {
  it("control: created WITHOUT a user, 12.2.1 restores the previous visitor and sends their id to sdk-api", async () => {
    const sdk = await pageLoad();
    const c = sdk.createInstance(KEY, { ...NOWHERE, optOutTracking: true });
    await c.onInitialized?.({ timeout: 2000 });
    expect(userLookups().length).toBeGreaterThan(0);
    expect(userLookups().every((s) => s.body.includes(OLD))).toBe(true);
  });

  it("a signed-out visitor: the sink creates it with `{}`, and no request — lookup or event — carries the previous id", async () => {
    const asked: Array<Record<string, unknown> | undefined> = [];
    const a = createAnalytics(() => {});
    a.init([createHackleSink(KEY, { Hackle: offline(await pageLoad(), asked) })]);
    a.setConsent(GRANTED);
    a.track("today_inbox_viewed"); // auth still loading: not created yet
    expect(asked).toEqual([]);
    expect(sent).toEqual([]);
    a.identify(null);
    expect(asked.map((c) => c?.user)).toEqual([{}]);
    await vi.waitFor(() => expect(sent.some((s) => s.path === "/api/v2/events")).toBe(true), { timeout: 3000 });
    expect(userLookups().length).toBeGreaterThan(0);
    expect(carries(OLD)).toBe(false);
    expect(sent.find((s) => s.path === "/api/v2/events")!.body).toContain("today_inbox_viewed");
    // and the next page load does not bring the previous visitor back either
    sent = [];
    const next = await pageLoad();
    const c = next.createInstance(KEY, { ...NOWHERE, optOutTracking: true });
    await c.onInitialized?.({ timeout: 2000 });
    expect(carries(OLD)).toBe(false);
  });

  it("a signed-in visitor: the lookups carry the current id, never the previous one", async () => {
    const a = createAnalytics(() => {});
    a.init([createHackleSink(KEY, { Hackle: offline(await pageLoad(), []) })]);
    a.setConsent(GRANTED);
    a.identify("uuid-now-visitor");
    await vi.waitFor(() => expect(userLookups().length).toBeGreaterThan(0), { timeout: 3000 });
    expect(userLookups().every((s) => s.body.includes("uuid-now-visitor"))).toBe(true);
    expect(carries(OLD)).toBe(false);
  });
});
