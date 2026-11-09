// @vitest-environment jsdom
import { afterAll, beforeAll, describe, expect, it, vi } from "vitest";
import { createAnalytics } from "./analytics";
import { createHackleSink, type HackleSdk } from "./hackleSink";

/**
 * <b>What the SDK attaches to every event by itself</b> (2026-11-07) — the real @hackler/javascript-sdk 12.2.1 browser
 * build behind the real sink, offline (same harness as `hackleSink.realSdk.test.ts`: `XMLHttpRequest` and `sendBeacon`
 * are recorders, the hosts point at a closed local port). The page carries a made-up order number, search term, hash,
 * referrer and title; the test pins that they all reach the event body. This is a characterization, not a wish: it
 * documents the collection scope `hackleSink.ts` describes, and fails if an SDK upgrade changes it.
 *
 * 12.2.1 builds `hackleProperties` from `window.location` / `document` at each event (`HacklePropertyGenerator` →
 * `BrowserPropertyGenerator` · `DevicePropertyGenerator`) and turns `utm_*` / `gclid` / `fbclid` into user properties
 * (`CampaignParser`). Its `Config` has no option to leave any of them out.
 */
const BROWSER_BUILD = `${process.cwd()}/node_modules/@hackler/javascript-sdk/lib/index.browser.es.js`;
const NOWHERE = { sdkUrl: "http://127.0.0.1:9", eventUrl: "http://127.0.0.1:9", monitoringUrl: "http://127.0.0.1:9" };

const FAKE_ORDER = "FAKE-ORDER-0001";
const FAKE_SEARCH = "FAKE-SEARCH-가상고객";
const FAKE_REFERRER = "https://ref.example.test/path?rq=FAKE-REF-QUERY";

type Sent = { url: string; body: string };
const sent: Sent[] = [];

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
    sent.push({ url: this.url, body: String(body ?? "") });
    setTimeout(() => {
      this.readyState = 4;
      this.status = 200;
      this.onreadystatechange?.();
    }, 0);
  }
  abort() {}
}

const wait = (ms: number) => new Promise((r) => setTimeout(r, ms));

type WireEvent = {
  eventTypeKey: string;
  hackleProperties: Record<string, unknown>;
  userProperties: Record<string, unknown>;
  properties: Record<string, unknown>;
};

function wireEvents(): WireEvent[] {
  return sent
    .filter((s) => new URL(s.url).pathname === "/api/v2/events")
    .flatMap((s) => (JSON.parse(s.body).trackEvents ?? []) as WireEvent[]);
}

describe("Hackle SDK 12.2.1 — page context attached to every event (offline)", () => {
  const realXhr = globalThis.XMLHttpRequest;
  const realFetch = globalThis.fetch;
  beforeAll(() => {
    vi.stubGlobal("XMLHttpRequest", RecordingXhr);
    vi.stubGlobal("fetch", () => Promise.reject(new Error("offline")));
    Object.defineProperty(navigator, "sendBeacon", {
      configurable: true,
      value: (url: string) => (sent.push({ url, body: "" }), true),
    });
  });
  afterAll(() => {
    vi.unstubAllGlobals();
    globalThis.XMLHttpRequest = realXhr;
    globalThis.fetch = realFetch;
  });

  it("the URL path, query, hash, referrer and title go out verbatim; utm_* becomes a user property", async () => {
    window.history.replaceState(
      {},
      "",
      `/orders/NAVER/FAKE-ACCOUNT/${FAKE_ORDER}?q=${encodeURIComponent(FAKE_SEARCH)}&utm_source=fake_src#fake-hash`,
    );
    document.title = "FAKE-TITLE";
    Object.defineProperty(document, "referrer", { value: FAKE_REFERRER, configurable: true });

    const sdk = (await import(/* @vite-ignore */ BROWSER_BUILD)) as unknown as HackleSdk;
    const offline: HackleSdk = { createInstance: (key, config) => sdk.createInstance(key, { ...config, ...NOWHERE }) };
    const a = createAnalytics(() => {});
    a.init([createHackleSink("hk_offline_payload_key", { Hackle: offline })]);
    a.setConsent({ analytics: true, marketing: false });
    a.identify("uuid-synthetic");
    await wait(300);
    a.track("today_inbox_viewed", {});
    await wait(1200);

    // nothing left for a real host
    expect(sent.length).toBeGreaterThan(0);
    for (const s of sent) expect(s.url.startsWith("http://127.0.0.1:9")).toBe(true);

    const event = wireEvents().find((e) => e.eventTypeKey === "today_inbox_viewed");
    expect(event, "the typed event reached the wire").toBeDefined();
    const hp = event!.hackleProperties;
    // what this sink sent: no properties of its own
    expect(event!.properties).toEqual({});
    // what the SDK added
    expect(hp.pagePath).toBe(`/orders/NAVER/FAKE-ACCOUNT/${FAKE_ORDER}`);
    expect(String(hp.url)).toContain(FAKE_ORDER);
    expect(String(hp.url)).toContain(`q=${encodeURIComponent(FAKE_SEARCH)}`);
    expect(String(hp.url)).toContain("#fake-hash");
    expect(decodeURIComponent(String(hp.queryParameter))).toContain(`q=${FAKE_SEARCH}`);
    expect(hp.referrer).toBe(FAKE_REFERRER);
    expect(hp.pageTitle).toBe("FAKE-TITLE");
    expect(Object.keys(hp)).toEqual(
      expect.arrayContaining([
        "platform", "browserName", "browserVersion", "userAgent", "host", "protocol",
        "osName", "deviceType", "screenWidth", "screenHeight", "locale", "language", "timeZone",
      ]),
    );
    expect(event!.userProperties).toMatchObject({ utm_source: "fake_src" });
  });
});
