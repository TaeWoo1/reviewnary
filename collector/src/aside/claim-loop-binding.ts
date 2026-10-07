import type { FixtureObserveLoop } from "./fixture-observe-runner";

/**
 * <b>Which device credential the claim loop is currently asking for work with — and keeping that answer true
 * when the credential changes under it.</b>
 *
 * <h2>The defect this exists to remove</h2>
 *
 * The loop reads its bearer <i>once</i>, at construction, and keeps it in a closure for as long as it runs
 * (`startFixtureObserveLoop` takes a `token`, not a way to get one). That is fine while a helper has exactly
 * one credential for its whole life, and wrong the moment it has two in sequence — which is the ordinary case
 * on a machine that was linked to one Reviewnary account and is then linked to another:
 *
 * <ol>
 *   <li>the helper boots with an old link on disk and starts a loop holding that old token;</li>
 *   <li>the seller presses 「이 기기 연결」 on the new account and a new token is written;</li>
 *   <li>the link hook fires — and a <i>start-once</i> guard sees a loop already running and returns.</li>
 * </ol>
 *
 * The result is the worst available one: a helper that looks linked on both screens, is polling happily, and
 * is polling <b>as the wrong account</b>, so the job the seller just queued is never claimed by anybody. It
 * cost a live verification already. Nothing on either screen said «restart the helper», and requiring that
 * would make the press insufficient again — the exact failure the link hook was added to fix.
 *
 * <h2>What this binds</h2>
 *
 * One rule, stated once: <b>the running loop's credential is the credential on disk, or there is no running
 * loop.</b> Every transition that can change what is on disk — boot, a link landing, a link being revoked or
 * unlinked — calls {@link rebind}, which reads the credential again and makes that sentence true:
 *
 * <ul>
 *   <li>no credential ⇒ no loop (so a forgotten token never keeps polling);</li>
 *   <li>a credential, and the running loop already holds <i>that</i> one ⇒ nothing happens, which is what makes
 *   a duplicate hook call harmless;</li>
 *   <li>a credential the running loop does not hold ⇒ the old loop is stopped <i>before</i> the new one starts,
 *   so there is never a moment with two loops, and never a poll with the old token after the new loop exists.</li>
 * </ul>
 *
 * Rebinds are <b>serialized</b>: each waits for the one before it. Two presses that land together therefore
 * cannot both read «no loop running» and both start one — the duplicate-loop bug this class is supposed to
 * prevent is otherwise reachable through the very hook that fixes the first one.
 *
 * <h2>What it deliberately does not know</h2>
 *
 * The credential never leaves {@link ClaimLoopBindingDeps.readToken} and {@link ClaimLoopBindingDeps.start}:
 * it is compared, never logged and never returned. And this class holds no opinion about <b>browser pairing</b>
 * — a pairing is per-browser and says a tab may drive this helper, a device grant is per-account and says this
 * helper may ask the backend for work. Conflating the two has cost a session; they are two lifecycles and this
 * one is only the second.
 */
export type ClaimLoopRebindReason = "boot" | "linked" | "unlinked";

export interface ClaimLoopBindingDeps {
  /**
   * The device credential this helper holds right now, or `null` when it holds none.
   *
   * Read afresh on every rebind — that re-read is the whole point. A throw is `null`: «not linked to this
   * backend» is the normal state of a freshly installed helper, not an error to crash a resident process on.
   */
  readToken: () => Promise<string | null>;
  /** Build and return a running loop for this credential. Called at most once per credential change. */
  start: (token: string, reason: ClaimLoopRebindReason) => FixtureObserveLoop;
  /** The loop that was running has been stopped. For the operator log; never receives the credential. */
  onStopped?: (reason: ClaimLoopRebindReason) => void;
  /** There is no credential, so there is nothing to ask with. For the operator log. */
  onNoCredential?: (reason: ClaimLoopRebindReason) => void;
}

export class ClaimLoopBinding {
  private readonly deps: ClaimLoopBindingDeps;
  private loop: FixtureObserveLoop | null = null;
  /**
   * The credential the running loop holds, so a rebind can tell «the same link again» from «a different
   * account». Compared only; it is never logged, surfaced or handed back.
   */
  private bound: string | null = null;
  /** The tail of the rebind chain. Serializing on it is what keeps «exactly one loop» true under races. */
  private queue: Promise<void> = Promise.resolve();
  private disposed = false;

  constructor(deps: ClaimLoopBindingDeps) {
    this.deps = deps;
  }

  /** Whether a loop is asking for work right now. */
  get running(): boolean {
    return this.loop !== null;
  }

  /**
   * Make «the running loop's credential is the credential on disk» true again, whatever changed.
   *
   * Safe to call at any time, from any transition, more than once, and concurrently.
   */
  rebind(reason: ClaimLoopRebindReason): Promise<void> {
    this.queue = this.queue.then(() => this.rebindOnce(reason)).catch(() => undefined);
    return this.queue;
  }

  /** Stop asking for work and stay stopped — the shutdown path. */
  stop(): void {
    this.disposed = true;
    this.halt("unlinked");
  }

  private async rebindOnce(reason: ClaimLoopRebindReason): Promise<void> {
    if (this.disposed) {
      return;
    }
    let token: string | null;
    try {
      token = await this.readToken();
    } catch {
      token = null;
    }
    // A shutdown may have landed while the credential was being read; honouring it here means `stop()` is
    // final rather than something a rebind in flight can undo.
    if (this.disposed) {
      return;
    }
    if (token === null) {
      this.halt(reason);
      this.deps.onNoCredential?.(reason);
      return;
    }
    if (this.loop && this.bound === token) {
      return;
    }
    // Stopped BEFORE the replacement starts: for one instant there is no loop, which is the only ordering in
    // which there is never a moment with two.
    this.halt(reason);
    this.loop = this.deps.start(token, reason);
    this.bound = token;
  }

  private async readToken(): Promise<string | null> {
    const token = await this.deps.readToken();
    return token === null || token === undefined || token === "" ? null : token;
  }

  private halt(reason: ClaimLoopRebindReason): void {
    if (!this.loop) {
      return;
    }
    const loop = this.loop;
    this.loop = null;
    this.bound = null;
    try {
      loop.stop();
    } catch {
      // A loop that refuses to stop must not keep this binding from starting the right one; the timer it owns
      // is unref'd either way.
    }
    this.deps.onStopped?.(reason);
  }
}
