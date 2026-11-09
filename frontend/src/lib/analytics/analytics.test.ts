// @vitest-environment jsdom
import { describe, expect, it, vi } from "vitest";
import { createAnalytics } from "./analytics";
import { ANALYTICS_EVENT_NAMES, analyticsChannel, sanitize } from "./events";
import { createGtmSink, isValidGtmId } from "./gtmSink";
import { createPosthogSink, POSTHOG_INIT_OPTIONS, type PosthogLike } from "./posthogSink";
import {
  createHackleSink,
  HACKLE_CONFIG,
  HACKLE_MAX_WAITING,
  HACKLE_INIT_TIMEOUT_MS,
  HACKLE_ORIGINS,
  type HackleClientLike,
  type HackleSdk,
  type HackleWindow,
} from "./hackleSink";
import { initialConsent, sinksFromEnv } from "./index";
import { buildCsp } from "../security/csp";
import type { AnalyticsSink, ConsentGrant } from "./sink";

const GRANTED: ConsentGrant = { analytics: true, marketing: false };

function recordingSink(name = "rec"): AnalyticsSink & { calls: unknown[]; started: number } {
  const sink = {
    name,
    calls: [] as unknown[],
    started: 0,
    start() {
      sink.started += 1;
    },
    consent(grant: ConsentGrant) {
      sink.calls.push(["consent", grant]);
    },
    track(event: string, props: Record<string, string>) {
      sink.calls.push(["track", event, props]);
    },
    identify(userId: string | null) {
      sink.calls.push(["identify", userId]);
    },
  };
  return sink;
}

describe("analytics — one abstraction, env-gated, PII impossible by construction", () => {
  it("is OFF with no env: no sinks, track is a silent no-op", () => {
    expect(sinksFromEnv({})).toEqual([]);
    expect(sinksFromEnv({ VITE_GTM_ID: "", VITE_POSTHOG_KEY: " " })).toEqual([]);
    const a = createAnalytics(() => {});
    a.init([]);
    a.setConsent(GRANTED);
    expect(a.enabled).toBe(false);
    a.track("sign_up", { method: "email" });
    a.identify("u-1");
    expect(a.emitted).toEqual([{ event: "sign_up", props: { method: "email" } }]);
  });

  it("builds a GTM sink only for a container id and a PostHog sink only for a key", () => {
    expect(isValidGtmId("GTM-ABC123")).toBe(true);
    expect(isValidGtmId("G-12345")).toBe(false);
    expect(sinksFromEnv({ VITE_GTM_ID: "GTM-ABC123" }).map((s) => s.name)).toEqual(["gtm"]);
    expect(sinksFromEnv({ VITE_POSTHOG_KEY: "phc_x" }).map((s) => s.name)).toEqual(["posthog"]);
    expect(sinksFromEnv({ VITE_GTM_ID: "GTM-ABC123", VITE_POSTHOG_KEY: "phc_x" }).map((s) => s.name)).toEqual([
      "gtm",
      "posthog",
    ]);
  });

  it("fans one sanitized event out to every sink and starts them once, on consent", () => {
    const a = createAnalytics(() => {});
    const s1 = recordingSink("a");
    const s2 = recordingSink("b");
    a.init([s1, s2]);
    expect(s1.started).toBe(0);
    a.setConsent(GRANTED);
    expect(s1.started).toBe(1);
    a.track("channel_connected", { channel: "naver" });
    a.track("today_inbox_viewed");
    // the session is known and signed out — told once (a vendor may have restored someone from an earlier visit)
    a.identify(null);
    a.identify(null); // re-render, same identity
    a.identify("user-uuid");
    a.identify("user-uuid");
    a.identify(null);
    expect(s1.started).toBe(1);
    expect(s2.calls).toEqual([
      ["track", "channel_connected", { channel: "naver" }],
      ["track", "today_inbox_viewed", {}],
      ["identify", null],
      ["identify", "user-uuid"],
      ["identify", null],
    ]);
  });

  it("drops every non-allow-listed key and every non-enum value before any sink sees it", () => {
    const warn = vi.fn();
    const a = createAnalytics(warn);
    const s = recordingSink();
    a.init([s]);
    a.setConsent(GRANTED);
    a.track("sign_up", {
      method: "email",
      // everything below is what the contract forbids — none of it may leave the abstraction
      email: "seller@example.com",
      name: "홍길동",
      orgName: "우리 스토어",
      reviewText: "배송이 늦어요",
      accountId: "3f1c…",
    } as never);
    a.track("channel_connected", { channel: "SmartStore-account-42" } as never);
    a.track("login", { method: "kakao" } as never);
    expect(s.calls).toEqual([
      ["track", "sign_up", { method: "email" }],
      ["track", "channel_connected", {}],
      ["track", "login", {}],
    ]);
    expect(JSON.stringify(s.calls)).not.toMatch(/example\.com|홍길동|스토어|배송|3f1c/);
    expect(warn).toHaveBeenCalled();
  });

  it("refuses an event that is not in the vocabulary", () => {
    const warn = vi.fn();
    const a = createAnalytics(warn);
    const s = recordingSink();
    a.init([s]);
    a.setConsent(GRANTED);
    (a as unknown as { track: (e: string) => void }).track("page_view_with_url");
    expect(s.calls).toEqual([]);
    expect(warn).toHaveBeenCalledWith(expect.stringContaining("unknown event"));
  });

  it("trackOnce fires the same event+props once per page load", () => {
    const a = createAnalytics(() => {});
    const s = recordingSink();
    a.init([s]);
    a.setConsent(GRANTED);
    a.trackOnce("first_sync_completed", { channel: "coupang" });
    a.trackOnce("first_sync_completed", { channel: "coupang" });
    a.trackOnce("first_sync_completed", { channel: "naver" });
    expect(s.calls).toHaveLength(2);
  });

  it("a sink that throws never reaches product code", () => {
    const a = createAnalytics(() => {});
    a.init([
      {
        name: "broken",
        start() {
          throw new Error("vendor down");
        },
        track() {
          throw new Error("vendor down");
        },
        identify() {
          throw new Error("vendor down");
        },
      },
    ]);
    a.setConsent(GRANTED);
    expect(() => {
      a.track("inquiry_opened");
      a.identify("u");
    }).not.toThrow();
  });

  it("sanitize + channel mapping are total over the vocabulary", () => {
    for (const event of ANALYTICS_EVENT_NAMES) {
      expect(sanitize(event, { junk: "x" }).props).toEqual({});
    }
    expect(analyticsChannel("NAVER")).toBe("naver");
    expect(analyticsChannel("Cafe24")).toBe("cafe24");
    expect(analyticsChannel("ESM")).toBeNull();
    expect(analyticsChannel(undefined)).toBeNull();
  });
});

