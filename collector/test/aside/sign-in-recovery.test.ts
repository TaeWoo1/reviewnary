import { describe, expect, it, vi } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import {
  asideSignInRuntime,
  SIGN_IN_BOUND_MS,
  SIGN_IN_POLL_MS,
  type SignInRuntimePlan,
  type SignInRuntimeTabLike,
} from "../../src/aside/sign-in-runtime";
import {
  buildSignInRuntimePlan,
  buildSignInRuntimeProgram,
  isSignInChannel,
  signInOutcomeOf,
} from "../../src/aside/sign-in-executor";
import { SignInEndpoint } from "../../src/bridge/sign-in-endpoint";
import { buildNaverReviewAuthScript } from "../../src/naver/review-list-observe-inpage";
import { buildWingAuthScript } from "../../src/action-window/coupang-review/wing-identity-inpage";
import { NAVER_REVIEW_LIST_URL } from "../../src/aside/naver-review-workflow";

/**
 * <b>A read that met the channel's sign-in wall left the seller with nowhere to go.</b>
 *
 * The window the acquisition drives is Aside's, not one the seller can open, so 「로그인한 뒤 다시 수집해
 * 주세요」 was advice about a session they could not reach (measured twice on 2026-10-07/08 — Coupang and
 * NAVER both). This lane opens that session: the same page, the same profile, and then it watches.
 *
 * What these tests mostly pin is what it must NOT do. A recovery that typed anything would be a credential
 * path, and this repository does not have one.
 */

const plan: SignInRuntimePlan = {
  entryUrl: "https://example.test/#/review/search",
  authScript: "(function(){return{signedIn:false}})()",
  settleTimeoutMs: 1_000,
  pollMs: 10,
  boundMs: 100,
};

/** A page that answers `signedIn` false until the Nth probe, then true — a seller finishing their login. */
function page(signedInOnProbe: number | null, probes: string[] = []): SignInRuntimeTabLike {
  let n = 0;
  return {
    evaluate: async (script: string) => {
      probes.push(script);
      n += 1;
      return { signedIn: signedInOnProbe !== null && n >= signedInOnProbe };
    },
    waitForLoadState: async () => undefined,
  };
}

const now = () => {
  let t = 0;
  return () => (t += 10);
};

describe("sign-in recovery — the seller signs in, this watches", () => {
  it("answers SIGNED_IN as soon as the probe sees a signed-in store", async () => {
    const opened: string[] = [];
    const result = await asideSignInRuntime(
      plan,
      { openTab: async (url) => (opened.push(url), page(1)) },
      async () => undefined,
      now(),
    );

    expect(result).toMatchObject({ ok: true, state: "SIGNED_IN" });
    // The page it opened is the channel's published entry route — the same one the read opens.
    expect(opened).toEqual([plan.entryUrl]);
  });

  it("the first probe is immediate — someone already signed in is not made to wait out a poll", async () => {
    const waits: number[] = [];
    await asideSignInRuntime(
      plan,
      { openTab: async () => page(1) },
      async (ms) => void waits.push(ms),
      now(),
    );
    expect(waits).toEqual([]);
  });

  it("waits across polls while the seller is typing, then answers SIGNED_IN", async () => {
    const waits: number[] = [];
    const result = await asideSignInRuntime(
      plan,
      { openTab: async () => page(3) },
      async (ms) => void waits.push(ms),
      now(),
    );

    expect(result).toMatchObject({ ok: true, state: "SIGNED_IN" });
    expect(waits).toEqual([plan.pollMs, plan.pollMs]);
  });

  it("the bound elapsing is NOT_SIGNED_IN, not a failure — MFA takes longer than any bound we may pick", async () => {
    const result = await asideSignInRuntime(
      plan,
      { openTab: async () => page(null) },
      async () => undefined,
      now(),
    );
    expect(result).toMatchObject({ ok: true, state: "NOT_SIGNED_IN" });
  });

  it("never closes the tab — the seller is standing in it", async () => {
    // `closeTab` is not even handed to the program: the env this runtime receives has one member.
    const env = { openTab: async () => page(1) };
    expect(Object.keys(env)).toEqual(["openTab"]);
    await asideSignInRuntime(plan, env, async () => undefined, now());
  });

  it("sends the page nothing but the read-only probe", async () => {
    const probes: string[] = [];
    await asideSignInRuntime(
      plan,
      { openTab: async () => page(2, probes) },
      async () => undefined,
      now(),
    );

    // Every script this lane evaluates is the plan's own probe, unmodified. Nothing else is sent, so there is
    // no place for a value to be typed even by mistake.
    expect(new Set(probes)).toEqual(new Set([plan.authScript]));
  });

  it("a page that cannot be opened says so, and claims nothing about the session", async () => {
    const result = await asideSignInRuntime(
      plan,
      {
        openTab: async () => {
          throw new Error("no browser");
        },
      },
      async () => undefined,
      now(),
    );
    expect(result).toEqual({ ok: false, state: "UNAVAILABLE", stage: "OPEN", elapsedMs: 10 });
  });

  it("a probe that never runs is UNAVAILABLE, not NOT_SIGNED_IN", async () => {
    // «The store is signed out» and «we could not look» are different answers, and only one of them should
    // send the seller back to a login page.
    const result = await asideSignInRuntime(
      plan,
      {
        openTab: async () => ({
          evaluate: async () => {
            throw new Error("detached");
          },
        }),
      },
      async () => undefined,
      now(),
    );
    expect(result).toMatchObject({ ok: false, state: "UNAVAILABLE", stage: "PROBE" });
  });

  it("the shipped bound and poll are bounded, slow, and inside Aside's own ceiling", () => {
    expect(SIGN_IN_POLL_MS).toBeGreaterThanOrEqual(3_000);
    expect(SIGN_IN_BOUND_MS).toBeGreaterThan(SIGN_IN_POLL_MS);
    // One session is one `repl` call, and Aside kills a program at 120s — a bound above that loses the answer.
    expect(SIGN_IN_BOUND_MS).toBeLessThan(120_000);
  });
});

