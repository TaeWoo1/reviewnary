/**
 * **The two page scripts an unattended NAVER Seller Center 리뷰 read runs — authored here, forwarded verbatim.**
 *
 * Nothing under `src/aside/` writes page code; the runtime there forwards these strings and nothing else
 * (`aside-guard.test.ts`). They are small because the discovery that justified them (2026-09-17, READ-ONLY, on the
 * real logged-in surface) found the list already holds what a scheduled read needs, with no click:
 *
 *  - **the list is an ag-Grid whose own row model holds every row of the screen's period.** Measured: model type
 *    `infinite`, 52 rows, 52 loaded, one page of 500 — while only ~15 rows are in the DOM at a time, because the
 *    grid recycles them. So the reader asks the MODEL, never scrolls, and refuses when any node is unloaded.
 *  - **each row's `id` is the review's own id.** On 15/15 rendered rows it equalled the id in the row's detail link
 *    (`openReviewDetailModal(<id>, …)`) — the id the guided reply lane matched against the exported 리뷰글번호 live on
 *    2026-09-03 and 09-05. The reader re-checks that equality on every run and refuses on any disagreement: a page
 *    whose meaning moved must fail closed, not quietly store the wrong key. The DOM `row-id` is the grid's index and
 *    is never read.
 *  - **the row model also carries the buyer's masked id, member number and order number.** None of them is read out.
 *    The row object this script builds names its fields explicitly; there is no spread, no copy, no `Object.keys`.
 *
 * Every refusal is a closed reason word. Page text never crosses back except the eight named review fields.
 */

/** Reasons the reader may give. `OK` is the only one that carries rows. */
export const NAVER_REVIEW_READ_REASONS = [
  "OK",
  "ROUTE_MISMATCH",
  "GRID_NOT_FOUND",
  "MODEL_UNREADABLE",
  "MODEL_SHAPE_CHANGED",
  "ROWS_NOT_LOADED",
  "ID_LINK_MISMATCH",
  "TOO_MANY_ROWS",
] as const;
export type NaverReviewReadReason = (typeof NAVER_REVIEW_READ_REASONS)[number];

/** The largest reading this recipe accepts. The backend enforces the same bound. */
export const NAVER_REVIEW_MAX_ROWS = 500;

/**
 * The property of one `reviewAttaches` entry that holds the attachment's own address.
 *
 * **Named by the READ-ONLY census of the live row model (M2, 2026-09-18, approved; 48 rows, 24 attachments).** Every
 * entry carried `attachUrl` and `attachPath`, both https on `phinf.pstatic.net` with a jpg/jpeg path; `attachUrl` is
 * the one this reader projects. The same census found `reviewAttachmentType = "I"` on all 24 — so `I` reads as an
 * image and any other value as UNKNOWN (a video code was never observed, so none is assumed). No row in that period
 * had `hasComment = true`, and the row model carries no reply-text field at all; seller reply text is not read here.
 */
export const NAVER_REVIEW_ATTACH_URL_KEY: string | null = "attachUrl";

/** The census-observed value of `reviewAttachmentType` that means a photo. */
export const NAVER_REVIEW_ATTACH_IMAGE_TYPE = "I";

/**
 * Signed in, on the Seller Center host, with no password field on the page. Host first: a sign-in redirect lands on
 * `nid.naver.com` / `accounts.commerce.naver.com`, and reading that page's words as «signed in» is the defect the
 * reply lane's `IN_PAGE_LOGIN_SIGNAL` was fixed for.
 */
export function buildNaverReviewAuthScript(): string {
  return `(function () {
  var host = String(location.host || '').toLowerCase();
  if (host !== 'sell.smartstore.naver.com') { return { signedIn: false, onSellerCenter: false }; }
  var pw = document.querySelectorAll('input[type="password"]').length;
  var out = 0;
  var all = document.querySelectorAll('*');
  for (var i = 0; i < all.length && i < 20000; i++) {
    var own = '';
    var cn = all[i].childNodes;
    for (var c = 0; c < cn.length; c++) { if (cn[c].nodeType === 3) { own += cn[c].nodeValue; } }
    own = own.replace(/\\s+/g, ' ').trim();
    if (own.length > 0 && own.length <= 20 && own.indexOf('로그아웃') >= 0) { out++; }
  }
  return { signedIn: pw === 0 && out > 0, onSellerCenter: true };
})()`;
}

