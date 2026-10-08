import { describe, expect, it } from "vitest";
import { ClaimLoopBinding, type ClaimLoopRebindReason } from "../../src/aside/claim-loop-binding";
import type { FixtureObserveLoop } from "../../src/aside/fixture-observe-runner";

/**
 * <b>Whose account is the claim loop asking for work as?</b>
 *
 * The defect: the loop reads its bearer once and keeps it. A machine linked to one account and then linked to
 * another kept polling as the first one, so the job the seller had just pressed for was claimed by nobody —
 * while both screens showed a linked, busy helper. The only way out was restarting the helper, which is the
 * thing the link hook exists to make unnecessary.
 *
 * So these tests are about one sentence: the running loop's credential is the credential on disk, or there is
 * no running loop.
 */

/** A loop that records that it was told to stop, and how often. */
function fakeLoop(label: string, log: string[]): FixtureObserveLoop {
  return {
    stop: () => {
      log.push(`stop:${label}`);
    },
  };
}

interface Harness {
  binding: ClaimLoopBinding;
  /** What the helper holds on disk. Assign to it to stand in for a link, a relink or an unlink. */
  setToken: (t: string | null) => void;
  /** Loops started, in order, by the credential each was given. */
  started: string[];
  /** Every lifecycle event, interleaved: `start:<token>`, `stop:<token>`, `none:<reason>`. */
  events: string[];
  reasons: ClaimLoopRebindReason[];
}

function harness(initial: string | null, opts: { readDelayMs?: number } = {}): Harness {
  let token = initial;
  const started: string[] = [];
  const events: string[] = [];
  const reasons: ClaimLoopRebindReason[] = [];
  const binding = new ClaimLoopBinding({
    readToken: async () => {
      if (opts.readDelayMs) await new Promise((r) => setTimeout(r, opts.readDelayMs));
      return token;
    },
    start: (t, reason) => {
      started.push(t);
      events.push(`start:${t}`);
      reasons.push(reason);
      return fakeLoop(t, events);
    },
    onStopped: () => undefined,
    onNoCredential: (reason) => events.push(`none:${reason}`),
  });
  return { binding, setToken: (t) => (token = t), started, events, reasons };
}