describe("analytics — consent (docs/standing/service_readiness_v1.md §2-4)", () => {
  it("buffers before a decision, then starts the sinks and flushes in order on grant", () => {
    const a = createAnalytics(() => {});
    const s = recordingSink();
    a.init([s]);
    a.identify("uuid-9");
    a.track("sign_up", { method: "email" });
    a.track("onboarding_completed");
    expect(s.started).toBe(0);
    expect(s.calls).toEqual([]);
    expect(a.started).toBe(false);
    a.setConsent({ analytics: true, marketing: true });
    expect(s.started).toBe(1);
    expect(s.calls).toEqual([
      ["identify", "uuid-9"],
      ["track", "sign_up", { method: "email" }],
      ["track", "onboarding_completed", {}],
    ]);
  });

  it("drops the buffer on refusal and never starts a sink; withdrawal after start tells the sink to stop", () => {
    const a = createAnalytics(() => {});
    const s = recordingSink();
    a.init([s]);
    a.track("today_inbox_viewed");
    a.setConsent({ analytics: false, marketing: false });
    a.track("inquiry_opened");
    expect(s.started).toBe(0);
    expect(s.calls).toEqual([]);
    a.setConsent(GRANTED);
    expect(s.started).toBe(1);
    a.setConsent({ analytics: false, marketing: false });
    expect(s.calls).toEqual([["consent", { analytics: false, marketing: false }]]);
    a.track("review_attention_opened");
    expect(s.calls).toHaveLength(1);
    a.setConsent({ analytics: true, marketing: true });
    // nobody was ever identified, so the re-grant hands the sink no identity
    expect(s.calls[s.calls.length - 1]).toEqual(["consent", { analytics: true, marketing: true }]);
    expect(s.started).toBe(1);
  });

  it("initial consent: not-applicable without vendors, stored decision or pending under the banner policy", () => {
    expect(initialConsent({}, null)).toEqual({ analytics: true, marketing: false });
    expect(initialConsent({ VITE_GTM_ID: "GTM-ABC123" }, null)).toBeNull();
    expect(initialConsent({ VITE_GTM_ID: "GTM-ABC123" }, { version: 1, analytics: true, marketing: false, decidedAt: "" }))
        .toEqual({ analytics: true, marketing: false });
    expect(initialConsent({ VITE_CONSENT_BANNER: "always" }, null)).toBeNull();
  });
});

describe("GTM sink", () => {
  it("pushes gtm.js once on start, then events and the opaque user id onto dataLayer", () => {
    const win = { dataLayer: undefined as unknown[] | undefined, document };
    const sink = createGtmSink("GTM-TEST1", win);
    sink.start!({ analytics: true, marketing: false });
    sink.start!({ analytics: true, marketing: false });
    sink.track("sign_up", { method: "google" });
    sink.identify("uuid-1");
    sink.consent!({ analytics: true, marketing: true });
    const layer = win.dataLayer!;
    // Consent Mode v2: default (all denied) then update, BEFORE gtm.js — as `arguments` objects, the gtag shape.
    const asArgs = (i: number) => Array.from(layer[i] as ArrayLike<unknown>);
    expect(asArgs(0)).toEqual(["consent", "default", {
      analytics_storage: "denied", ad_storage: "denied", ad_user_data: "denied", ad_personalization: "denied",
    }]);
    expect(asArgs(1)).toEqual(["consent", "update", {
      analytics_storage: "granted", ad_storage: "denied", ad_user_data: "denied", ad_personalization: "denied",
    }]);
    expect((layer[2] as { event: string }).event).toBe("gtm.js");
    expect(asArgs(layer.length - 1)).toEqual(["consent", "update", {
      analytics_storage: "granted", ad_storage: "granted", ad_user_data: "granted", ad_personalization: "granted",
    }]);
    expect(layer.filter((e) => (e as { event?: string }).event === "gtm.js")).toHaveLength(1);
    expect(layer).toContainEqual({ event: "sign_up", method: "google" });
    expect(layer).toContainEqual({ event: "sellerops_identify", user_id: "uuid-1" });
    expect(document.querySelectorAll('script[src^="https://www.googletagmanager.com/gtm.js?id=GTM-TEST1"]')).toHaveLength(1);
  });
});

