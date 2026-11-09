import type { AnalyticsEventName } from "./events";
import type { AnalyticsSink } from "./sink";

/**
 * Hackle sink (2026-11-06, 모두의창업 AI 솔루션 — 핵클 그로스 해킹 플랜) — the same abstraction as PostHog. The SDK is the npm
 * package `@hackler/javascript-sdk`, pinned to exactly 12.2.1 in package.json and the lockfile (integrity hash), and
 * imported dynamically — its own chunk, served from this app's origin, fetched only when `start()` runs, i.e. after
 * 분석 consent (docs/standing/auth_growth_instrumentation_v1.md §2-8). No third-party script URL is involved: the CDN
 * path it replaced answered 301 to another host on 2026-11-07, which the CSP would have blocked. The browser SDK key
 * is public by design; the server key never reaches this file.
 *
 * What leaves the browser — checked against the 12.2.1 bundle, an offline run of the real SDK behind this sink
 * (`hackleSdkPayload.test.ts`) and the dev-environment console (2026-11-07):
 * - Events from THIS sink: only the typed events in `events.ts` with their allow-listed properties, and the opaque
 *   user id.
 * - Events the SDK makes on its own, which this sink can neither narrow to the vocabulary nor switch off:
 *   - `$session_start` / `$session_end` — no switch in 12.2.1;
 *   - `$engagement` — `$page_name` (the document title) and `$engagement_time_ms`, whenever a page is left after
 *     >= 1 s. The browser client registers it unconditionally: `automaticPageTracking` and
 *     `automaticEngagementTracking` do NOT turn it off (an earlier version of this comment said they did; the
 *     dev-environment console showed `$engagement` with both page settings off, 2026-11-07);
 *   - `$properties` — user-property changes: the clear-all of `resetUser`, and the utm_* set/unset below;
 *   - exposure events, if a flag or experiment were evaluated (this sink never evaluates one).
 * - Switched OFF in `HACKLE_CONFIG`: `$page_view` (`automaticPageTracking`, default ON; `auto_track_page_view` is the
 *   older name the bundle still reads first), the route listener (`automaticRouteTracking`), and SDK self-monitoring
 *   (`enableMonitoring`, default ON, its own host).
 * - Attached by the SDK to EVERY event above, typed or its own, as `hackleProperties` built from `window.location` /
 *   `document` at the moment of the event: the full page URL with query string and hash (`url`), `pagePath`,
 *   `queryParameter`, `host`, `protocol`, the full `referrer`, the document title (`pageTitle`), the user agent and the
 *   browser / OS / device fields, screen size and orientation, `locale` / `language` / `timeZone`. A `utm_*` /
 *   `gclid` / `fbclid` in the URL also becomes a user property. 12.2.1's `Config` has no option to leave any of these
 *   out — so whatever a route carries in its path or query (a marketplace order number, a review or product id, the
 *   inbox search `q`) reaches Hackle with every event fired on that page, `$engagement` included. Only `code` /
 *   `token` are lifted out of the URL before any vendor starts (`urlSecrets.ts`).
 * - Requests to sdk-api: the workspace config at start, and a cohort / user-target lookup carrying the identifiers
 *   (device id, plus the user id when signed in) at start and on every user change.
 * The opt-out (`optOutTracking` / `setOptOutTracking`) is a flag the SDK's event processor reads before queueing ANY
 * event, its own included — set synchronously, so it works before initialisation too. Turning it on FLUSHES the
 * events already batched (up to 500 ms' worth, all collected under consent) rather than dropping them, and the
 * `resetUser` a withdrawal runs to clear the user id makes ONE more sdk-api user-target lookup — device ids only, no
 * user id: 12.2.1 syncs user targets on every user change, opted out or not, and no public call clears the id without
 * it. So a withdrawal means "no new events from then on", not "no traffic at all". In-app
 * messages would load a renderer script from `static.hackle.io`, which the CSP does not allow; this product uses
 * none. Which of these actually go out is the test-environment network check, not this comment.
 */
