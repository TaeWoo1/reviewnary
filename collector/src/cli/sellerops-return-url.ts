/**
 * **Where `SellerOps로 돌아가기` is allowed to send the seller.** Pure — it parses and decides; it opens nothing.
 *
 * The guided walk's last step promises a return to SellerOps, and something has to navigate for that promise to
 * be true. That "something" is a navigation of the seller's own dedicated window, driven by a button on a
 * marketplace page — so the destination is screened the same way the WING landing is, and fails closed.
 *
 * The rule is deliberately narrow: **the local SellerOps UI on loopback, and nothing else.** The configured app
 * URL is operator-supplied, and an operator-supplied string that can steer a browser window is a string worth
 * bounding. Only the ORIGIN survives screening — any path, query or fragment on the configured value is
 * discarded and replaced with the connect route, so nothing can ride along in it.
 */

/** The route the Coupang walk returns to: the Coupang connect screen, where the connection is completed. */
export const SELLEROPS_COUPANG_CONNECT_PATH = "/connect/coupang";

/**
 * The route the NAVER walk returns to: the NAVER connect screen, where the seller types the two values in.
 *
 * Until 2026-10-17 there was one route for both walks, so the NAVER walk's last step opened the COUPANG connect
 * screen — found when the 4차 Phase B harness was first given the product's return. It carries no resume marker:
 * the NAVER screen has no reader for one, and a marker nothing reads would be a promise nobody keeps.
 */
export const SELLEROPS_NAVER_CONNECT_PATH = "/connect/naver";

/** Which walk is returning — it decides the route, and nothing else. */
export type SellerOpsReturnChannel = "coupang" | "naver";

/**
 * **The marker that says "this is a RETURN, not an arrival"** — the whole of D2's URL half.
 *
 * Reported live on 2026-08-13: the seller pressed the walk's return and landed on `쿠팡 연결 안내 시작` /
 * `이미 키가 있어요`, i.e. the beginning of the flow, having just finished it. The cause is not a wrong route.
 * The connect page resolves its phase from persisted state — correct, and for a seller mid-issuance nothing is
 * persisted yet — and the walkthrough component's "have we started" flag is component-local, so a fresh tab
 * starts at the start CTA. Neither side is wrong on its own; nothing was carrying the fact that a run is
 * already in flight.
 *
 * This carries it, and carries nothing else. It is not a run id and makes no identity claim — the agent hosts
 * exactly one issuance run and the frontend adopts it over the bridge, so the only thing missing was
 * permission to skip the start gate. A forged value can therefore do nothing a seller cannot do by pressing
 * the CTA themselves.
 *
 * The frontend's reader (`isIssuanceResumeReturn`) is pinned against this constant by a cross-stack test.
 */
export const SELLEROPS_ISSUANCE_RESUME_QUERY = "issuance=resume";

/**
 * **The walkthrough run a local proof's return must carry** (2026-10-19, run 9).
 *
 * A bootstrapped walk frontend refuses any `/connect/naver` tab whose address names no `walkthroughRun`
 * (`MISSING_URL_RUN`), so the walk's own return landed the operator on 「환경 확인 실패」 — every NAVER proof run did,
 * and none looked at the screen it ended on. The live harness passes its bootstrap run id; the product carrier passes
 * nothing and its return is unchanged. The id is bootstrap's shape (`wt-` + 12 hex) or the return is refused: it is
 * the one thing besides the origin that may ride along, so it is held to the one shape bootstrap mints.
 */
export const SELLEROPS_WALKTHROUGH_RUN_QUERY = "walkthroughRun";
const WALKTHROUGH_RUN_ID = /^wt-[0-9a-f]{12}$/;

/** Why a return destination was refused. Fixed enum — it is logged, so it carries no URL and no host. */
export type SellerOpsReturnRefusal = "EMPTY" | "UNPARSEABLE" | "NOT_HTTP" | "NOT_LOOPBACK" | "BAD_WALKTHROUGH_RUN";

export type SellerOpsReturnScreen =
  | { readonly ok: true; readonly url: string }
  | { readonly ok: false; readonly reason: SellerOpsReturnRefusal };

/**
 * The hosts that ARE this machine. The local agent's UI is served from loopback and nowhere else; a SellerOps
 * deployment on a real domain is not something a marketplace-page button should be able to navigate to, and if
 * that ever becomes a product requirement it should arrive as its own decision rather than through this list.
 */
const LOOPBACK_HOSTS: readonly string[] = ["localhost", "127.0.0.1", "[::1]", "::1"];

/**
 * Whether `url` is EXACTLY what {@link screenSellerOpsReturnUrl} produces — a loopback SellerOps origin on the
 * connect route, and nothing else.
 *
 * The screening function answers "what may this configured value become"; this answers "is this string already
 * that". They are different questions and the second one is what a process launcher needs: `os-open-url.ts`
 * builds an argv for the seller's real browser, so it re-establishes the property rather than trusting its caller
 * to have run the screening first.
 */
export function isSellerOpsReturnUrl(url: string): boolean {
  let walkthroughRunId: string | undefined;
  try {
    walkthroughRunId = new URL(url).searchParams.get(SELLEROPS_WALKTHROUGH_RUN_QUERY) ?? undefined;
  } catch {
    return false;
  }
  return (["coupang", "naver"] as const).some((channel) => {
    const screened = screenSellerOpsReturnUrl(url, channel, channel === "naver" ? walkthroughRunId : undefined);
    return screened.ok && screened.url === url;
  });
}

/**
 * Screen a configured SellerOps app URL into the exact return destination, or refuse with a reason.
 * `walkthroughRunId` is the NAVER local proof's bootstrap run ({@link SELLEROPS_WALKTHROUGH_RUN_QUERY}); the
 * Coupang route ignores it.
 */
export function screenSellerOpsReturnUrl(
  appUrl: string | undefined,
  channel: SellerOpsReturnChannel = "coupang",
  walkthroughRunId?: string,
): SellerOpsReturnScreen {
  const raw = (appUrl ?? "").trim();
  if (raw === "") return { ok: false, reason: "EMPTY" };
  let parsed: URL;
  try {
    parsed = new URL(raw);
  } catch {
    return { ok: false, reason: "UNPARSEABLE" };
  }
  if (parsed.protocol !== "http:" && parsed.protocol !== "https:") return { ok: false, reason: "NOT_HTTP" };
  if (!LOOPBACK_HOSTS.includes(parsed.hostname)) return { ok: false, reason: "NOT_LOOPBACK" };
  // ORIGIN ONLY. Everything else about the configured value is dropped rather than carried forward — the
  // route and the resume marker are ours, not the operator's.
  if (channel === "naver") {
    if (walkthroughRunId === undefined) return { ok: true, url: `${parsed.origin}${SELLEROPS_NAVER_CONNECT_PATH}` };
    if (!WALKTHROUGH_RUN_ID.test(walkthroughRunId)) return { ok: false, reason: "BAD_WALKTHROUGH_RUN" };
    return { ok: true, url: `${parsed.origin}${SELLEROPS_NAVER_CONNECT_PATH}?${SELLEROPS_WALKTHROUGH_RUN_QUERY}=${walkthroughRunId}` };
  }
  return { ok: true, url: `${parsed.origin}${SELLEROPS_COUPANG_CONNECT_PATH}?${SELLEROPS_ISSUANCE_RESUME_QUERY}` };
}
