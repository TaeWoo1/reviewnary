/**
 * **The bounded sign-in recovery session: open the store's own page, and watch for the seller to finish.**
 *
 * <p>Why this exists. A pressed read that meets the channel's sign-in wall settles `AUTH_REQUIRED` and the
 * product can say so — but the seller then had nowhere to go. The window the acquisition drives is Aside's,
 * not a browser they can reach, so "로그인한 뒤 다시 수집해 주세요" was advice about a session they could not
 * open. This opens it: the same page, in the same persistent profile the read uses, and then it watches.
 *
 * <h2>What it does NOT do, and cannot</h2>
 *
 * It types nothing. There is no credential in this file, no field is filled, no form is submitted, no MFA
 * code is read or entered, and no CAPTCHA is looked at. The only thing it sends to the page is the SAME
 * read-only `signedIn` probe the acquisition uses — which is also why «signed in» means one thing here and
 * there, rather than two things that could disagree. The seller signs in; this watches a boolean.
 *
 * <h2>Bounded, and the bound is the seller's patience, not a credential's</h2>
 *
 * One session is one Aside `repl` call, so its ceiling is Aside's (120s) and ours is deliberately under it.
 * The poll is slow on purpose: a sign-in page being typed into does not need to be inspected every second,
 * and a tight loop on a page someone is using is a worse neighbour than a slow one. When the bound elapses
 * the answer is `NOT_SIGNED_IN` — <b>not</b> a failure: MFA takes longer than any bound we would be right to
 * choose, so the product asks again rather than waiting forever on a window it cannot see.
 *
 * <p>The tab is never closed. The seller is standing in it.
 *
 * <h2>The window is raised — once, and only here</h2>
 *
 * Measured 2026-10-08: the seller pressed 「판매자센터 로그인」, the page opened, and nothing happened on screen.
 * Aside's `openTab` makes the new tab active inside its own browser, which is not the same as that browser's
 * window coming forward on the desktop — so the one control whose whole job is «take me there» took the seller
 * nowhere visible.
 *
 * <p>`bringToFront()` is Playwright's own primitive and Aside forwards it (verified against the installed CLI,
 * 1.26.906). It is called <b>once, right after the page opens</b>, and nowhere else: raising a window on every
 * poll would pull focus out of a field while someone is typing their password into it, which is worse than not
 * raising it at all. It is best-effort — a host that does not forward it changes nothing about the session, and
 * the product still says 「판매자센터 로그인 창을 열었습니다」 so a seller whose window did not come forward knows
 * there is a window to look for.
 *
 * <p>No read runtime may do this. Raising a window is a thing done <b>for</b> a person who asked to be taken
 * somewhere; an unattended read has nobody to take, and `aside-guard.test.ts` holds this primitive to this file.
 */

/** How often the page is asked. Slow by intent — see the class note. */
export const SIGN_IN_POLL_MS = 5_000;

/**
 * The whole session's ceiling. Under Aside's own 120s `repl` bound, because one session is one call and a
 * program killed by the host returns nothing at all — a bound we chose beats a bound that loses the answer.
 */
export const SIGN_IN_BOUND_MS = 100_000;

export interface SignInRuntimePlan {
  /** The channel's published entry route. Signed out, the channel itself redirects this to its login page. */
  entryUrl: string;
  /** The acquisition's own read-only probe. Must answer `{ signedIn: boolean }`. */
  authScript: string;
  /** How long the page may take to settle before the first probe. */
  settleTimeoutMs: number;
  pollMs: number;
  boundMs: number;
}

export interface SignInRuntimeTabLike {
  evaluate(script: string): Promise<unknown>;
  waitForLoadState?(state: string, opts?: { timeout?: number }): Promise<unknown>;
  /** Playwright's own «make this page the visible one». Optional: a host without it is not an error. */
  bringToFront?(): Promise<unknown>;
}

export interface SignInRuntimeEnv {
  openTab(url: string): Promise<SignInRuntimeTabLike>;
}

export type SignInRuntimeResult =
  /** The probe saw a signed-in store. Nothing was typed by this program to get there. */
  | { ok: true; state: "SIGNED_IN"; elapsedMs: number }
  /** The bound elapsed with the store still signed out, or the seller never finished. Not an error. */
  | { ok: true; state: "NOT_SIGNED_IN"; elapsedMs: number }
  /** The page could not be opened, or the probe could not run. Nothing is claimed about the session. */
  | { ok: false; state: "UNAVAILABLE"; stage: "OPEN" | "PROBE"; elapsedMs: number };

/**
 * Runs inside Aside, with only `openTab` in scope (and a `__wait` the program preamble supplies).
 *
 * Serialised with {@code Function.prototype.toString}, so it must stay self-contained: no imports, no
 * closures over module state, nothing from this file's scope.
 */
export async function asideSignInRuntime(
  plan: SignInRuntimePlan,
  env: SignInRuntimeEnv,
  wait: (ms: number) => Promise<void>,
  nowMs: () => number,
): Promise<SignInRuntimeResult> {
  const startedAt = nowMs();
  const elapsed = () => nowMs() - startedAt;

  let tab: SignInRuntimeTabLike;
  try {
    tab = await env.openTab(plan.entryUrl);
  } catch {
    return { ok: false, state: "UNAVAILABLE", stage: "OPEN", elapsedMs: elapsed() };
  }
  // Raised before the page is waited on: the seller pressed a control that promised to take them here, and the
  // window should be in front of them while it loads rather than after.
  if (typeof tab.bringToFront === "function") {
    try {
      await tab.bringToFront();
    } catch {
      /* best-effort: a host that cannot raise a window still runs the session */
    }
  }
  if (typeof tab.waitForLoadState === "function") {
    try {
      await tab.waitForLoadState("networkidle", { timeout: plan.settleTimeoutMs });
    } catch {
      /* best-effort: an unsettled page is decided by the probe below, not by a timer */
    }
  }

  let probed = false;
  // A do-while over the bound: the first probe happens immediately, because a seller who was already signed
  // in should not be made to wait out a poll interval to be told so.
  for (;;) {
    let auth: unknown;
    try {
      auth = await tab.evaluate(plan.authScript);
      probed = true;
    } catch {
      // One failed probe is not an answer; only never having probed at all is.
      if (!probed && elapsed() >= plan.boundMs) {
        return { ok: false, state: "UNAVAILABLE", stage: "PROBE", elapsedMs: elapsed() };
      }
      auth = null;
    }
    if (auth && typeof auth === "object" && (auth as { signedIn?: unknown }).signedIn === true) {
      return { ok: true, state: "SIGNED_IN", elapsedMs: elapsed() };
    }
    if (elapsed() + plan.pollMs >= plan.boundMs) {
      return probed
        ? { ok: true, state: "NOT_SIGNED_IN", elapsedMs: elapsed() }
        : { ok: false, state: "UNAVAILABLE", stage: "PROBE", elapsedMs: elapsed() };
    }
    await wait(plan.pollMs);
  }
}