export interface HackleClientLike {
  track(event: { key: string; properties?: Record<string, unknown> }): void;
  /**
   * 12.x returns a Promise (that never rejects — 12.2.1 catches inside); the next call waits for it. `undefined`
   * clears the user id only — the documented client-side sign-out (docs.hackle.io user-identifier).
   */
  setUserId?(id: string | undefined): unknown;
  /**
   * Back to the SDK's default user — its own device id, no user id, no properties (12.2.1 `LocalUserManager`) — and
   * tracks a clear-all `$properties` event. Returns a Promise that never rejects.
   */
  resetUser?(): unknown;
  /** Documented opt-out (docs.hackle.io js-optout): true stops the SDK sending events; it resets on reload. */
  setOptOutTracking?(optOut: boolean): void;
  /**
   * 12.x: resolves `{ success: true }` once initialised, `{ success: false }` when it failed or did not finish within
   * `timeout` (default 3 s). Preferred over `onReady`.
   */
  onInitialized?(config?: { timeout?: number }): Promise<unknown>;
  /** Deprecated in 12.x — used only when `onInitialized` is absent. */
  onReady?(cb: () => void): void;
}

export interface HackleSdk {
  createInstance(key: string, config?: Record<string, unknown>): HackleClientLike;
}

/** Where the sink gets the SDK: already present (tests), or a loader — the default is the pinned package's chunk. */
export interface HackleWindow {
  Hackle?: HackleSdk;
  load?: () => Promise<HackleSdk>;
}

/** The pinned package, as its own lazily-fetched chunk. */
export const loadHackleSdk = (): Promise<HackleSdk> =>
  import("@hackler/javascript-sdk").then((m) => m as unknown as HackleSdk);

/**
 * The hosts the SDK calls with `HACKLE_CONFIG` (12.2.1 `DEFAULT_SDK_URL` · `DEFAULT_EVENT_URL`) — connect-src only,
 * mirrored in `csp.ts`. The script itself is this app's own chunk, so there is no script origin. Monitoring
 * (`monitoring.hackle.io`) is off, and the in-app message renderer (`static.hackle.io`) is not allowed.
 */
export const HACKLE_ORIGINS = ["https://sdk-api.hackle.io", "https://event-api.hackle.io"];
/** How long `onInitialized` may take — the SDK's own request timeout (12.2.1 `REQUEST_TIME_OUT`), not its 3 s default. */
export const HACKLE_INIT_TIMEOUT_MS = 10_000;
/**
 * Every automatic collector 12.2.1 lets a caller switch off, OFF (`$engagement` and the session events have no switch —
 * see the header) — and the instance is always created OPTED OUT (`optOutTracking: true`):
 * 12.2.1 starts a session inside `createInstance` (`$session_end` for a user restored from an earlier visit, then
 * `$session_start`). Collection is turned on only by the queue, once the SDK has initialised. The current user is
 * added per instance as `user` (see `userConfig`).
 */
export const HACKLE_CONFIG = Object.freeze({
  automaticPageTracking: false,
  auto_track_page_view: false,
  automaticRouteTracking: false,
  enableMonitoring: false,
  optOutTracking: true,
});

type Op = (c: HackleClientLike) => unknown;
/** `control` = consent housekeeping; `identity` = putting the current user into the SDK; `event` = a typed event. */
type Entry = { kind: "control" | "identity" | "event"; run: Op };

/** Events waiting for the SDK or for the first identity — beyond this they are dropped, like the layer's buffer. */
export const HACKLE_MAX_WAITING = 100;

/**
 * The user the SDK is created with. 12.2.1 takes `config.user || null`, then `initUser ?? previousUser` — so ANY object
 * here, `{}` included, replaces the user it would restore from its cookies (`_hackle_uid` · `_hackle_did`), and the
 * cohort / user-target lookups its initialisation sends to sdk-api carry the current user, never the previous visitor.
 * Leaving it out (or `null`) would restore. A signed-out visitor is `{}`: the SDK fills in its own device id.
 */
export const userConfig = (userId: string | null): { userId?: string } => (userId ? { userId } : {});

const isThenable = (v: unknown): v is PromiseLike<unknown> =>
  typeof v === "object" && v !== null && typeof (v as { then?: unknown }).then === "function";

/** `onInitialized` resolves `{ success: false }` on a failed initialisation (12.2.1); anything else is success. */
const initialisedOk = (r: unknown) => !(typeof r === "object" && r !== null && (r as { success?: unknown }).success === false);