/**
 * Read every row of the screen's current period from the grid's row model.
 *
 * Returns `{ reason, modelType, rowCount, loaded, rows }`. `rows` is empty unless `reason === "OK"`.
 */
export function buildNaverReviewListReadScript(): string {
  return `(function () {
  var MAX = ${NAVER_REVIEW_MAX_ROWS};
  function fail(reason, extra) {
    var r = { reason: reason, modelType: null, rowCount: -1, loaded: -1, rows: [] };
    if (extra) { for (var k in extra) { r[k] = extra[k]; } }
    return r;
  }
  if (String(location.host || '').toLowerCase() !== 'sell.smartstore.naver.com'
      || String(location.hash || '').indexOf('#/review/search') !== 0) {
    return fail('ROUTE_MISMATCH');
  }
  var rendered = document.querySelectorAll('.ag-center-cols-container .ag-row');
  if (rendered.length === 0) { return fail('GRID_NOT_FOUND'); }
  function nodeOf(el) {
    var names = Object.getOwnPropertyNames(el);
    for (var i = 0; i < names.length; i++) {
      if (names[i].indexOf('__AG_') !== 0) { continue; }
      var store = el[names[i]];
      if (store && store.renderedRow && store.renderedRow.rowNode) { return store.renderedRow.rowNode; }
    }
    return null;
  }
  var first = nodeOf(rendered[0]);
  var api = first && first.gridApi;
  if (!api || typeof api.forEachNode !== 'function' || typeof api.getModel !== 'function') {
    return fail('MODEL_UNREADABLE');
  }
  var model = api.getModel();
  var modelType = model && typeof model.getType === 'function' ? model.getType() : null;
  var rowCount = model && typeof model.getRowCount === 'function' ? model.getRowCount() : -1;
  if (typeof rowCount !== 'number' || rowCount < 0) { return fail('MODEL_UNREADABLE', { modelType: modelType }); }
  if (rowCount > MAX) { return fail('TOO_MANY_ROWS', { modelType: modelType, rowCount: rowCount }); }
  if (typeof api.paginationGetTotalPages === 'function' && api.paginationGetTotalPages() > 1) {
    return fail('TOO_MANY_ROWS', { modelType: modelType, rowCount: rowCount });
  }
  var ATTACH_URL_KEY = ${JSON.stringify(NAVER_REVIEW_ATTACH_URL_KEY)};
  function attachmentsOf(list) {
    if (!ATTACH_URL_KEY || !Array.isArray(list)) { return null; }
    var out = [];
    for (var a = 0; a < list.length; a++) {
      var entry = list[a];
      var url = entry && typeof entry[ATTACH_URL_KEY] === 'string' ? entry[ATTACH_URL_KEY] : null;
      if (!url || url.indexOf('https://') !== 0) { return null; }
      var kind = entry.reviewAttachmentType === ${JSON.stringify(NAVER_REVIEW_ATTACH_IMAGE_TYPE)} ? 'IMAGE' : 'UNKNOWN';
      out.push({ url: url, kind: kind });
    }
    return out;
  }
  var rows = [];
  var missing = 0;
  var badShape = 0;
  api.forEachNode(function (n) {
    var d = n && n.data;
    if (!d) { missing++; return; }
    var id = d.id;
    var score = d.reviewScore;
    if ((typeof id !== 'number' && typeof id !== 'string') || typeof score !== 'number'
        || typeof d.createDate !== 'string' || typeof d.hasComment !== 'boolean'
        || (typeof d.productNo !== 'number' && typeof d.productNo !== 'string')) {
      badShape++;
      return;
    }
    rows.push({
      reviewId: String(id),
      createdAt: d.createDate,
      rating: score,
      body: typeof d.reviewContent === 'string' ? d.reviewContent : '',
      productNo: String(d.productNo),
      productName: typeof d.productName === 'string' ? d.productName : null,
      answered: d.hasComment,
      attachCount: Array.isArray(d.reviewAttaches) ? d.reviewAttaches.length : 0,
      attachments: attachmentsOf(d.reviewAttaches)
    });
  });
  if (missing > 0 || rows.length + badShape !== rowCount) {
    return fail('ROWS_NOT_LOADED', { modelType: modelType, rowCount: rowCount, loaded: rows.length });
  }
  if (badShape > 0) { return fail('MODEL_SHAPE_CHANGED', { modelType: modelType, rowCount: rowCount }); }
  // The meaning check: the model's id must be the id the row's own detail link opens.
  var checked = 0;
  for (var r = 0; r < rendered.length; r++) {
    var node = nodeOf(rendered[r]);
    if (!node || !node.data) { continue; }
    var links = rendered[r].querySelectorAll('[ng-click]');
    for (var l = 0; l < links.length; l++) {
      var m = /openReviewDetailModal\\((\\d+)/.exec(String(links[l].getAttribute('ng-click') || ''));
      if (!m) { continue; }
      if (m[1] !== String(node.data.id)) { return fail('ID_LINK_MISMATCH', { modelType: modelType, rowCount: rowCount }); }
      checked++;
    }
  }
  var pinned = document.querySelectorAll('.ag-pinned-left-cols-container .ag-row');
  for (var p = 0; p < pinned.length; p++) {
    var pn = nodeOf(pinned[p]);
    if (!pn || !pn.data) { continue; }
    var plinks = pinned[p].querySelectorAll('[ng-click]');
    for (var q = 0; q < plinks.length; q++) {
      var pm = /openReviewDetailModal\\((\\d+)/.exec(String(plinks[q].getAttribute('ng-click') || ''));
      if (!pm) { continue; }
      if (pm[1] !== String(pn.data.id)) { return fail('ID_LINK_MISMATCH', { modelType: modelType, rowCount: rowCount }); }
      checked++;
    }
  }
  if (rows.length > 0 && checked === 0) { return fail('ID_LINK_MISMATCH', { modelType: modelType, rowCount: rowCount }); }
  return { reason: 'OK', modelType: modelType, rowCount: rowCount, loaded: rows.length, linkChecked: checked, rows: rows };
})()`;
}