describe("PostHog sink", () => {
  it("initialises with session replay, autocapture and pageview OFF, and identifies by opaque id only", () => {
    const calls: unknown[] = [];
    const ph: PosthogLike = {
      init: (k, o) => calls.push(["init", k, o]),
      capture: (e, p) => calls.push(["capture", e, p]),
      identify: (id) => calls.push(["identify", id]),
      reset: () => calls.push(["reset"]),
    };
    const sink = createPosthogSink("phc_key", "https://eu.i.posthog.com/", { posthog: ph });
    sink.start!({ analytics: true, marketing: false });
    sink.track("login", { method: "naver" });
    sink.identify("uuid-2");
    sink.identify(null);
    expect(calls[0]).toEqual([
      "init",
      "phc_key",
      { api_host: "https://eu.i.posthog.com", ...POSTHOG_INIT_OPTIONS },
    ]);
    expect(POSTHOG_INIT_OPTIONS.disable_session_recording).toBe(true);
    expect(POSTHOG_INIT_OPTIONS.autocapture).toBe(false);
    expect(calls.slice(1)).toEqual([["capture", "login", { method: "naver" }], ["identify", "uuid-2"], ["reset"]]);
  });

  it("queues calls until the script has loaded", () => {
    const win: { posthog?: PosthogLike; document: Document } = { document };
    const sink = createPosthogSink("phc_key", undefined, win);
    sink.track("today_inbox_viewed", {});
    sink.start!({ analytics: true, marketing: false });
    const script = document.querySelector('script[src="https://us.i.posthog.com/static/array.js"]') as HTMLScriptElement;
    expect(script).not.toBeNull();
    const calls: unknown[] = [];
    win.posthog = {
      init: (k, o) => calls.push(["init", k, o]),
      capture: (e, p) => calls.push(["capture", e, p]),
      identify: () => {},
      reset: () => {},
    };
    script.onload!(new Event("load"));
    expect(calls.map((c) => (c as unknown[])[0])).toEqual(["init", "capture"]);
  });
});


describe("analytics — identity across a withdrawal", () => {
  it("an identify while withdrawn reaches no sink; a re-grant hands the sinks the user signed in now", () => {
    const a = createAnalytics(() => {});
    const s = recordingSink();
    a.init([s]);
    a.setConsent(GRANTED);
    a.identify("uuid-a");
    a.setConsent({ analytics: false, marketing: false });
    a.identify(null);
    a.identify("uuid-b");
    expect(s.calls).toEqual([["identify", "uuid-a"], ["consent", { analytics: false, marketing: false }]]);
    a.setConsent(GRANTED);
    expect(s.calls.slice(2)).toEqual([["consent", GRANTED], ["identify", "uuid-b"]]);
  });
});


/** 12.2.1's rule for the user it starts with: `config.user || null`, then `initUser ?? previousUser`. */
function startingUser(config: Record<string, unknown> | undefined, restored: string | undefined) {
  const initUser = (config?.user as { userId?: string } | undefined) || null;
  return initUser ? initUser.userId : restored;
}

/** A fake 12.x client that logs every call; `init` decides when `onInitialized` resolves (absent = ready at once). */
function fakeHackle(calls: unknown[], opts: { init?: Promise<unknown>; reset?: () => Promise<unknown> } = {}) {
  const client: HackleClientLike = {
    track: (e) => calls.push(["track", e.key]),
    setUserId: (id) => calls.push(["setUserId", id]),
    resetUser: () => {
      calls.push(["resetUser"]);
      return opts.reset?.();
    },
    setOptOutTracking: (o) => calls.push(["optOut", o]),
  };
  if (opts.init) client.onInitialized = () => opts.init!;
  return client;
}

function deferred() {
  let resolve!: () => void;
  const promise = new Promise<void>((r) => (resolve = r));
  return { promise, resolve };
}

const flush = () => new Promise((r) => setTimeout(r, 0));

/** The SDK chunk, arriving when the test says so — the dynamic import's stand-in. */
function lateSdk() {
  let resolve!: (sdk: HackleSdk) => void;
  let reject!: (e: unknown) => void;
  const promise = new Promise<HackleSdk>((res, rej) => ((resolve = res), (reject = rej)));
  const state = {
    loads: 0,
    win: {
      load: () => {
        state.loads += 1;
        return promise;
      },
    } as HackleWindow,
    async arrive(sdk: HackleSdk) {
      resolve(sdk);
      await flush();
    },
    async fail() {
      reject(new Error("chunk failed"));
      await flush();
    },
  };
  return state;
}
const WITHDRAWN: ConsentGrant = { analytics: false, marketing: false };