/**
 * Consent, identity and failure (2026-11-07).
 * - **The SDK is not created until consent AND the signed-in state are both known**, and is created WITH that user
 *   (`userConfig`): 12.2.1's initialisation sends the user it holds to sdk-api (cohorts, user targets) whether or not
 *   it is opted out, so it must never hold the previous visitor, even for a moment. Before creation, an `identify`
 *   is only remembered; after it, a change is `setUserId` / `resetUser` as below.
 * - **The SDK collects nothing until the current user is in it.** It is created opted out; the opt-in is queued right
 *   behind the identity calls (after their Promises settle) and ahead of the events that waited — never at creation,
 *   in `ready()` or on a re-grant. 12.2.1 changes the user synchronously and emits the session events of that change
 *   in the same call, so they fall while still opted out. The price: the `$session_start` of a session begun at page
 *   load (or by that first user change) is never sent; later sessions' are — Hackle's session count is no denominator
 *   for visits or conversion.
 * - Nothing is sent before the SDK reports it initialised; calls wait in order, and a call that returns a Promise holds
 *   the ones after it until it settles (so A→B is `resetUser` THEN `setUserId(B)`).
 * - **No event before the current user is in the SDK.** "Not yet told" is not "anonymous": events wait until the
 *   first `identify`. When the user the SDK holds is not known (an identity call failed), the next `identify`
 *   overwrites it — `setUserId(id)`, or `setUserId(undefined)` for a signed-out visitor; `resetUser` is kept for a
 *   real sign-out, so it adds its clear-all `$properties` event only then. The same id twice is set once; sign-out is
 *   `resetUser`; A→B is `resetUser` then `setUserId(B)`.
 * - **A failure stops analytics, never the app.** Initialisation that fails (`{ success: false }` or a rejection): the
 *   SDK is opted out and nothing is ever sent from it. An identity call that fails (throws or rejects — 12.2.1 itself
 *   catches and logs, so this guards a wrapper or a later version): the SDK is opted out, waiting events are dropped
 *   and new ones are not queued, until a later `identify` succeeds — the SDK may hold the wrong user, so nothing may
 *   be attributed to it. The chunk failing to load is the same as a failed initialisation.
 * - Withdrawal: `setOptOutTracking(true)` at once — also on an instance still initialising — then every waiting call
 *   is dropped and `resetUser` clears the id the SDK holds (one more sdk-api user-target lookup, device ids only —
 *   see the header). While withdrawn, nothing is queued and no identity is
 *   remembered: the analytics layer re-tells the CURRENT user after a re-grant, so a user who signed out or switched
 *   accounts while withdrawn is never restored.
 * - An SDK whose script finishes loading after the withdrawal is not created at all; after a re-grant it is created
 *   once the analytics layer has told the current user again.
 * - A re-grant does not opt back in by itself: it waits for the analytics layer to re-tell the current user.
 * The opt-out does not survive a reload, and need not: on the next page load a withdrawn consent never starts this
 * sink. What the SDK keeps in the browser on its own (cookies, device id) is the test-environment check.
 */
