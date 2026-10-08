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
 * **The candidate sets a historical window read may look through — pure CSS, and nothing else.**
 *
 * <h2>What these are, and what they are deliberately not</h2>
 *
 * They are <b>candidate sets</b>, not addresses, and they decide nothing. Whether a candidate is a control a
 * person could actually use — visible, enabled, and carrying the meaning this read needs — is judged in one
 * place only: {@code buildNaverReviewControlsScript}, which walks these same selectors inside the page and
 * answers in indices. The runtime acts on exactly the indices that script accepted.
 *
 * <p><b>Why they are plain CSS.</b> The first live catch-up (2026-10-08) stopped in the CONTROLS stage with a
 * `SyntaxError`, because these carried `:visible:not([disabled])` and Aside hands a selector straight to
 * `document.querySelectorAll` — which has never heard of Playwright's `:visible`. Removing the pseudo-class is
 * the trivial half of that fix. The half worth writing down: the same judgement was being made here AND in
 * the page predicate, and a fact judged in two places can be judged two ways. The selector is now dumb on
 * purpose. `:not([disabled])` is gone for that reason and not for the syntax — enabled is the census's call.
 */
export const NAVER_REVIEW_DATE_INPUT_SELECTOR =
  'input[type="date"], input[class*="date" i], input[class*="calendar" i], input[class*="picker" i]';

/**
 * Everything a person could press. Broad on purpose: the narrowing is 조회/검색 as a whole label, and that is
 * a judgement about text, which belongs in the page and not in a selector.
 */
export const NAVER_REVIEW_QUERY_CONTROL_SELECTOR =
  'button, a, input[type="button"], input[type="submit"]';

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