describe("Hackle sink", () => {
  it("is built only for a key, after the other vendors", () => {
    expect(sinksFromEnv({ VITE_HACKLE_SDK_KEY: "hk_x" }).map((s) => s.name)).toEqual(["hackle"]);
    expect(sinksFromEnv({ VITE_POSTHOG_KEY: "phc_x", VITE_HACKLE_SDK_KEY: " " }).map((s) => s.name)).toEqual([
      "posthog",
    ]);
  });

  it("creates the client with every automatic collector and monitoring OFF, opted OUT until the user is in, and forwards typed events", () => {
    const calls: unknown[] = [];
    const created: unknown[] = [];
    const sink = createHackleSink("hk_key", {
      Hackle: { createInstance: (k, c) => (created.push([k, c]), fakeHackle(calls)) },
    });
    sink.track("login", { method: "naver" });
    sink.start!(GRANTED);
    expect(created).toEqual([]); // not created before the signed-in state is known
    expect(calls).toEqual([]);
    sink.identify(null);
    expect(HACKLE_CONFIG).toEqual({
      automaticPageTracking: false,
      auto_track_page_view: false,
      automaticRouteTracking: false,
      enableMonitoring: false,
      optOutTracking: true,
    });
    // created opted out WITH the signed-out user — `{}`, which the SDK takes over whatever it would restore
    expect(created).toEqual([["hk_key", { ...HACKLE_CONFIG, optOutTracking: true, user: {} }]]);
    // nothing to set: collection starts once initialised, then the event goes
    expect(calls).toEqual([["optOut", false], ["track", "login"]]);
  });

  it("forwards the event with its props and nothing before the SDK is initialised (12.x onInitialized)", async () => {
    const calls: unknown[] = [];
    const tracked: unknown[] = [];
    const init = deferred();
    const client = fakeHackle(calls, { init: init.promise });
    client.track = (e) => tracked.push(e);
    const sink = createHackleSink("hk_key", { Hackle: { createInstance: () => client } });
    sink.start!(GRANTED);
    sink.track("login", { method: "naver" });
    sink.identify("uuid-3");
    expect(calls).toEqual([]);
    expect(tracked).toEqual([]);
    init.resolve();
    await flush();
    expect(calls).toEqual([["optOut", false]]); // the user went in at creation
    expect(tracked).toEqual([{ key: "login", properties: { method: "naver" } }]);
  });

  it("loads the SDK lazily — only on start — and stops forwarding when 분석 consent is withdrawn", async () => {
    const sdk = lateSdk();
    const sink = createHackleSink("hk_key", sdk.win);
    expect(sdk.loads).toBe(0);
    sink.start!(GRANTED);
    sink.start!(GRANTED);
    expect(sdk.loads).toBe(1);
    const calls: unknown[] = [];
    await sdk.arrive({ createInstance: () => ({ track: (e) => calls.push(e) }) });
    sink.identify("uuid-1");
    sink.track("today_inbox_viewed", {});
    sink.consent!(WITHDRAWN);
    sink.track("today_inbox_viewed", {});
    expect(calls).toHaveLength(1);
  });

  it("consent → withdraw → re-grant: the SDK itself is opted out (id cleared) and back in", () => {
    const calls: unknown[] = [];
    const sink = createHackleSink("hk_key", { Hackle: { createInstance: () => fakeHackle(calls) } });
    sink.start!(GRANTED);
    sink.identify("uuid-1");
    sink.track("today_inbox_viewed", {});
    sink.consent!(WITHDRAWN);
    sink.identify("uuid-2");
    sink.track("today_inbox_viewed", {});
    sink.consent!({ analytics: false, marketing: true });
    expect(calls).toEqual([["optOut", false], ["track", "today_inbox_viewed"], ["optOut", true], ["resetUser"]]);
    sink.consent!(GRANTED);
    sink.track("today_inbox_viewed", {});
    // the re-grant alone opts nothing in and brings no id back; the event waits — the analytics layer re-tells the user
    expect(calls.slice(4)).toEqual([]);
    // the id given while withdrawn was not kept, so being told it now sets it, then opts in, ahead of the event
    sink.identify("uuid-2");
    expect(calls.slice(4)).toEqual([["setUserId", "uuid-2"], ["optOut", false], ["track", "today_inbox_viewed"]]);
  });

  it("withdrawn while the SDK chunk downloads: the SDK is never created, and the event that waited is never sent", async () => {
    const sdk = lateSdk();
    const sink = createHackleSink("hk_late", sdk.win);
    sink.start!(GRANTED);
    sink.identify("uuid-9");
    sink.track("today_inbox_viewed", {});
    sink.consent!(WITHDRAWN);
    const created: unknown[] = [];
    const calls: unknown[] = [];
    await sdk.arrive({ createInstance: (k, c) => (created.push([k, c]), fakeHackle(calls)) });
    expect(created).toEqual([]);
    sink.consent!(GRANTED);
    expect(created).toEqual([]); // the user given before the withdrawal was not kept: wait to be told again
    sink.identify("uuid-9");
    expect(created).toEqual([["hk_late", { ...HACKLE_CONFIG, optOutTracking: true, user: { userId: "uuid-9" } }]]);
    expect(calls).toEqual([["optOut", false]]);
  });

  it("withdrawn after the client is created but before it is initialised: opted out at once, nothing that waited is sent", async () => {
    const calls: unknown[] = [];
    const init = deferred();
    const sink = createHackleSink("hk_key", {
      Hackle: { createInstance: () => fakeHackle(calls, { init: init.promise }) },
    });
    sink.start!(GRANTED);
    sink.identify("uuid-1");
    sink.track("today_inbox_viewed", {});
    sink.consent!(WITHDRAWN);
    expect(calls).toEqual([["optOut", true]]);
    init.resolve();
    await flush();
    expect(calls).toEqual([["optOut", true], ["resetUser"]]);
    sink.consent!(GRANTED);
    sink.identify("uuid-1");
    sink.track("inquiry_opened", {});
    expect(calls.slice(2)).toEqual([["setUserId", "uuid-1"], ["optOut", false], ["track", "inquiry_opened"]]);
  });

  it("…and a re-grant before it is initialised: neither ready() nor the re-grant opts in before the user, the old event still dropped", async () => {
    const calls: unknown[] = [];
    const init = deferred();
    const sink = createHackleSink("hk_key", {
      Hackle: { createInstance: () => fakeHackle(calls, { init: init.promise }) },
    });
    sink.start!(GRANTED);
    sink.identify(null); // created, still initialising
    sink.track("today_inbox_viewed", {});
    sink.consent!(WITHDRAWN);
    sink.consent!(GRANTED);
    sink.identify(null);
    sink.track("inquiry_opened", {});
    init.resolve();
    await flush();
    expect(calls).toEqual([["optOut", true], ["resetUser"], ["optOut", false], ["track", "inquiry_opened"]]);
  });

  it("the same id twice is set once; sign-out resets; a direct A→B switch resets then sets B", () => {
    const calls: unknown[] = [];
    const users: unknown[] = [];
    const sink = createHackleSink("hk_key", {
      Hackle: { createInstance: (_k, c) => (users.push(c?.user), fakeHackle(calls)) },
    });
    sink.start!(GRANTED);
    sink.identify("uuid-a");
    sink.identify("uuid-a");
    sink.identify("uuid-b");
    sink.identify(null);
    sink.identify(null);
    sink.identify("uuid-c");
    expect(users).toEqual([{ userId: "uuid-a" }]); // the first one is the creation's
    expect(calls).toEqual([
      ["optOut", false],
      ["resetUser"],
      ["setUserId", "uuid-b"],
      ["resetUser"],
      ["setUserId", "uuid-c"],
    ]);
  });

  it("A→B waits for resetUser's Promise before setUserId(B), and keeps events in order behind it", async () => {
    const calls: unknown[] = [];
    const reset = deferred();
    const sink = createHackleSink("hk_key", {
      Hackle: { createInstance: () => fakeHackle(calls, { reset: () => reset.promise }) },
    });
    sink.start!(GRANTED);
    sink.identify("uuid-a");
    sink.identify("uuid-b");
    sink.track("login", { method: "naver" });
    expect(calls).toEqual([["optOut", false], ["resetUser"]]);
    reset.resolve();
    await flush();
    expect(calls.slice(2)).toEqual([["setUserId", "uuid-b"], ["track", "login"]]);
  });
});

