/**
 * **The Runner half of the sign-in recovery session** — build the plan for a channel, ship the program, take
 * back one of three closed words.
 *
 * <p>Everything a channel contributes here is already published and already screened: the entry route from
 * its read workflow, and the read-only `signedIn` probe its acquisition uses. Nothing new is authored. That
 * is the point — the page the seller is sent to is the page the read opens, and «signed in» is the same
 * question in both places, so the product cannot tell the seller they are signed in on a surface the read
 * would then refuse.
 *
 * <p><b>A signed-out channel sends the seller to its own login page.</b> That is why no login URL appears in
 * this file: opening the published entry route while signed out IS opening the sign-in page, by the
 * marketplace's own redirect. A separate login URL would be a second route to screen and a second thing to
 * get wrong.
 */
import { buildNaverReviewAuthScript } from "../naver/review-list-observe-inpage";
import { buildWingAuthScript } from "../action-window/coupang-review/wing-identity-inpage";
import { runAsideRepl, type AsideCliOptions } from "./aside-cli";
import {
  asideSignInRuntime,
  SIGN_IN_BOUND_MS,
  SIGN_IN_CHECK_BOUND_MS,
  SIGN_IN_POLL_MS,
  type SignInRuntimePlan,
  type SignInRuntimeResult,
} from "./sign-in-runtime";
import { COUPANG_REVIEW_READ_WORKFLOW } from "./coupang-review-workflow";
import { NAVER_REVIEW_READ_WORKFLOW } from "./naver-review-workflow";

/** Mirrors the other executors' preamble, and for the same reason: esbuild `keepNames` under tsx. */
export const SIGN_IN_RUNTIME_PREAMBLE = "const __name = (target, _value) => target;" as const;

/** The channels a sign-in recovery exists for — exactly those whose reads can meet a sign-in wall. */
export const SIGN_IN_CHANNELS = ["NAVER", "COUPANG"] as const;
export type SignInChannel = (typeof SIGN_IN_CHANNELS)[number];

export function isSignInChannel(raw: unknown): raw is SignInChannel {
  return typeof raw === "string" && (SIGN_IN_CHANNELS as readonly string[]).includes(raw);
}

/**
 * What one session is for.
 *
 * <p><b>RECOVER</b> — the seller pressed 「판매자센터 로그인」. The window comes forward and the session waits
 * out its bound while they sign in.
 *
 * <p><b>CHECK</b> — the seller came back to Reviewnary with a read waiting on a sign-in. One probe, no
 * waiting, no window raised: this asks 「지금 로그인되어 있나」 and nothing else. It exists because a seller
 * who signs in anywhere — another tab, another device, after the recovery window closed — has solved the
 * thing the product was waiting for, and the product has no way to be told
 * ({@code docs/review_auto_check_v1.md}, measured live 2026-10-09).
 */
export type SignInMode = "RECOVER" | "CHECK";

/** The plan for one channel, assembled only from what that channel's read already publishes. */
export function buildSignInRuntimePlan(channel: SignInChannel, mode: SignInMode = "RECOVER"): SignInRuntimePlan {
  const workflow = channel === "NAVER" ? NAVER_REVIEW_READ_WORKFLOW : COUPANG_REVIEW_READ_WORKFLOW;
  return {
    entryUrl: workflow.entryUrl,
    authScript: channel === "NAVER" ? buildNaverReviewAuthScript() : buildWingAuthScript(),
    settleTimeoutMs: workflow.settleTimeoutMs,
    pollMs: SIGN_IN_POLL_MS,
    boundMs: mode === "CHECK" ? SIGN_IN_CHECK_BOUND_MS : SIGN_IN_BOUND_MS,
    raiseWindow: mode === "RECOVER",
  };
}

export function buildSignInRuntimeProgram(plan: SignInRuntimePlan): string {
  const fn = asideSignInRuntime.toString();
  return [
    SIGN_IN_RUNTIME_PREAMBLE,
    `const __plan = ${JSON.stringify(plan)};`,
    `const __run = (${fn});`,
    `const __wait = (ms) => new Promise((r) => setTimeout(r, ms));`,
    `const __result = await __run(__plan, { openTab }, __wait, () => Date.now());`,
    `console.log("ASIDE_RESULT " + JSON.stringify(__result));`,
  ].join("\n");
}

/** What the bridge reports. Closed words; never a URL, never a page, never anything the seller typed. */
export type SignInOutcome = "SIGNED_IN" | "NOT_SIGNED_IN" | "UNAVAILABLE";

/** Parse the runtime's own result, refusing anything that is not one of its shapes. */
export function signInOutcomeOf(result: unknown): SignInOutcome {
  if (!result || typeof result !== "object") {
    return "UNAVAILABLE";
  }
  const state = (result as { state?: unknown }).state;
  return state === "SIGNED_IN" ? "SIGNED_IN" : state === "NOT_SIGNED_IN" ? "NOT_SIGNED_IN" : "UNAVAILABLE";
}

/**
 * Run one bounded recovery session for this channel.
 *
 * <p>Our own ceiling is the runtime's bound plus a small margin for Aside's own startup — a wrapper that
 * timed out before the program could answer would report `UNAVAILABLE` about a session that was working.
 */
export async function runSignInRecovery(
  channel: SignInChannel,
  options: AsideCliOptions = {},
): Promise<SignInOutcome> {
  return runSignInSession(channel, "RECOVER", options);
}

/**
 * Ask once whether this channel is signed in, and answer.
 *
 * <p>The same page and the same read-only probe the recovery session uses — so «signed in» means one thing
 * across the product rather than two that could disagree. What it does not do is wait, and it does not raise
 * the window: a seller who is already signed in sees nothing happen, which is the right amount of happening
 * for a question nobody asked out loud.
 */
export async function runSignInCheck(
  channel: SignInChannel,
  options: AsideCliOptions = {},
): Promise<SignInOutcome> {
  return runSignInSession(channel, "CHECK", options);
}

async function runSignInSession(
  channel: SignInChannel,
  mode: SignInMode,
  options: AsideCliOptions,
): Promise<SignInOutcome> {
  const plan = buildSignInRuntimePlan(channel, mode);
  const run = await runAsideRepl(buildSignInRuntimeProgram(plan), {
    ...options,
    // A check's own bound is one probe, so its ceiling is the page opening, not the bound.
    timeoutMs: options.timeoutMs ?? plan.boundMs + (mode === "CHECK" ? 45_000 : 15_000),
  });
  if (run.kind !== "RESULT") {
    return "UNAVAILABLE";
  }
  return signInOutcomeOf(run.result as SignInRuntimeResult);
}
