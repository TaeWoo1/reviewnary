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
  // <b>The two numbers the screen says out loud.</b>
  //
  // The grid's own model is what this reader reads, and loaded === rowCount is what proves it read all of
  // it. These two are INDEPENDENT witnesses of the same fact, and they exist because «500» was doing two
  // jobs at once: the recipe's own ceiling, and an assumption about how many rows this screen was showing
  // per page. The list prints its total as 「리뷰목록 (총 N개)」 and its chosen page size as 「N개씩」. A total
  // the model disagrees with, or a total above the page size, means this screen is not showing the whole
  // period — whatever the model says it loaded.
  function labelledTotal() {
    var nodes = document.querySelectorAll('h1, h2, h3, h4, h5, strong, em, b, span, div, p, td, li');
    var seen = null;
    for (var i = 0; i < nodes.length && i < 6000; i++) {
      var t = String(nodes[i].textContent || '').replace(/\\s+/g, '');
      if (t.length === 0 || t.length > 24) { continue; }
      var m = /^(?:[^0-9]{0,8})총([0-9,]+)개\\)?$/.exec(t);
      if (!m) { continue; }
      var v = parseInt(String(m[1]).replace(/,/g, ''), 10);
      if (isNaN(v)) { continue; }
      if (seen !== null && seen !== v) { return null; }
      seen = v;
    }
    return seen;
  }
  function selectedPageSize() {
    var sels = document.querySelectorAll('select');
    for (var s = 0; s < sels.length; s++) {
      var sv = /^([0-9,]+)개씩$/.exec(String(sels[s].value || '').replace(/\\s+/g, ''));
      if (sv) { return parseInt(String(sv[1]).replace(/,/g, ''), 10); }
    }
    var nodes = document.querySelectorAll('*');
    var seen = null;
    for (var i = 0; i < nodes.length && i < 6000; i++) {
      if (nodes[i].children.length > 0) { continue; }
      var t = String(nodes[i].textContent || '').replace(/\\s+/g, '');
      var m = /^([0-9,]+)개씩$/.exec(t);
      if (!m) { continue; }
      var cls = ' ' + String(nodes[i].className || '') + ' ';
      // Only the chosen one counts: the widget prints every option, and the one it is showing carries the
      // word. A widget whose chosen value cannot be told from its options answers null, not a guess.
      if (cls.indexOf(' selected ') < 0 && cls.indexOf(' item ') < 0 && cls.indexOf(' active ') < 0) { continue; }
      var v = parseInt(String(m[1]).replace(/,/g, ''), 10);
      if (isNaN(v)) { continue; }
      if (seen !== null && seen !== v) { return null; }
      seen = v;
    }
    return seen;
  }
  var LABELLED = labelledTotal();
  var PAGE_SIZE = selectedPageSize();
  function fail(reason, extra) {
    var r = { reason: reason, modelType: null, rowCount: -1, loaded: -1,
      labelledTotal: LABELLED, selectedPageSize: PAGE_SIZE, rows: [] };
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
  // gridReadMode is evidence, not decoration: it says the rows came from the grid's own row model and not
  // from the ~15 recycled elements the DOM holds, so «no new rows appeared» was never the stopping rule here.
  return { reason: 'OK', modelType: modelType, rowCount: rowCount, loaded: rows.length, linkChecked: checked,
    gridReadMode: 'MODEL', labelledTotal: LABELLED, selectedPageSize: PAGE_SIZE, rows: rows };
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

  // <b>The calendar opener bound to each date control — structure, not position.</b>
  //
  // Measured live, 2026-10-08: each readonly field sits in a group holding exactly that one field and exactly
  // one anchor, and the anchor is what draws the calendar. So the rule is the smallest ancestor that still
  // holds one accepted date control AND at least one thing a person could press. Exactly one there is the
  // opener; more than one is reported as a count and fails closed upstream. The field is readonly, so this is
  // the only way to the period at all — and «the first anchor near the input» is precisely the guess this
  // lane does not make.
  function openerFor(input) {
    var node = input.parentElement;
    var hops = 0;
    while (node && hops < 8) {
      hops++;
      var inside = 0;
      for (var d = 0; d < dates.accepted.length; d++) {
        if (node.contains(dates.nodes[dates.accepted[d]])) { inside++; }
      }
      if (inside !== 1) { break; }
      var here = [];
      if (query !== null) {
        for (var p = 0; p < query.nodes.length && p < 4000; p++) {
          if (node.contains(query.nodes[p]) && usable(query.nodes[p])) { here.push(p); }
        }
      }
      if (here.length > 0) {
        return { index: here.length === 1 ? here[0] : -1, count: here.length };
      }
      node = node.parentElement;
    }
    return { index: -1, count: 0 };
  }
  var openers = [];
  if (dates !== null) {
    for (var o = 0; o < dates.accepted.length; o++) {
      var bound = openerFor(dates.nodes[dates.accepted[o]]);
      openers.push({ dateIndex: dates.accepted[o], openerIndex: bound.index, openerCandidates: bound.count });
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
    queryAccepted: query === null ? null : queryInForm,
    // Indices into the SAME pressable candidate set as queryAccepted, so the runtime acts on one set.
    openers: openers
  };
})()`;
}

/**
 * **The open calendar, read as structure — the single judge of what a date-picker control is.**
 *
 * <h2>Why this exists, and what it replaced</h2>
 *
 * The 2026-10-08 window read stopped at `RANGE_NOT_SETTABLE`, and it was right to: the period's two fields are
 * `input[type=text]` with `readOnly = true`, and no amount of typing reaches them. The lane had nowhere to go,
 * and the first probe that looked for the calendar reported it unusable — no date on any cell, no month
 * navigation. That probe was wrong, and the way it was wrong is the reason this file is shaped as it is: it
 * climbed from the readonly input and called the input's own 190x34 wrapper the picker, so the 42 cells it
 * counted were another view's template, and the month navigation it declared missing was two levels above the
 * box it had chosen. One bad root, three false conclusions.
 *
 * <h2>What the live surface actually carries (2026-10-08, READ-ONLY, logged-in)</h2>
 *
 * The calendar is created on open — `document` holds no day cell while it is closed — and the component
 * declares its own semantics in the attribute that drives the cells' classes:
 *
 * ```
 * {current: …current, active: …active, past: …past, future: …future,
 *  disabled: !…selectable, includeDay: …includeDay, startDay: …startDay, endDay: …endDay}
 * ```
 *
 * So `past` / `future` mark the days of the neighbouring months that fill a 6x7 grid, and `disabled` is the
 * component's own word for «not selectable». A September page decomposed exactly: 2 `past` (Aug 30–31), 30
 * September cells, 10 `future` (Oct 1–10, of which two `disabled`) — 42 in all. That is what makes a day cell
 * identifiable without reading its number alone, which is the thing this lane may not do: «3» appears twice on
 * a September page, and the second one is October.
 *
 * <h2>The one thing the measurement refused to supply</h2>
 *
 * There is **no accessible label anywhere in the header**. The four arrows are `<button>` elements whose only
 * child is an `<i>` carrying `aria-hidden`, with no text, no `title` and no `aria-label`. A rule written as
 * «the control labelled 이전 달» cannot be implemented on this surface, so this script identifies a month step
 * by two independent markers that must agree — an icon class naming a single step (`backward`/`forward`, never
 * the `last-`/`first-` year jumps) **and** a handler that moves within the current view — and the runtime then
 * proves the month moved by one before it does anything else. Identification narrows the field; the title
 * re-read is what actually holds. Either marker disappearing makes the count 0 or 2, and both fail closed.
 *
 * <p>Answers in <b>indices</b> into the two pure-CSS candidate sets the runtime's locators enumerate, plus
 * small integers. No page text crosses back: a day cell is reported as a number, never as its text.
 */
export function buildNaverReviewPickerScript(
  dateSelector: string,
  pressableSelector: string,
  dayCellSelector: string,
): string {
  return `(function () {
  function usable(el) {
    if (el.disabled === true || String(el.getAttribute('aria-disabled') || '') === 'true') { return false; }
    var st = window.getComputedStyle(el);
    if (st.display === 'none' || st.visibility === 'hidden' || st.pointerEvents === 'none') { return false; }
    var r = el.getBoundingClientRect();
    return r.width > 0 || r.height > 0;
  }
  function isDateControl(el) {
    var type = String(el.getAttribute('type') || '').toLowerCase();
    if (type === 'date') { return true; }
    var cls = typeof el.className === 'string' ? el.className.toLowerCase() : '';
    return cls.indexOf('date') >= 0 || cls.indexOf('calendar') >= 0 || cls.indexOf('picker') >= 0;
  }
  function list(selector) {
    try { return document.querySelectorAll(selector); } catch (e) { return null; }
  }
  var dateNodes = list(${JSON.stringify(dateSelector)});
  var pressNodes = list(${JSON.stringify(pressableSelector)});
  var cellNodes = list(${JSON.stringify(dayCellSelector)});
  if (dateNodes === null || pressNodes === null || cellNodes === null) { return { readable: false, sides: [] }; }

  // The accepted date controls, by the same predicate the controls census uses. Both scripts ask the page the
  // same question in the same words; neither asks the runtime.
  var accepted = [];
  for (var i = 0; i < dateNodes.length && i < 4000; i++) {
    if (isDateControl(dateNodes[i]) && usable(dateNodes[i])) { accepted.push(i); }
  }

  // The side's own territory: the LARGEST ancestor that still holds exactly one accepted date control. The
  // calendar is drawn inside it (measured: two levels above the input, as a sibling of the input's group), and
  // the other field's calendar never is — which is what keeps «start» and «end» apart structurally instead of
  // by position.
  function sideRootOf(input) {
    var node = input.parentElement;
    var best = null;
    var hops = 0;
    while (node && hops < 8) {
      hops++;
      var inside = 0;
      for (var d = 0; d < accepted.length; d++) { if (node.contains(dateNodes[accepted[d]])) { inside++; } }
      if (inside !== 1) { break; }
      best = node;
      node = node.parentElement;
    }
    return best;
  }
  function classMarks(el) {
    var marks = ' ' + String(el.className || '') + ' ';
    var kids = el.querySelectorAll('*');
    for (var k = 0; k < kids.length && k < 20; k++) { marks += ' ' + String(kids[k].className || '') + ' '; }
    return marks.toLowerCase();
  }
  function handlerOf(el) {
    return String(el.getAttribute('data-ng-click') || el.getAttribute('ng-click') || '').toLowerCase();
  }
  // A single month step: an icon naming one step, and a handler that moves inside the view already shown.
  // The year jumps carry 'last-'/'first-' and a handler that names the year; the title opens another view.
  function monthSteps(root, direction) {
    var out = [];
    for (var p = 0; p < pressNodes.length; p++) {
      var el = pressNodes[p];
      if (!root.contains(el) || !usable(el)) { continue; }
      var marks = classMarks(el);
      var ng = handlerOf(el);
      if (ng.indexOf('year') >= 0 || marks.indexOf('last-backward') >= 0 || marks.indexOf('first-forward') >= 0) {
        continue;
      }
      if (ng.replace(/\\s+/g, '').indexOf('currentview') < 0) { continue; }
      var back = marks.indexOf('backward') >= 0 || marks.indexOf('prev') >= 0;
      var fwd = marks.indexOf('forward') >= 0 || marks.indexOf('next') >= 0;
      if (direction < 0 && back && !fwd) { out.push(p); }
      if (direction > 0 && fwd && !back) { out.push(p); }
    }
    return out;
  }
  var WEEKDAYS = ['일', '월', '화', '수', '목', '금', '토'];
  function hasClassWord(el, word) {
    return (' ' + String(el.className || '') + ' ').toLowerCase().indexOf(' ' + word + ' ') >= 0;
  }
  function sideState(dateIndex) {
    var state = {
      dateIndex: dateIndex, open: false, year: null, month: null,
      weekdays: 0, cells: 0, prev: [], next: [], days: [], titles: 0
    };
    var root = sideRootOf(dateNodes[dateIndex]);
    if (root === null) { return state; }
    // The picker root is found from the CELLS, never by climbing from the input — that climb is what made the
    // first probe call a 190x34 input wrapper a calendar.
    var mine = [];
    for (var c = 0; c < cellNodes.length && c < 4000; c++) {
      if (root.contains(cellNodes[c]) && hasClassWord(cellNodes[c], 'day')) { mine.push(c); }
    }
    state.cells = mine.length;
    if (mine.length < 28) { return state; }
    var picker = cellNodes[mine[0]];
    for (var up = 0; up < 10 && picker; up++) {
      var holds = 0;
      for (var m = 0; m < mine.length; m++) { if (picker.contains(cellNodes[mine[m]])) { holds++; } }
      if (holds === mine.length) { break; }
      picker = picker.parentElement;
    }
    if (!picker) { return state; }
    // Climb to the whole component: the header with the title and the arrows sits beside the cell table.
    var head = picker;
    for (var h = 0; h < 4 && head.parentElement; h++) {
      head = head.parentElement;
      if (head.querySelectorAll('th').length >= 7 && monthSteps(head, -1).length > 0) { break; }
    }
    var ths = head.querySelectorAll('th');
    for (var t = 0; t < ths.length; t++) {
      var w = String(ths[t].textContent || '').trim();
      for (var x = 0; x < WEEKDAYS.length; x++) { if (w === WEEKDAYS[x]) { state.weekdays++; } }
    }
    // The month on show, read as text. Three independent facts have to agree that this is a DAY view: a
    // YYYY.MM title, seven weekday headings, and a full grid of day cells. A month or year view has none of
    // the three, and that is the view the first probe mistook for this one.
    var titleNodes = head.querySelectorAll('*');
    for (var q = 0; q < titleNodes.length && q < 400; q++) {
      if (titleNodes[q].children.length > 0) { continue; }
      var raw = String(titleNodes[q].textContent || '').replace(/\\s+/g, '');
      var mm = /^(\\d{4})[.\\-/](\\d{1,2})\\.?$/.exec(raw);
      if (!mm) { continue; }
      state.titles++;
      state.year = Number(mm[1]);
      state.month = Number(mm[2]);
    }
    if (state.titles !== 1) { state.year = null; state.month = null; }
    state.prev = monthSteps(head, -1);
    state.next = monthSteps(head, 1);
    // A selectable day of the month on show. 'past'/'future' are the component's own words for the
    // neighbouring months' filler days, and they are the whole reason a cell is never chosen by its number.
    for (var y = 0; y < mine.length; y++) {
      var cell = cellNodes[mine[y]];
      if (hasClassWord(cell, 'past') || hasClassWord(cell, 'future') || hasClassWord(cell, 'disabled')) { continue; }
      if (!usable(cell)) { continue; }
      var num = String(cell.textContent || '').replace(/\\s+/g, '');
      if (!/^\\d{1,2}$/.test(num)) { continue; }
      var day = Number(num);
      if (day < 1 || day > 31) { continue; }
      state.days.push([mine[y], day]);
    }
    state.open = state.weekdays === 7 && state.year !== null && state.days.length > 0;
    return state;
  }
  var sides = [];
  for (var a = 0; a < accepted.length; a++) { sides.push(sideState(accepted[a])); }
  return { readable: true, dateAccepted: accepted, sides: sides };
})()`;
}
