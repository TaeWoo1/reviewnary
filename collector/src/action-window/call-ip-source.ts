/**
 * **Which IP the NAVER call-IP step shows** — asked of the helper's own SellerOps backend, never guessed.
 *
 * Two installs, two answers (docs/vendor_server_contract_v1.md §7, scope lock v1.14 ⑧-4):
 *  - **a seller-PC install** calls NAVER from this PC, so the IP to register is this PC's public IPv4, which the
 *    backend learns from the vendor server (`/api/local/vendor-link/public-ip`). No address ⇒ nothing is shown —
 *    falling back to the service's advertised address would tell the seller to register the wrong one.
 *  - **the hosted service** calls NAVER from its fixed egress, which the backend publishes as
 *    `advertisedEgressIps` (`/api/connect/naver/setup`).
 *
 * The two are told apart by the link status (`state: OFF` with `offReason: NOT_LOCAL_SELLER`), because the IP
 * route itself answers `NOT_LINKED` for both "not a seller PC" and "a seller PC not yet linked".
 *
 * Every failure is an empty list. Nothing caught is logged — a transport error can quote the request it failed on.
 * The result is screened to IPv4 again by the panel before any of it reaches a page.
 */
import { log } from "../log";
import { copyableIpv4s } from "./overlay-copy-values";

type FetchImpl = typeof fetch;

async function getJson(fetchImpl: FetchImpl, url: string, token: string): Promise<unknown> {
  const res = await fetchImpl(url, { method: "GET", headers: { authorization: `Bearer ${token}` } });
  if (!res.ok) {
    log("aw_issuance_call_ip_source_refused", { httpStatus: res.status });
    return null;
  }
  return res.json();
}

function field(body: unknown, key: string): unknown {
  return body !== null && typeof body === "object" ? (body as Record<string, unknown>)[key] : undefined;
}

/** The IPv4 address(es) the seller registers in NAVER's 「API호출 IP」, or `[]`. */
export async function fetchNaverCallIps(baseUrl: string, token: string, fetchImpl: FetchImpl = fetch): Promise<string[]> {
  try {
    const link = await getJson(fetchImpl, `${baseUrl}/api/local/vendor-link`, token);
    if (link === null) return [];
    const hosted = field(link, "state") === "OFF" && field(link, "offReason") === "NOT_LOCAL_SELLER";
    if (hosted) {
      const setup = await getJson(fetchImpl, `${baseUrl}/api/connect/naver/setup`, token);
      const ips = field(setup, "advertisedEgressIps");
      return copyableIpv4s(Array.isArray(ips) ? ips : []);
    }
    const view = await getJson(fetchImpl, `${baseUrl}/api/local/vendor-link/public-ip`, token);
    return copyableIpv4s([field(view, "ipv4")]);
  } catch {
    log("aw_issuance_call_ip_source_refused", { reason: "TRANSPORT" });
    return [];
  }
}