/**
 * **Is this the review grid, is it drawn, and which controls may be acted on? — one answer, from the page.**
 *
 * Authored here for the same reason as the reader: nothing under `src/aside/` writes page code. The historical
 * window read runs this BEFORE it touches a control, because typing a date into a page that is not this page
 * is the one mistake no later verification can undo.
 *
 * <h2>Why the predicate lives here and nowhere else</h2>
 *
 * It used to live in two places. The selector carried `:visible:not([disabled])` and this script carried the
 * same judgement again — and on 2026-10-08 the first live catch-up stopped in the CONTROLS stage with a
 * `SyntaxError`: Aside hands a selector to `document.querySelectorAll`, which has never heard of Playwright's
 * `:visible`. Two things were wrong at once. The syntax, which is trivial. And the duplication, which is not:
 * a fact judged in two places is a fact that can be judged two ways, and the copy that happened to run first
 * was the one with no tests behind it.
 *
 * <p>So the selector is now a <b>pure-CSS candidate set</b> and this script is the <b>only</b> judge of
 * whether a candidate is a control a person could use. It answers in <b>indices into that same candidate
 * set</b>, in document order — which is the order a locator enumerates too, so the runtime can act on exactly
 * the elements this script accepted without either side re-deciding anything.
 *
 * <p>Returns `{ route, grid, dateCandidates, dateAccepted, dateFormFound, queryCandidates, queryLabelled,
 * queryAccepted }`. Booleans, counts and small integer indices. No page text, no selector, no attribute value
 * crosses back.
 *
 * <p>It establishes the SURFACE, not the STORE. Which seller's store this is cannot be decided in the page —
 * the backend decides it at delivery, against listings collected by the official API.
 */
