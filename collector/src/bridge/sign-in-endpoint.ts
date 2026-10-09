import { isSignInChannel, type SignInChannel, type SignInOutcome } from "../aside/sign-in-executor";

/**
 * **The seller's own sign-in recovery, hosted on demand and remembered nowhere.**
 *
 * <p>The press 「판매자센터 로그인」 arrives here from the paired tab, this opens the channel's page in the
 * same persistent profile the acquisition drives, and the tab asks back until there is an answer. One
 * session at a time, per helper, because there is one browser and one person standing in it.
 *
 * <h2>In memory, by decision</h2>
 *
 * <p>Nothing about a sign-in is written anywhere: not to the backend, not to a job row, not to disk. That is
 * the product decision (2026-10-08) and it has a consequence worth stating plainly — <b>a page reload loses
 * it</b>, and the seller then sees 「최근 수집 시 로그인이 필요했습니다」 again, because that sentence is about
 * the last collection attempt and remains true until the next one. Only the tab that ran the session may say
 * 「로그인 확인됨」, and only until it navigates away. Storing it would mean the product claiming a store is
 * reachable on the strength of a probe that is minutes old and belongs to nobody.
 *
 * <h2>What it will not do</h2>
 *
 * <p>No credential reaches this file or the program it runs; nothing is typed into the page, no form is
 * submitted, no MFA code and no CAPTCHA is handled. The seller signs in; the session watches a boolean.
 */

/** What the paired browser is told. Closed words only. */
export type SignInSessionState =
  /** No session has run in this helper's lifetime (or the last one was read and cleared). */
  | "IDLE"
  /** A bounded session is running: the page is open and the seller is signing in. */
  | "WAITING"
  /** The probe saw a signed-in store. */
  | "SIGNED_IN"
  /** The bound elapsed with the store still signed out. Not a failure — ask again. */
  | "NOT_SIGNED_IN"
  /** The session could not run at all (no executor, no browser, refused). */
  | "UNAVAILABLE";

export interface SignInSessionStatus {
  state: SignInSessionState;
  /** Which channel the running or last session was for, or null when none has run. */
  channelCode: SignInChannel | null;
}

export type SignInStartResult =
  | { ok: true; state: "WAITING" }
  /** Another session is still open on this desk — there is one browser, and someone is in it. */
  | { ok: false; reason: "busy" }
  /** Not a channel a sign-in recovery exists for. */
  | { ok: false; reason: "unsupported_channel" }
  /** This helper has no way to drive a marketplace browser (the Aside lane is not configured here). */
  | { ok: false; reason: "no_executor" };

/** What a check answers. `busy` means a session is already open on this desk — ask its status instead. */
export type SignInCheckResult =
  | { ok: true; state: SignInOutcome }
  | { ok: false; reason: "busy" | "unsupported_channel" | "no_executor" };

export interface SignInEndpointDeps {
  /** Run one bounded session. Injected so the endpoint's own rules are testable without a browser. */
  recover?: (channel: SignInChannel) => Promise<SignInOutcome>;
  /** Ask once, without waiting and without raising a window. Absent in a helper built before checks existed. */
  check?: (channel: SignInChannel) => Promise<SignInOutcome>;
  /** Operator log. Receives closed words only — never a URL and never a page. */
  log?: (event: string, fields: Record<string, unknown>) => void;
}

export class SignInEndpoint {
  private readonly recover: ((channel: SignInChannel) => Promise<SignInOutcome>) | null;
  private readonly checkSession: ((channel: SignInChannel) => Promise<SignInOutcome>) | null;
  private readonly log: (event: string, fields: Record<string, unknown>) => void;
  private state: SignInSessionState = "IDLE";
  private channel: SignInChannel | null = null;
  private running = false;

  constructor(deps: SignInEndpointDeps = {}) {
    this.recover = deps.recover ?? null;
    this.checkSession = deps.check ?? null;
    this.log = deps.log ?? (() => undefined);
  }

  /** Begin a bounded session, or say why not. Returns as soon as the page is being opened. */
  start(channelCode: unknown): SignInStartResult {
    if (!this.recover) {
      return { ok: false, reason: "no_executor" };
    }
    if (!isSignInChannel(channelCode)) {
      return { ok: false, reason: "unsupported_channel" };
    }
    if (this.running) {
      return { ok: false, reason: "busy" };
    }
    this.running = true;
    this.channel = channelCode;
    this.state = "WAITING";
    this.log("aw_sign_in_session_started", { channelCode });
    // Deliberately not awaited: the press must return now, and the tab learns the answer by asking.
    void this.recover(channelCode)
      .then((outcome) => {
        this.state = outcome;
        this.log("aw_sign_in_session_settled", { channelCode, outcome });
      })
      .catch(() => {
        this.state = "UNAVAILABLE";
        this.log("aw_sign_in_session_settled", { channelCode, outcome: "UNAVAILABLE" });
      })
      .finally(() => {
        this.running = false;
      });
    return { ok: true, state: "WAITING" };
  }

  /**
   * <b>Is this channel signed in right now?</b> One probe, awaited, nothing raised.
   *
   * <p>Asked when a seller comes back to Reviewnary with a read waiting on a sign-in. It answers and that is
   * all: starting the waiting read is the backend's decision, made against the setting that authorised it, not
   * this endpoint's.
   *
   * <p>Refuses while a recovery session is open — there is one browser and someone may be typing in it. The
   * caller then asks {@link #status} instead, which is the answer that session is about to produce anyway.
   */
  async check(channelCode: unknown): Promise<SignInCheckResult> {
    if (!this.checkSession) {
      return { ok: false, reason: "no_executor" };
    }
    if (!isSignInChannel(channelCode)) {
      return { ok: false, reason: "unsupported_channel" };
    }
    if (this.running) {
      return { ok: false, reason: "busy" };
    }
    this.running = true;
    this.log("aw_sign_in_check_started", { channelCode });
    try {
      const state = await this.checkSession(channelCode);
      // A check tells the tab, and it also updates what `status` reports: the two must not disagree about a
      // session that was just looked at. It never becomes WAITING — nothing is waiting.
      this.channel = channelCode;
      this.state = state;
      this.log("aw_sign_in_check_settled", { channelCode, outcome: state });
      return { ok: true, state };
    } catch {
      this.state = "UNAVAILABLE";
      this.log("aw_sign_in_check_settled", { channelCode, outcome: "UNAVAILABLE" });
      return { ok: true, state: "UNAVAILABLE" };
    } finally {
      this.running = false;
    }
  }

  status(): SignInSessionStatus {
    return { state: this.state, channelCode: this.channel };
  }
}
