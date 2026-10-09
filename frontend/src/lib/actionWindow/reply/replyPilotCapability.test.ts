/**
 * **Guided Reply is a capability of a shipped build, not of a dev server** — pinned by reading the source.
 *
 * The live runtime that fills a composer is reached through three modules: this lane's connect
 * (`replyBridge.ts`), its shared lease (`replyConnection.ts`) and its owner (`useReplyRuntime.ts`). None of
 * them may re-acquire a build-mode gate. The one that used to exist refused with `bridge-disabled` before
 * touching the network in every shipped build, which made the guided lane structurally unreachable — not
 * "unavailable until an agent hosts it", but unreachable even when one did.
 *
 * This test exists because that is not a hypothetical regression: a 2026-09-05 walkthrough read a STALE
 * COMMENT saying the lane was DEV-only and reported it to the product owner as a pilot blocker. A sentence
 * cannot be trusted about this; the source can.
 *
 * The simulated fallback in `replyRuntime.ts` is deliberately NOT covered — it is dev-only on purpose, and
 * production resolving `null` there is what keeps a shipped build from simulating a run nobody ran.
 */
import { describe, it, expect } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { buildCsp } from "../../security/csp";

const HERE = resolve(__dirname);
const LIVE_RUNTIME_MODULES = ["replyBridge.ts", "replyConnection.ts", "useReplyRuntime.ts"];

/** Source with comment lines stripped: prose about a flag is not a use of it. */
function code(file: string): string {
  return readFileSync(resolve(HERE, file), "utf8")
    .split("\n")
    .filter((line) => {
      const t = line.trim();
      return !t.startsWith("//") && !t.startsWith("*") && !t.startsWith("/*");
    })
    .join("\n");
}

describe("the guided-reply runtime is not gated on the build mode", () => {
  for (const file of LIVE_RUNTIME_MODULES) {
    it(`${file} does not consult DEV, the fixture flag, or the dev bridge flag`, () => {
      const src = code(file);
      expect(src).not.toContain("env.DEV");
      expect(src).not.toContain("import.meta.env.DEV");
      expect(src).not.toContain("VITE_AW_BRIDGE");
      expect(src).not.toContain("isBridgeModeEnabled");
      expect(src).not.toContain("isFixturePreviewEnabled");
    });
  }

  it("the helper origin is in the CSP of every shipped build — no flag decides whether the product can reach it", () => {
    // 이 테스트는 반대를 주장하고 있었다: 「플래그가 없으면 helper origin도 없다」가 올바른 상태인 것처럼.
    // 2026-10-09 라이브에서 그 결과를 봤다 — 플래그 없이 만든 production 번들은 sign-in check를 CSP에서
    // 막혔고, 화면에는 「도우미가 응답하지 않습니다」만 남았다. 도우미는 판매자의 제품 기능이지 배포가
    // 선택하는 vendor가 아니다.
    const bare = buildCsp({});
    expect(bare).toContain("http://127.0.0.1:47615");
    expect(bare).toContain("ws://127.0.0.1:47615");
    const named = buildCsp({ VITE_BRIDGE_URL: "http://127.0.0.1:47999" });
    expect(named).toContain("http://127.0.0.1:47999");
    expect(named).toContain("ws://127.0.0.1:47999");
    // 그리고 dock 플래그는 정책을 더 이상 바꾸지 않는다.
    expect(buildCsp({ VITE_ENABLE_AGENT_BRIDGE: "true" } as Record<string, string>)).toEqual(bare);
  });
});
