/**
 * **The bound NAVER Seller Center 리뷰 read workflow** — the one route this recipe may open, and its version.
 *
 * The route is observed, not invented: `#/review/search` is the Seller Center 리뷰 관리 route the guided reply lane
 * has used live since 2026-09-03, and the READ-ONLY discovery of 2026-09-17 opened exactly this URL on the logged-in
 * surface. A changed route surfaces as a fail-closed read (`ROUTE_MISMATCH`), never as a silently different page.
 *
 * Pure: no I/O, no browser, no network.
 */

export interface NaverReviewWorkflow {
  readonly id: string;
  readonly version: number;
  readonly entryUrl: string;
  /** How long the grid may take to fill before the read is refused. */
  readonly settleTimeoutMs: number;
}

export const NAVER_SELLER_CENTER_HOST = "sell.smartstore.naver.com";
export const NAVER_REVIEW_SEARCH_ROUTE = "#/review/search";
export const NAVER_REVIEW_LIST_URL = `https://${NAVER_SELLER_CENTER_HOST}/${NAVER_REVIEW_SEARCH_ROUTE}`;

/**
 * **The two controls a historical window read is allowed to touch, as selectors.**
 *
 * <p>They are PREDICATES, not addresses. `input[type=date]` plus a class naming date/calendar/picker is the
 * grounded predicate the live-proven range census already uses to find this list's period fields
 * (`review-list-range-inpage.ts`, 2026-09-05) — written here as CSS so a locator can act through it, and
 * narrowed to what a person could act on (`:visible`, not disabled). The 조회/검색 control is identified by the
 * word the list prints on it.
 *
 * <p><b>Neither has been proved against the live surface.</b> The census has — it counted two date inputs on
 * this screen — but acting through a selector is a different claim from counting through a predicate, and the
 * only honest way to hold that gap is to refuse rather than guess: the runtime requires EXACTLY two date
 * inputs and EXACTLY one search control, and reports the count it saw when that is not what it found. A first
 * live catch-up therefore either works or stops with a number that says which predicate was wrong.
 */
export const NAVER_REVIEW_DATE_INPUT_SELECTOR =
  'input[type="date"]:visible:not([disabled]), input[class*="date" i]:visible:not([disabled]), '
  + 'input[class*="calendar" i]:visible:not([disabled]), input[class*="picker" i]:visible:not([disabled])';

/** 조회 or 검색 — the list's own «show me that period». One match, or the read stops. */
export const NAVER_REVIEW_SEARCH_SELECTOR =
  'button:visible:has-text("조회"), a:visible:has-text("조회"), '
  + 'button:visible:has-text("검색"), a:visible:has-text("검색")';

export const NAVER_REVIEW_READ_WORKFLOW: NaverReviewWorkflow = Object.freeze({
  id: "naver-seller-center-review-read",
  version: 1,
  entryUrl: NAVER_REVIEW_LIST_URL,
  settleTimeoutMs: 45_000,
});

export type NaverReviewWorkflowError = "ID_INVALID" | "VERSION_INVALID" | "ENTRY_NOT_REVIEW_ROUTE" | "TIMEOUT_INVALID";

/**
 * Screen a route by PARSING it, then require it to be exactly the one published route. https only, exactly the
 * Seller Center host, exactly the review search route, and a normalized URL identical to the bound one — which is
 * what refuses userinfo in the authority, a port, a query, or a host that merely contains the name.
 */
export function screenNaverReviewUrl(raw: string): boolean {
  let u: URL;
  try {
    u = new URL(raw);
  } catch {
    return false;
  }
  return (
    u.protocol === "https:" &&
    u.hostname.toLowerCase() === NAVER_SELLER_CENTER_HOST &&
    u.hash === NAVER_REVIEW_SEARCH_ROUTE &&
    u.href === NAVER_REVIEW_LIST_URL
  );
}

export function validateNaverReviewWorkflow(w: NaverReviewWorkflow): readonly NaverReviewWorkflowError[] {
  const errors: NaverReviewWorkflowError[] = [];
  if (typeof w.id !== "string" || w.id.trim().length === 0) errors.push("ID_INVALID");
  if (!Number.isInteger(w.version) || w.version < 1) errors.push("VERSION_INVALID");
  if (!screenNaverReviewUrl(w.entryUrl)) errors.push("ENTRY_NOT_REVIEW_ROUTE");
  if (!Number.isInteger(w.settleTimeoutMs) || w.settleTimeoutMs < 1_000 || w.settleTimeoutMs > 90_000) {
    errors.push("TIMEOUT_INVALID");
  }
  return errors;
}