describe("Hackle sink under the analytics layer — identity and waiting events across a withdrawal", () => {
  function setUp() {
    const calls: unknown[] = [];
    const win: HackleWindow = { Hackle: { createInstance: () => fakeHackle(calls) } };
    const a = createAnalytics(() => {});
    a.init([createHackleSink("hk_key", win)]);
    a.setConsent(GRANTED);
    return { a, calls };
  }

  it("signed out while withdrawn → re-grant: the previous user is not restored", () => {
    const { a, calls } = setUp();
    a.identify("uuid-a");
    a.setConsent(WITHDRAWN);
    a.identify(null);
    a.setConsent(GRANTED);
    a.track("today_inbox_viewed");
    expect(calls).toEqual([
      ["optOut", false],
      ["optOut", true],
      ["resetUser"],
      ["optOut", false], // re-told "signed out" — nothing to change — so only collection comes back
      ["track", "today_inbox_viewed"],
    ]);
  });

  it("switched account while withdrawn → re-grant: only the current user is set", () => {
    const { a, calls } = setUp();
    a.identify("uuid-a");
    a.setConsent(WITHDRAWN);
    a.identify("uuid-b");
    expect(calls).not.toContainEqual(["setUserId", "uuid-b"]);
    a.setConsent(GRANTED);
    expect(calls.slice(3)).toEqual([["setUserId", "uuid-b"], ["optOut", false]]);
    expect(calls).not.toContainEqual(["setUserId", "uuid-a"]); // uuid-a only ever went in at creation
  });

  it("the same user throughout: set again after the re-grant, because the withdrawal cleared it", () => {
    const { a, calls } = setUp();
    a.identify("uuid-a");
    a.setConsent(WITHDRAWN);
    a.setConsent(GRANTED);
    expect(calls.slice(3)).toEqual([["setUserId", "uuid-a"], ["optOut", false]]);
  });

  it("an event waiting for the SDK before the withdrawal is not delivered after the re-grant", async () => {
    const calls: unknown[] = [];
    const sdk = lateSdk();
    const a = createAnalytics(() => {});
    a.init([createHackleSink("hk_wait", sdk.win)]);
    a.setConsent(GRANTED);
    a.identify("uuid-a");
    a.track("today_inbox_viewed");
    a.setConsent(WITHDRAWN);
    a.setConsent(GRANTED);
    a.track("inquiry_opened");
    const users: unknown[] = [];
    await sdk.arrive({ createInstance: (_k, c) => (users.push(c?.user), fakeHackle(calls)) });
    expect(users).toEqual([{ userId: "uuid-a" }]); // the user re-told after the re-grant
    expect(calls).toEqual([["optOut", false], ["track", "inquiry_opened"]]);
  });
});

/**
 * A fake SDK that, like 12.2.1, restores a user from an earlier visit unless `config.user` says otherwise
 * (`startingUser`); each event records who it went under.
 */
function restoringHackle(
  sent: unknown[],
  restored: string | undefined,
  opts: { setUserId?: () => unknown } = {},
): HackleSdk {
  return { createInstance: (_key, config) => restoringClient(sent, startingUser(config, restored), opts) };
}
function restoringClient(sent: unknown[], startWith: string | undefined, opts: { setUserId?: () => unknown }) {
  let userId = startWith;
  const client: HackleClientLike = {
    track: (e) => sent.push([e.key, userId]),
    setUserId: (id) => {
      const r = opts.setUserId?.();
      if (!isFailure(r)) userId = id;
      return r;
    },
    resetUser: () => {
      userId = undefined;
    },
    setOptOutTracking: (o) => sent.push(["optOut", o]),
  };
  return client;
}
const isFailure = (r: unknown) => r instanceof Promise && (r as Promise<unknown> & { failed?: boolean }).failed === true;
function rejected() {
  const p = Promise.reject(new Error("setUserId failed")) as Promise<unknown> & { failed?: boolean };
  p.failed = true;
  p.catch(() => {});
  return p;
}

