/**
 * **`POST /api/reply-runs` takes a backend-minted run token and nothing else** (Reply Agent v1, 2026-10-28) — a seller's
 * login, a malformed token or extra fields are refused before any backend call.
 */
import type { AddressInfo } from "node:net";
import type { Server } from "node:http";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { createHttpServer } from "../../src/http/server";
import { AgentRunService } from "../../src/http/AgentRunService";
import { RunStoreProvider } from "../../src/http/runStoreProvider";
import type { RuntimeConfig } from "../../src/http/config";

const CONFIG: RuntimeConfig = {
  port: 0,
  backendBaseUrl: "http://127.0.0.1:9",
  env: "development",
  runStoreKind: "memory",
  runStoreDir: "./unused",
  corsAllowedOrigins: [],
};

describe("reply-run route", () => {
  let server: Server;
  let base: string;

  beforeAll(async () => {
    const service = new AgentRunService({
      storeProvider: new RunStoreProvider(CONFIG),
      clientFactory: () => {
        throw new Error("the reply route must not build a seller client");
      },
      env: "development",
    });
    server = createHttpServer(service, CONFIG);
    await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
    base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  });

  afterAll(async () => {
    await new Promise<void>((resolve) => server.close(() => resolve()));
  });

  const post = (body: unknown) =>
    fetch(`${base}/api/reply-runs`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });

  it("refuses a seller's login, a malformed token, and extra fields", async () => {
    const runId = "4f6d2c1e-8a7b-4c3d-9e0f-1a2b3c4d5e6f";
    for (const body of [
      { runId, token: "eyJhbGciOiJIUzI1NiJ9.seller.jwt" },
      { runId, token: "rvr_short" },
      { runId: "not-a-uuid", token: "rvr_" + "a".repeat(43) },
      { runId, token: "rvr_" + "a".repeat(43), reviewId: "x" },
    ]) {
      expect((await post(body)).status, JSON.stringify(body).slice(0, 40)).toBe(400);
    }
  });

  it("a well-formed run whose backend cannot be reached ends FAILED with the step's name", async () => {
    const res = await post({ runId: "4f6d2c1e-8a7b-4c3d-9e0f-1a2b3c4d5e6f", token: "rvr_" + "a".repeat(43) });
    expect(res.status).toBe(200);
    const report = (await res.json()) as { outcome: string; reason: string };
    expect(report.outcome).toBe("FAILED");
    expect(report.reason).toBe("STEP_CLASSIFY");
  });
});