export function buildNaverReviewControlsScript(dateSelector: string, querySelector: string): string {
  return `(function () {
  var route = String(location.host || '').toLowerCase() === 'sell.smartstore.naver.com'
    && String(location.hash || '').indexOf('#/review/search') === 0;
  var grid = document.querySelectorAll('.ag-center-cols-container .ag-row').length;
  function usable(el) {
    if (el.disabled === true || String(el.getAttribute('aria-disabled') || '') === 'true') { return false; }
    var st = window.getComputedStyle(el);
    if (st.display === 'none' || st.visibility === 'hidden' || st.pointerEvents === 'none') { return false; }
    var r = el.getBoundingClientRect();
    return r.width > 0 || r.height > 0;
  }
  // The date predicate, unchanged in meaning from the live-proven census: type=date, or a class naming a
  // date/calendar/picker widget. Readonly is NOT an exclusion — a calendar-backed field is almost always
  // readonly, and treating that as unusable reported zero date inputs on a surface that had two.
  function isDateControl(el) {
    var type = String(el.getAttribute('type') || '').toLowerCase();
    if (type === 'date') { return true; }
    var cls = typeof el.className === 'string' ? el.className.toLowerCase() : '';
    return cls.indexOf('date') >= 0 || cls.indexOf('calendar') >= 0 || cls.indexOf('picker') >= 0;
  }
  // The query predicate: the word this list prints on the control that shows a period. Compared as a whole
  // label, so 「조회수」 or 「검색어 저장」 is not a 조회 button. The label is read and discarded here.
  var WORDS = ['조회', '검색', '조회하기', '검색하기'];
  function isQueryControl(el) {
    var label = String((el.value !== undefined && el.value !== null && String(el.tagName).toLowerCase() === 'input')
      ? el.value : (el.textContent || '')).replace(/\s+/g, '');
    if (label.length === 0 || label.length > 8) { return false; }
    for (var w = 0; w < WORDS.length; w++) { if (label === WORDS[w]) { return true; } }
    return false;
  }
  function formOf(el) {
    var n = el;
    while (n) { if (String(n.tagName).toLowerCase() === 'form') { return n; } n = n.parentElement; }
    return null;
  }
  function census(selector, accept) {
    var found;
    try { found = document.querySelectorAll(selector); } catch (e) { return null; }
    var accepted = [];
    for (var i = 0; i < found.length && i < 4000; i++) {
      if (accept(found[i]) && usable(found[i])) { accepted.push(i); }
    }
    return { candidates: found.length, accepted: accepted, nodes: found };
  }
  var dates = census(${JSON.stringify(dateSelector)}, isDateControl);
  var query = census(${JSON.stringify(querySelector)}, isQueryControl);

  // <b>The 조회 that belongs to THIS period — the one in the period's own form.</b>
  //
  // Measured on the live surface, 2026-10-08: two controls carry the whole word. One is a 17x24 anchor at the
  // far left of the page (the global search) in a DIFFERENT form, whose nearest common ancestor with the date
  // inputs is five levels from <body> — «also on this page», and nothing more. The other is a 120x40 submit
  // button in the SAME form as the two date inputs, three levels under a container holding those two dates
  // and exactly one 조회. The form is what tells them apart, and it is what a page author would say too: the
  // filter's submit button is in the filter's form.
  //
  // queryLabelled is kept beside the answer so a stop can say which test excluded them — the word or the
  // form. «Two labelled, none in the form» and «none labelled at all» are different pages.
  var dateForm = null;
  if (dates !== null && dates.accepted.length === 2) {
    var f0 = formOf(dates.nodes[dates.accepted[0]]);
    var f1 = formOf(dates.nodes[dates.accepted[1]]);
    if (f0 !== null && f0 === f1) { dateForm = f0; }
  }
  var queryInForm = [];
  if (query !== null && dateForm !== null) {
    for (var q = 0; q < query.accepted.length; q++) {
      if (formOf(query.nodes[query.accepted[q]]) === dateForm) { queryInForm.push(query.accepted[q]); }
    }
  }
  return {
    route: route,
    grid: grid,
    dateCandidates: dates === null ? -1 : dates.candidates,
    dateAccepted: dates === null ? null : dates.accepted,
    dateFormFound: dateForm !== null,
    queryCandidates: query === null ? -1 : query.candidates,
    queryLabelled: query === null ? -1 : query.accepted.length,
    // A 조회 with no period form to belong to is not this period's 조회. Empty fails closed, which is the
    // honest answer for a page whose filter is not a form at all.
    queryAccepted: query === null ? null : queryInForm
  };
})()`;
}