describe("Hackle sink — a user restored from an earlier visit (2026-11-07)", () => {
  function underLayer(sdk: HackleSdk) {
    const a = createAnalytics(() => {});
    a.init([createHackleSink("hk_key", { Hackle: sdk })]);
    a.setConsent(GRANTED);
    return a;
  }

  it("a signed-out visitor: events wait for the session, then go under nobody — not the restored user", () => {
    const sent: unknown[] = [];
    const a = underLayer(restoringHackle(sent, "uuid-old"));
    a.track("today_inbox_viewed"); // auth still loading
    expect(sent).toEqual([]);
    a.identify(null); // the session is known: signed out
    a.track("inquiry_opened");
    expect(sent).toEqual([
      ["optOut", false],
      ["today_inbox_viewed", undefined],
      ["inquiry_opened", undefined],
    ]);
  });

  it("a signed-in visitor: the current id replaces the restored one before any event", () => {
    const sent: unknown[] = [];
    const a = underLayer(restoringHackle(sent, "uuid-old"));
    a.track("today_inbox_viewed");
    a.identify("uuid-now");
    expect(sent.slice(1)).toEqual([["today_inbox_viewed", "uuid-now"]]);
  });

  it("the first identity goes into createInstance — no setUserId, no resetUser; a real sign-out later is resetUser", () => {
    const calls: unknown[] = [];
    const users: unknown[] = [];
    const sink = createHackleSink("hk_key", {
      Hackle: { createInstance: (_k, c) => (users.push(c?.user), fakeHackle(calls)) },
    });
    sink.start!(GRANTED);
    sink.identify(null);
    sink.identify("uuid-a");
    sink.identify(null);
    expect(users).toEqual([{}]);
    expect(calls).toEqual([["optOut", false], ["setUserId", "uuid-a"], ["resetUser"]]);
  });

  it("a signed-out visitor's initial value does not fall back to the restored user (12.2.1: `config.user || null`)", () => {
    const sent: unknown[] = [];
    const a = underLayer(restoringHackle(sent, "uuid-old"));
    a.identify(null);
    a.track("today_inbox_viewed");
    expect(sent).toEqual([["optOut", false], ["today_inbox_viewed", undefined]]);
    // the value handed over must be an object: null or undefined would be `|| null` → the restored user
    expect(startingUser({ user: {} }, "uuid-old")).toBeUndefined();
    expect(startingUser({ user: undefined }, "uuid-old")).toBe("uuid-old");
    expect(startingUser({ user: null }, "uuid-old")).toBe("uuid-old");
  });

  it("no more than HACKLE_MAX_WAITING events wait for the first identity", () => {
    const sent: unknown[] = [];
    const a = underLayer(restoringHackle(sent, undefined));
    for (let i = 0; i < HACKLE_MAX_WAITING + 5; i++) a.track("today_inbox_viewed");
    a.identify(null);
    expect(sent.filter((s) => (s as unknown[])[0] === "today_inbox_viewed")).toHaveLength(HACKLE_MAX_WAITING);
  });
});