describe("the plan is the channel's own published surface", () => {
  it("NAVER reuses the read workflow's route and the acquisition's own probe", () => {
    const built = buildSignInRuntimePlan("NAVER");
    expect(built.entryUrl).toBe(NAVER_REVIEW_LIST_URL);
    // Same probe as the read, so 「로그인됨」 cannot mean one thing here and another thing there.
    expect(built.authScript).toBe(buildNaverReviewAuthScript());
  });

  it("COUPANG reuses the WING workflow's route and the WING probe", () => {
    const built = buildSignInRuntimePlan("COUPANG");
    expect(built.authScript).toBe(buildWingAuthScript());
    expect(built.entryUrl.startsWith("https://")).toBe(true);
  });

  it("only the two channels whose reads can meet a sign-in wall have a recovery", () => {
    expect(isSignInChannel("NAVER")).toBe(true);
    expect(isSignInChannel("COUPANG")).toBe(true);
    for (const other of ["CAFE24", "GMARKET", "", null, undefined, 1]) {
      expect(isSignInChannel(other)).toBe(false);
    }
  });

  it("the shipped program writes nothing to the page and carries no login URL of our own", () => {
    const program = buildSignInRuntimeProgram(buildSignInRuntimePlan("NAVER"));
    // `password` DOES appear, and correctly: the channel's probe counts `input[type="password"]` to decide
    // that a store is signed out. Reading that a field exists is the opposite of filling it, so the rule
    // worth pinning is the writing calls — and there are none.
    for (const write of ["submit()", ".type(", ".fill(", ".click(", ".press(", "setInputValue"]) {
      expect(program).not.toContain(write);
    }
    // The only URL in the program is the channel's published entry route. No login URL of our own exists,
    // because a signed-out channel redirects that route to its own sign-in page.
    const urls = program.match(/https?:\/\/[^"'\\\s]+/g) ?? [];
    expect(new Set(urls)).toEqual(new Set([NAVER_REVIEW_LIST_URL]));
    // It asks for exactly one global, and closing the tab is not among the things it can do.
    expect(program).toContain("{ openTab }");
    expect(program).not.toContain("closeTab");
  });

  it("this lane's source types nothing into a page", () => {
    // The structural version of the rule above: the two files that make up this lane contain no page-writing
    // call at all, so a future edit that added one would have to add it here and be seen.
    for (const file of ["sign-in-runtime.ts", "sign-in-executor.ts"]) {
      const code = readFileSync(resolve(__dirname, "../../src/aside", file), "utf8")
        .replace(/\/\*[\s\S]*?\*\//g, "")
        .replace(/(^|[^:])\/\/.*$/gm, "$1");
      for (const write of [".fill(", ".type(", ".click(", ".press(", "submit()", "setInputValue"]) {
        expect(code).not.toContain(write);
      }
    }
  });

  it("a malformed runtime answer is UNAVAILABLE, never a sign-in", () => {
    expect(signInOutcomeOf({ ok: true, state: "SIGNED_IN" })).toBe("SIGNED_IN");
    expect(signInOutcomeOf({ ok: true, state: "NOT_SIGNED_IN" })).toBe("NOT_SIGNED_IN");
    for (const junk of [null, undefined, 1, "SIGNED_IN", {}, { state: "YES" }, { signedIn: true }]) {
      expect(signInOutcomeOf(junk)).toBe("UNAVAILABLE");
    }
  });
});

describe("one browser, one person standing in it", () => {
  it("a press starts a bounded session and returns at once", async () => {
    let release: (v: "SIGNED_IN") => void = () => undefined;
    const endpoint = new SignInEndpoint({
      recover: () => new Promise((r) => (release = r)),
    });

    expect(endpoint.start("NAVER")).toEqual({ ok: true, state: "WAITING" });
    expect(endpoint.status()).toEqual({ state: "WAITING", channelCode: "NAVER" });

    release("SIGNED_IN");
    await vi.waitFor(() => expect(endpoint.status().state).toBe("SIGNED_IN"));
  });

  it("a second press while one session is open is refused, not queued", async () => {
    const endpoint = new SignInEndpoint({ recover: () => new Promise(() => undefined) });
    endpoint.start("NAVER");
    expect(endpoint.start("COUPANG")).toEqual({ ok: false, reason: "busy" });
    // And the refused press did not re-point the session at the other channel.
    expect(endpoint.status().channelCode).toBe("NAVER");
  });

  it("the desk is free again once a session settles", async () => {
    const endpoint = new SignInEndpoint({ recover: async () => "NOT_SIGNED_IN" });
    endpoint.start("NAVER");
    await vi.waitFor(() => expect(endpoint.status().state).toBe("NOT_SIGNED_IN"));
    expect(endpoint.start("NAVER")).toEqual({ ok: true, state: "WAITING" });
  });

  it("an install with no marketplace browser offers no door to a room it does not have", () => {
    const endpoint = new SignInEndpoint({});
    expect(endpoint.start("NAVER")).toEqual({ ok: false, reason: "no_executor" });
    expect(endpoint.status()).toEqual({ state: "IDLE", channelCode: null });
  });

  it("a channel with no recovery is refused by name", () => {
    const endpoint = new SignInEndpoint({ recover: async () => "SIGNED_IN" });
    expect(endpoint.start("CAFE24")).toEqual({ ok: false, reason: "unsupported_channel" });
    expect(endpoint.start(null)).toEqual({ ok: false, reason: "unsupported_channel" });
  });

  it("a session that throws is UNAVAILABLE and leaves the desk usable", async () => {
    const endpoint = new SignInEndpoint({
      recover: async () => {
        throw new Error("aside gone");
      },
    });
    endpoint.start("NAVER");
    await vi.waitFor(() => expect(endpoint.status().state).toBe("UNAVAILABLE"));
    expect(endpoint.start("NAVER")).toEqual({ ok: true, state: "WAITING" });
  });

  it("the operator log gets closed words — never a URL, never a page", async () => {
    const lines: Array<[string, Record<string, unknown>]> = [];
    const endpoint = new SignInEndpoint({
      recover: async () => "SIGNED_IN",
      log: (event, fields) => lines.push([event, fields]),
    });
    endpoint.start("NAVER");
    await vi.waitFor(() => expect(lines).toHaveLength(2));

    expect(lines[0]).toEqual(["aw_sign_in_session_started", { channelCode: "NAVER" }]);
    expect(lines[1]).toEqual(["aw_sign_in_session_settled", { channelCode: "NAVER", outcome: "SIGNED_IN" }]);
    expect(JSON.stringify(lines)).not.toContain("http");
  });
});
