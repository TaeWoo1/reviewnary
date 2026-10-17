/**
 * Which IP the NAVER call-IP step shows. A seller PC shows its own public IPv4 and NEVER falls back to the hosted
 * service's advertised address (that would tell the seller to register the wrong one); the hosted service shows
 * its advertised egress. Every failure is an empty list.
 */
import { describe, expect, it } from "vitest";
import { fetchNaverCallIps } from "../../src/action-window/call-ip-source";

type Routes = Record<string, { status?: number; body?: unknown } | Error>;

function fakeFetch(routes: Routes) {
  const calls: { url: string; auth: string | null }[] = [];
  const impl = (async (url: string, init?: RequestInit) => {
    const headers = (init?.headers ?? {}) as Record<string, string>;
    calls.push({ url, auth: headers.authorization ?? null });
    const path = url.replace("http://127.0.0.1:18090", "");
    const r = routes[path];
    if (r instanceof Error) throw r;
    if (!r) return new Response("", { status: 404 });
    return new Response(JSON.stringify(r.body ?? {}), { status: r.status ?? 200 });
  }) as unknown as typeof fetch;
  return { impl, calls };
}

const BASE = "http://127.0.0.1:18090";

describe("fetchNaverCallIps", () => {
  it("a linked seller PC shows its own public IPv4, under the helper's bearer", async () => {
    const f = fakeFetch({
      "/api/local/vendor-link": { body: { state: "LINKED" } },
      "/api/local/vendor-link/public-ip": { body: { ipv4: "203.0.113.7", reason: null } },
    });
    expect(await fetchNaverCallIps(BASE, "tok", f.impl)).toEqual(["203.0.113.7"]);
    expect(f.calls.every((c) => c.auth === "Bearer tok")).toBe(true);
    expect(f.calls.map((c) => c.url)).not.toContain(`${BASE}/api/connect/naver/setup`);
  });

  it("**a seller PC with no IP shows none — never the hosted service's address**", async () => {
    const f = fakeFetch({
      "/api/local/vendor-link": { body: { state: "UNLINKED" } },
      "/api/local/vendor-link/public-ip": { body: { ipv4: null, reason: "NOT_LINKED" } },
      "/api/connect/naver/setup": { body: { advertisedEgressIps: ["198.51.100.1"] } },
    });
    expect(await fetchNaverCallIps(BASE, "tok", f.impl)).toEqual([]);
    expect(f.calls.map((c) => c.url)).not.toContain(`${BASE}/api/connect/naver/setup`);
  });

  it("the hosted service shows its advertised egress", async () => {
    const f = fakeFetch({
      "/api/local/vendor-link": { body: { state: "OFF", offReason: "NOT_LOCAL_SELLER" } },
      "/api/connect/naver/setup": { body: { advertisedEgressIps: ["198.51.100.1", "bad"] } },
    });
    expect(await fetchNaverCallIps(BASE, "tok", f.impl)).toEqual(["198.51.100.1"]);
  });

  it("a seller PC switched off for another reason is still a seller PC", async () => {
    const f = fakeFetch({
      "/api/local/vendor-link": { body: { state: "OFF", offReason: "NOT_CONFIGURED" } },
      "/api/local/vendor-link/public-ip": { body: { ipv4: null, reason: "NOT_LINKED" } },
      "/api/connect/naver/setup": { body: { advertisedEgressIps: ["198.51.100.1"] } },
    });
    expect(await fetchNaverCallIps(BASE, "tok", f.impl)).toEqual([]);
  });

  it.each([
    ["a refused status", { "/api/local/vendor-link": { status: 401 } }],
    ["a transport failure", { "/api/local/vendor-link": new Error("connect ECONNREFUSED") }],
    ["a malformed answer", { "/api/local/vendor-link": { body: { state: "LINKED" } }, "/api/local/vendor-link/public-ip": { body: { ipv4: "1.2.3.4; rm" } } }],
  ])("%s is an empty list", async (_label, routes) => {
    expect(await fetchNaverCallIps(BASE, "tok", fakeFetch(routes as Routes).impl)).toEqual([]);
  });
});