describe("Hackle sink — a failure stops analytics, not the app (2026-11-07)", () => {
  it("initialisation that reports { success: false }: opted out, nothing ever sent", async () => {
    const calls: unknown[] = [];
    const client = fakeHackle(calls, { init: Promise.resolve({ success: false }) });
    const sink = createHackleSink("hk_key", { Hackle: { createInstance: () => client } });
    sink.start!(GRANTED);
    sink.identify("uuid-1");
    sink.track("today_inbox_viewed", {});
    await flush();
    sink.track("inquiry_opened", {});
    sink.identify("uuid-2");
    sink.consent!(WITHDRAWN);
    sink.consent!(GRANTED);
    await flush();
    expect(calls).toEqual([["optOut", true]]);
  });

  it("initialisation that rejects: the same", async () => {
    const calls: unknown[] = [];
    const init = Promise.reject(new Error("init failed"));
    init.catch(() => {});
    const sink = createHackleSink("hk_key", { Hackle: { createInstance: () => fakeHackle(calls, { init }) } });
    sink.start!(GRANTED);
    sink.identify(null);
    sink.track("today_inbox_viewed", {});
    await flush();
    expect(calls).toEqual([["optOut", true]]);
  });

  it("createInstance that throws never reaches the app", () => {
    const a = createAnalytics(() => {});
    a.init([
      createHackleSink("hk_key", {
        Hackle: {
          createInstance: () => {
            throw new Error("boom");
          },
        },
      }),
    ]);
    expect(() => {
      a.setConsent(GRANTED);
      a.identify("uuid-1");
      a.track("today_inbox_viewed");
    }).not.toThrow();
  });

  it("an identity call that rejects: waiting events dropped, new ones not sent, until a later identify succeeds", async () => {
    const sent: unknown[] = [];
    let failNext = true;
    failNext = false;
    const sink = createHackleSink("hk_key", { Hackle: restoringHackle(sent, "uuid-old", { setUserId: () => (failNext ? rejected() : undefined) }) });
    sink.start!(GRANTED);
    sink.identify("uuid-a"); // created with it
    sink.track("today_inbox_viewed", {});
    expect(sent).toEqual([["optOut", false], ["today_inbox_viewed", "uuid-a"]]);
    failNext = true;
    sink.identify("uuid-b"); // reset, then setUserId(B) fails: the SDK may hold nobody, or B
    sink.track("inquiry_opened", {});
    await flush();
    sink.track("review_attention_opened", {});
    expect(sent.slice(2)).toEqual([["optOut", true]]);
    failNext = false;
    sink.identify("uuid-c");
    sink.track("inquiry_opened", {});
    // an unknown holder is overwritten with setUserId; opted in only after it worked
    expect(sent.slice(3)).toEqual([["optOut", false], ["inquiry_opened", "uuid-c"]]);
  });

  it("an identity call that throws: the same, synchronously", () => {
    const sent: unknown[] = [];
    let failNext = true;
    const sdk = restoringHackle(sent, "uuid-old", {
      setUserId: () => {
        if (failNext) throw new Error("setUserId threw");
      },
    });
    const sink = createHackleSink("hk_key", { Hackle: sdk });
    sink.start!(GRANTED);
    sink.identify("uuid-a");
    sink.identify("uuid-b"); // reset, then setUserId(B) throws
    sink.track("today_inbox_viewed", {});
    expect(sent).toEqual([["optOut", false], ["optOut", true]]);
    failNext = false;
    sink.identify("uuid-b"); // the same id again is retried after a failure, not skipped
    sink.track("today_inbox_viewed", {});
    expect(sent.slice(2)).toEqual([["optOut", false], ["today_inbox_viewed", "uuid-b"]]);
  });

  it("a failed sign-out (resetUser rejects) under the layer: nothing goes under the previous user", async () => {
    const calls: unknown[] = [];
    const client = fakeHackle(calls, { reset: () => Promise.reject(new Error("reset failed")) });
    const a = createAnalytics(() => {});
    a.init([createHackleSink("hk_key", { Hackle: { createInstance: () => client } })]);
    a.setConsent(GRANTED);
    a.identify("uuid-a");
    a.identify(null);
    a.track("today_inbox_viewed");
    await flush();
    a.track("inquiry_opened");
    expect(calls).toEqual([["optOut", false], ["resetUser"], ["optOut", true]]);
  });
});

/**
 * A stand-in for what 12.2.1 itself does (index.browser.es.js, 2026-11-07): the opt-out flag starts from the config;
 * `createInstance` restores the stored user and starts a session for it; a user change emits its session events in
 * the same call (its Promise only waits for the cohort fetch). `left` = what got past the opt-out — what would be sent.
 */
function sdkLike(restored: string | undefined, opts: { init?: Promise<unknown> } = {}) {
  const left: Array<[string, string | undefined]> = [];
  let optedOut = false;
  let userId: string | undefined;
  const emit = (key: string) => {
    if (!optedOut) left.push([key, userId]);
  };
  const change = (next: string | undefined) => {
    if (next === userId) return;
    emit("$session_end");
    userId = next;
    emit("$session_start");
  };
  const sdk: HackleSdk = {
    createInstance: (_key, config) => {
      optedOut = config?.optOutTracking === true;
      userId = restored;
      change(startingUser(config, restored)); // inside construction, like 12.2.1's session refresh
      emit("$session_start");
      const client: HackleClientLike = {
        track: (e) => emit(e.key),
        setUserId: (id) => (change(id), Promise.resolve()),
        resetUser: () => (change(undefined), emit("$properties"), Promise.resolve()),
        setOptOutTracking: (o) => {
          optedOut = o;
        },
      };
      if (opts.init) client.onInitialized = () => opts.init!;
      return client;
    },
  };
  return { sdk, left, emit };
}

describe("Hackle sink — the SDK's own events before the user is in (2026-11-07)", () => {
  function underLayer(sdk: HackleSdk) {
    const a = createAnalytics(() => {});
    a.init([createHackleSink("hk_key", { Hackle: sdk })]);
    a.setConsent(GRANTED);
    return a;
  }

  it("a signed-out visitor with a restored user: the session the SDK starts inside createInstance never leaves", async () => {
    const h = sdkLike("uuid-old");
    const a = underLayer(h.sdk);
    a.track("today_inbox_viewed"); // auth still loading
    expect(h.left).toEqual([]);
    a.identify(null);
    await flush();
    a.track("inquiry_opened");
    expect(h.left).toEqual([
      ["today_inbox_viewed", undefined],
      ["inquiry_opened", undefined],
    ]);
  });

  it("a signed-in visitor with a restored user: nothing under the old id, the switch's session events included", async () => {
    const h = sdkLike("uuid-old");
    const a = underLayer(h.sdk);
    a.track("today_inbox_viewed");
    a.identify("uuid-now");
    await flush();
    expect(h.left).toEqual([["today_inbox_viewed", "uuid-now"]]);
  });

  it("not created before the user is known; created opted out, so nothing leaves before initialisation either", async () => {
    const init = deferred();
    const h = sdkLike("uuid-old", { init: init.promise });
    let created = 0;
    const a = underLayer({ createInstance: (k, c) => (created++, h.sdk.createInstance(k, c)) });
    expect(created).toBe(0);
    a.identify("uuid-a");
    expect(created).toBe(1);
    h.emit("$session_start"); // an SDK event while initialising
    expect(h.left).toEqual([]);
    init.resolve();
    await flush();
    h.emit("$session_end");
    expect(h.left).toEqual([["$session_end", "uuid-a"]]);
  });

  it("a re-grant does not opt in before the current user is back in the SDK", async () => {
    const h = sdkLike(undefined);
    const sink = createHackleSink("hk_key", { Hackle: h.sdk });
    sink.start!(GRANTED);
    sink.identify("uuid-a");
    await flush();
    sink.consent!(WITHDRAWN);
    await flush();
    sink.consent!(GRANTED);
    h.emit("$session_start"); // e.g. a session the SDK starts on its own between the re-grant and the identify
    expect(h.left).toEqual([]);
    sink.identify("uuid-a");
    sink.track("today_inbox_viewed", {});
    await flush();
    expect(h.left).toEqual([["today_inbox_viewed", "uuid-a"]]);
  });
});