export function createHackleSink(key: string, win: HackleWindow = { load: loadHackleSdk }): AnalyticsSink {
  let started = false;
  let loaded = false;
  let sdk: HackleSdk | undefined = win.Hackle;
  let allowed = true;
  let instance: HackleClientLike | null = null;
  let initialised = false;
  /** Initialisation failed: nothing is ever sent from this instance. */
  let dead = false;
  let waiting = false;
  /** The current user has been put in the queue since the last grant (or failure); events wait until then. */
  let told = false;
  /** An identity call failed: events are dropped until a later identify succeeds. */
  let broken = false;
  /** The SDK collects — or will, once the queue reaches the opt-in behind the current user's identity. */
  let collecting = false;
  /** The user id the SDK holds once every waiting call has run: null = none, undefined = not known (first sync, failure). */
  let planned: string | null | undefined = undefined;
  const queue: Entry[] = [];

  const optOut = (c: HackleClientLike, value: boolean) => {
    try {
      c.setOptOutTracking?.(value);
    } catch {
      // the stop is best effort; nothing else is sent either way
    }
  };
  const fail = (entry: Entry) => {
    if (entry.kind === "event") return;
    planned = undefined;
    if (entry.kind !== "identity") return;
    told = false;
    broken = true;
    collecting = false;
    // drop the user's calls (the queued opt-in among them) and events; consent housekeeping still runs
    const kept = queue.filter((e) => e.kind === "control");
    queue.splice(0, queue.length, ...kept);
    if (instance) optOut(instance, true);
  };
  const pump = () => {
    const c = instance;
    if (!c || !initialised || dead) return;
    while (!waiting && queue.length) {
      const head = queue[0];
      if (head.kind === "event" && !told) return;
      queue.shift();
      let result: unknown;
      try {
        result = head.run(c);
      } catch {
        fail(head);
        continue;
      }
      if (isThenable(result)) {
        waiting = true;
        result.then(
          () => {
            waiting = false;
            pump();
          },
          () => {
            waiting = false;
            fail(head);
            pump();
          },
        );
      }
    }
  };
  const setIdentity = (userId: string | null) => {
    const entries: Entry[] = [];
    const id = (run: Op): Entry => ({ kind: "identity", run });
    // (after a failure `planned` is undefined, so the same id is retried rather than skipped)
    if (userId !== planned) {
      // before the SDK exists the user is only remembered — it is created with it (`userConfig`)
      if (!instance) {
        // nothing to call
      } else if (planned === undefined) {
        entries.push(id((c) => c.setUserId?.(userId ?? undefined)));
      } else {
        if (planned !== null) entries.push(id((c) => c.resetUser?.()));
        if (userId) entries.push(id((c) => c.setUserId?.(userId)));
      }
      planned = userId;
    }
    // collection starts only behind the identity calls — at first sync, after a re-grant, after a failure
    if (!collecting) {
      entries.push(id((c) => optOut(c, false)));
      collecting = true;
    }
    broken = false;
    if (told) queue.push(...entries);
    else {
      // the first identity goes ahead of the events that waited for it (but after any consent housekeeping)
      const at = queue.findIndex((e) => e.kind === "event");
      queue.splice(at < 0 ? queue.length : at, 0, ...entries);
      told = true;
    }
  };
  const ready = (c: HackleClientLike, ok: boolean) => {
    if (c !== instance || initialised) return;
    initialised = true;
    if (!ok) {
      dead = true;
      queue.splice(0);
      optOut(c, true);
      return;
    }
    // no opt-in here: it waits in the queue behind the current user (still opted out from creation or withdrawal)
    pump();
  };
  const create = () => {
    loaded = true;
    // consent, the SDK and the signed-in state, all three — never created holding "whoever it restores"
    if (!allowed || instance || !sdk || planned === undefined) return;
    let c: HackleClientLike;
    try {
      c = sdk.createInstance(key, { ...HACKLE_CONFIG, user: userConfig(planned) });
    } catch {
      dead = true;
      return;
    }
    instance = c;
    if (c.onInitialized) {
      c.onInitialized({ timeout: HACKLE_INIT_TIMEOUT_MS }).then(
        (r) => ready(c, initialisedOk(r)),
        () => ready(c, false),
      );
    } else if (c.onReady) c.onReady(() => ready(c, true));
    else ready(c, true);
  };

  return {
    name: "hackle",
    start() {
      if (started) return;
      started = true;
      if (sdk) {
        create();
        return;
      }
      if (!win.load) return;
      win.load().then(
        (loadedSdk) => {
          sdk = loadedSdk;
          create();
        },
        () => {
          // the chunk did not arrive: no analytics this page, the app carries on
          dead = true;
          queue.splice(0);
        },
      );
    },
    consent(grant) {
      const was = allowed;
      allowed = grant.analytics;
      if (!allowed) {
        if (!was) return;
        queue.splice(0);
        told = false;
        broken = false;
        collecting = false;
        // the dropped calls never ran, so what the SDK holds is known only if the reset below runs
        planned = undefined;
        if (instance && !dead) {
          optOut(instance, true);
          planned = null;
          queue.push({ kind: "control", run: (c) => c.resetUser?.() });
          pump();
        }
        return;
      }
      if (was) return;
      // no opt-in here: it is queued behind the current user, whom the analytics layer re-tells after a re-grant
      if (!instance && loaded) create();
    },
    track(event: AnalyticsEventName, props: Record<string, string>) {
      if (!allowed || dead || broken) return;
      if (queue.filter((e) => e.kind === "event").length >= HACKLE_MAX_WAITING) return;
      queue.push({ kind: "event", run: (c) => c.track({ key: event, properties: props }) });
      pump();
    },
    identify(userId) {
      if (!allowed || dead) return;
      setIdentity(userId);
      if (!instance && loaded) create();
      pump();
    },
  };
}