describe("claim loop binding — the loop asks as whoever this helper is now", () => {
  it("a link landing over an older account's link rebinds without a restart", async () => {
    // The live case, exactly: a helper booted with the previous account's token, and the seller then presses
    // 「이 기기 연결」 on the account they are actually using.
    const h = harness("token-old-org");
    await h.binding.rebind("boot");
    expect(h.started).toEqual(["token-old-org"]);

    h.setToken("token-demo-org");
    await h.binding.rebind("linked");

    // Stopped first, then started — never two loops, and no poll with the old token after the new one exists.
    expect(h.events).toEqual(["start:token-old-org", "stop:token-old-org", "start:token-demo-org"]);
    expect(h.binding.running).toBe(true);
    // And the press alone did it: nothing in this test restarted the process.
    expect(h.reasons).toEqual(["boot", "linked"]);
  });

  it("the same link again changes nothing — so a duplicate hook is harmless", async () => {
    const h = harness("token-a");
    await h.binding.rebind("boot");
    await h.binding.rebind("linked");
    await h.binding.rebind("linked");

    expect(h.started).toEqual(["token-a"]);
    expect(h.events).toEqual(["start:token-a"]);
  });

  it("two rebinds racing still leave exactly one loop", async () => {
    // Serialization is the point: without it both calls read «no loop running» while the other is still
    // awaiting its credential, and both start one. The duplicate is then invisible — two desks, one machine.
    const h = harness("token-a", { readDelayMs: 5 });
    await Promise.all([h.binding.rebind("boot"), h.binding.rebind("linked"), h.binding.rebind("linked")]);

    expect(h.started).toEqual(["token-a"]);
    expect(h.binding.running).toBe(true);
  });

  it("losing the link stops the loop — a forgotten token never keeps polling", async () => {
    const h = harness("token-a");
    await h.binding.rebind("boot");

    h.setToken(null); // the seller unlinked, or the backend answered 401 and the dead token was dropped
    await h.binding.rebind("unlinked");

    expect(h.events).toEqual(["start:token-a", "stop:token-a", "none:unlinked"]);
    expect(h.binding.running).toBe(false);
  });

  it("unlink then relink gives one loop on the new credential", async () => {
    const h = harness("token-a");
    await h.binding.rebind("boot");
    h.setToken(null);
    await h.binding.rebind("unlinked");
    h.setToken("token-b");
    await h.binding.rebind("linked");

    expect(h.started).toEqual(["token-a", "token-b"]);
    expect(h.binding.running).toBe(true);
  });

  it("an unlinked helper starts nothing at boot, and the later press is what starts it", async () => {
    // The ordinary install: nothing on disk yet. This must stay a quiet skip, not an error and not a loop.
    const h = harness(null);
    await h.binding.rebind("boot");
    expect(h.binding.running).toBe(false);
    expect(h.events).toEqual(["none:boot"]);

    h.setToken("token-new");
    await h.binding.rebind("linked");
    expect(h.started).toEqual(["token-new"]);
  });

  it("a restored link still starts the loop at boot — the old behaviour, unregressed", async () => {
    const h = harness("token-restored");
    await h.binding.rebind("boot");
    expect(h.started).toEqual(["token-restored"]);
    expect(h.reasons).toEqual(["boot"]);
  });

  it("a credential that cannot be read is «no credential», not a crash", async () => {
    const events: string[] = [];
    const binding = new ClaimLoopBinding({
      readToken: async () => {
        throw new Error("home unreadable");
      },
      start: () => fakeLoop("never", events),
      onNoCredential: (reason) => events.push(`none:${reason}`),
    });
    await binding.rebind("boot");
    expect(binding.running).toBe(false);
    expect(events).toEqual(["none:boot"]);
  });

  it("stop() is final — a rebind already in flight cannot bring the loop back", async () => {
    // Shutdown and a link landing can interleave. A helper that is going away must not open a new lane on the
    // way out, because nothing will ever stop it.
    const h = harness("token-a", { readDelayMs: 5 });
    const inFlight = h.binding.rebind("linked");
    h.binding.stop();
    await inFlight;

    expect(h.started).toEqual([]);
    expect(h.binding.running).toBe(false);
  });

  it("stop() stops a running loop and nothing restarts it", async () => {
    const h = harness("token-a");
    await h.binding.rebind("boot");
    h.binding.stop();
    expect(h.events).toEqual(["start:token-a", "stop:token-a"]);

    await h.binding.rebind("linked");
    expect(h.started).toEqual(["token-a"]);
    expect(h.binding.running).toBe(false);
  });

  it("a loop that throws on stop does not keep the right one from starting", async () => {
    const started: string[] = [];
    let token = "token-a";
    const binding = new ClaimLoopBinding({
      readToken: async () => token,
      start: (t) => {
        started.push(t);
        return {
          stop: () => {
            throw new Error("refused");
          },
        };
      },
    });
    await binding.rebind("boot");
    token = "token-b";
    await binding.rebind("linked");

    expect(started).toEqual(["token-a", "token-b"]);
    expect(binding.running).toBe(true);
  });

  /** The same binding, with the stop reason recorded — the one thing the shared harness deliberately drops. */
  function withStopReasons(initial: string) {
    let token: string | null = initial;
    const stops: ClaimLoopRebindReason[] = [];
    const binding = new ClaimLoopBinding({
      readToken: async () => token,
      start: (t) => fakeLoop(t, []),
      onStopped: (reason) => stops.push(reason),
    });
    return { binding, stops, forget: () => (token = null) };
  }

  it("종료는 해제가 아니다 — 두 사건이 같은 단어로 적히지 않는다", async () => {
    // 2026-10-08 재설치: 평범한 종료가 `aside_fixture_loop_stopped {"reason":"unlinked"}`로 찍혔다. 이 축에서
    // unlink는 「판매자의 grant가 사라졌고 디스크의 자격도 지워졌다」는 유일한 사건이라, 그 줄을 읽은 운영자는
    // 아무것도 revoke되지 않았다는 것을 확인하려고 `.auth/device.json`을 직접 열어야 했다.
    const h = withStopReasons("token-a");
    await h.binding.rebind("boot");
    h.binding.stop();
    expect(h.stops).toEqual(["shutdown"]);

    // 그리고 진짜 해제는 여전히 해제로 적힌다 — 구분이 생겼다는 것은 양쪽이 각자 제 이름을 갖는다는 뜻이다.
    const g = withStopReasons("token-a");
    await g.binding.rebind("boot");
    g.forget();
    await g.binding.rebind("unlinked");
    expect(g.stops).toEqual(["unlinked"]);
  });

  it("an empty credential is no credential", async () => {
    // `""` reaching the loop would be a bearer header with nothing in it: every poll 401s forever, and the
    // operator log says the lane is up.
    const h = harness("");
    await h.binding.rebind("boot");
    expect(h.binding.running).toBe(false);
    expect(h.events).toEqual(["none:boot"]);
  });
});