describe("Hackle SDK loading — the pinned package, not a CDN script (2026-11-07)", () => {
  it("the chunk failing to load: no analytics, nothing thrown, nothing waiting", async () => {
    const sdk = lateSdk();
    const a = createAnalytics(() => {});
    a.init([createHackleSink("hk_key", sdk.win)]);
    a.setConsent(GRANTED);
    a.identify("uuid-1");
    a.track("today_inbox_viewed");
    await sdk.fail();
    expect(() => a.track("inquiry_opened")).not.toThrow();
    a.setConsent(WITHDRAWN);
    a.setConsent(GRANTED);
    expect(sdk.loads).toBe(1);
  });

  it("waits for initialisation as long as the SDK's own request timeout, not its 3 s default", () => {
    const asked: unknown[] = [];
    const client = fakeHackle([]);
    client.onInitialized = (config) => (asked.push(config), new Promise(() => {}));
    const sink = createHackleSink("hk_key", { Hackle: { createInstance: () => client } });
    sink.start!(GRANTED);
    sink.identify(null);
    expect(asked).toEqual([{ timeout: HACKLE_INIT_TIMEOUT_MS }]);
    expect(HACKLE_INIT_TIMEOUT_MS).toBe(10_000);
  });

  it("the default loader is the pinned package: exactly 12.2.1 in package.json, the lockfile and node_modules", async () => {
    const { readFileSync } = await import("node:fs");
    const json = (rel: string) => JSON.parse(readFileSync(new URL(rel, import.meta.url), "utf8"));
    expect(json("../../../package.json").dependencies["@hackler/javascript-sdk"]).toBe("12.2.1");
    const locked = json("../../../package-lock.json").packages["node_modules/@hackler/javascript-sdk"];
    expect(locked.version).toBe("12.2.1");
    expect(locked.integrity).toMatch(/^sha512-/);
    expect(json("../../../node_modules/@hackler/javascript-sdk/package.json").version).toBe("12.2.1");
    const { loadHackleSdk } = await import("./hackleSink");
    const sdk = await loadHackleSdk();
    expect(typeof sdk.createInstance).toBe("function");
  });

  it("the CSP lets no Hackle script host in — only the two hosts the SDK calls, mirrored from the sink", () => {
    const csp = buildCsp({ VITE_HACKLE_SDK_KEY: "hk_x" });
    const directive = (name: string) => csp.split(";").map((d) => d.trim()).find((d) => d.startsWith(`${name} `)) ?? "";
    expect(directive("script-src")).toBe("script-src 'self'");
    for (const origin of HACKLE_ORIGINS) expect(directive("connect-src")).toContain(origin);
    expect(csp).not.toMatch(/cdn2\.hackle\.io|jsdelivr|static\.hackle\.io|monitoring\.hackle\.io/);
    expect(buildCsp({})).not.toMatch(/hackle/);
  });
});

describe("Hackle sink — the login events belong to the user who signed in (2026-11-07)", () => {
  /** A client that remembers whose events it sends: 12.2.1 changes the user synchronously and resolves a Promise. */
  function attributingClient(sent: Array<[string, string | null]>) {
    let current: string | null = null;
    const client: HackleClientLike = {
      track: (e) => sent.push([e.key, current]),
      setUserId: (id) => {
        current = id ?? null;
        return Promise.resolve();
      },
      resetUser: () => {
        current = null;
        return Promise.resolve();
      },
      setOptOutTracking: () => {},
    };
    return client;
  }

  it("signed out → A (login, home) → sign-out → B (login): each event under its user, once", async () => {
    const sent: Array<[string, string | null]> = [];
    const a = createAnalytics(() => {});
    a.init([createHackleSink("hk_key", { Hackle: { createInstance: () => attributingClient(sent) } })]);
    a.setConsent(GRANTED);
    a.identify(null); // the session is known: nobody signed in
    await flush();

    // what `AuthProvider.login` + `Login.onSubmit` + the home screen now do, in this order
    a.identify("uuid-a");
    a.track("login", { method: "email" });
    a.track("today_inbox_viewed", {});
    await flush();
    a.identify(null);
    await flush();
    a.identify("uuid-b");
    a.track("login", { method: "email" });
    await flush();

    expect(sent).toEqual([
      ["login", "uuid-a"],
      ["today_inbox_viewed", "uuid-a"],
      ["login", "uuid-b"],
    ]);
  });

  it("the order it replaced — event first, identity after — is exactly what sent them anonymously", async () => {
    const sent: Array<[string, string | null]> = [];
    const a = createAnalytics(() => {});
    a.init([createHackleSink("hk_key", { Hackle: { createInstance: () => attributingClient(sent) } })]);
    a.setConsent(GRANTED);
    a.identify(null);
    await flush();
    a.track("login", { method: "email" });
    a.identify("uuid-a");
    await flush();
    expect(sent).toEqual([["login", null]]);
  });
});

