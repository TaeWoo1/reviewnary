/**
 * **The reply graph reaches five backend routes and nothing else, with the run token as its only credential**
 * (Reply Agent v1, 2026-10-28). The token is never logged and never echoed; a failed step names its step and status only.
 */
import { afterEach, describe, expect, it } from "vitest";
import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { REPLY_RUN_ROUTES, ReplySpringClient, ReplyStepError } from "../../src/reply/ReplySpringClient";
import { runReply } from "../../src/reply/replyGraph";
import { clearLogSink, getLogSink } from "../../src/log";

const TOKEN = "rvr_" + "a".repeat(43);

function recordingFetch(answers: Record<string, unknown>) {
  const seen: { url: string; auth: string | null; hasSignal: boolean }[] = [];
  const impl = (async (url: string, init: RequestInit) => {
    const headers = init.headers as Record<string, string>;
    seen.push({ url, auth: headers.Authorization ?? null, hasSignal: init.signal != null });
    const path = new URL(url).pathname;
    const body = answers[path];
    return new Response(body === undefined ? "" : JSON.stringify(body), { status: 200 });
  }) as unknown as typeof fetch;
  return { seen, impl };
}

afterEach(() => clearLogSink());

describe("reply client — the run's only way to the backend", () => {
  it("calls only the run routes, each with the run token as bearer and a timeout", async () => {
    const { seen, impl } = recordingFetch({
      [REPLY_RUN_ROUTES.classify]: { category: "P", needsResponse: false, handoff: false },
      [REPLY_RUN_ROUTES.draft]: { written: true, revisions: 0 },
      [REPLY_RUN_ROUTES.critique]: { passed: true, failed: [] },
      [REPLY_RUN_ROUTES.save]: { saved: true, version: 1, reason: null },
    });
    const logs = getLogSink();
    const report = await runReply(new ReplySpringClient("http://backend", TOKEN, impl));
    expect(report.outcome).toBe("SAVED");
    const allowed = new Set(Object.values(REPLY_RUN_ROUTES));
    for (const call of seen) {
      expect(allowed.has(new URL(call.url).pathname as never)).toBe(true);
      expect(call.auth).toBe(`Bearer ${TOKEN}`);
      expect(call.hasSignal).toBe(true);
    }
    expect(seen.map((c) => new URL(c.url).pathname)).toEqual([
      REPLY_RUN_ROUTES.classify, REPLY_RUN_ROUTES.draft, REPLY_RUN_ROUTES.critique, REPLY_RUN_ROUTES.save,
      REPLY_RUN_ROUTES.finish,
    ]);
    expect(JSON.stringify(logs)).not.toContain(TOKEN);
  });

  it("a failed step throws its step and status — never the response body", async () => {
    const impl = (async () => new Response("고객 리뷰 본문", { status: 500 })) as unknown as typeof fetch;
    const error = await new ReplySpringClient("http://backend", TOKEN, impl).classify().catch((e) => e);
    expect(error).toBeInstanceOf(ReplyStepError);
    expect(error.step).toBe("classify");
    expect(String(error.message)).not.toContain("고객");
  });

  it("the reply package holds no checkpointer, run store or second credential", () => {
    const dir = join(__dirname, "../../src/reply");
    for (const file of readdirSync(dir).filter((f) => f.endsWith(".ts"))) {
      const code = readFileSync(join(dir, file), "utf8").replace(/\/\*[\s\S]*?\*\/|\/\/.*$/gm, "");
      expect(code.length, file).toBeGreaterThan(0);
      for (const banned of ["MemorySaver", "checkpointer", "RunStore", "writeFile", "localStorage", "process.env"]) {
        expect(code, `${file} must not use ${banned}`).not.toContain(banned);
      }
    }
  });
});
