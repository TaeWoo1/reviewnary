/**
 * **A bounded HISTORICAL window read of the NAVER Seller Center 리뷰 list — the program that runs inside Aside.**
 *
 * <h2>Why this file exists, and why it is not the other one</h2>
 *
 * `naver-review-runtime.ts` opens one route and reads whatever period the screen happens to be showing. That
 * is all it can do, and it is the reason the 2026-10-08 live read covered 10-02 … 10-08 while the twenty-nine
 * days behind it stayed unread: there was no way to ask the page for a period. A seller with a month-wide hole
 * had no lane that could reach into it.
 *
 * This is that lane, and it is deliberately a second file rather than a flag on the first. The read runtime's
 * property — **it touches nothing** — is worth keeping exactly as it is, and worth being able to check by
 * reading one short file. What this file has instead is a different, narrower property:
 *
 * <h2>READ navigation, and the whole of it</h2>
 *
 * Three interactions exist here and no fourth is reachable:
 *
 *  1. put a date in the period's **from** field,
 *  2. put a date in its **to** field,
 *  3. press the list's own 조회/검색.
 *
 * That is navigation of a read — the same thing a seller does with their own hands to look at last month. It
 * is not a mutation: no reply is written, no review's state is changed, nothing is submitted, nothing is
 * deleted, no row-level control is touched, and no button the plan did not name is pressed. `aside-guard`
 * holds this file to `fill` and `click` on count-guarded handles and to a bounded number of sites, so a fourth
 * interaction cannot be added without that test failing.
 *
 * <h2>One judge for «is this a control»</h2>
 *
 * The plan carries two <b>pure-CSS candidate sets</b> and the page script
 * ({@code buildNaverReviewControlsScript}) is the only thing that decides which candidates are controls a
 * person could use — visible, enabled, and carrying the right meaning. It answers in indices into those same
 * candidate sets, in document order, and this file acts on exactly those indices.
 *
 * <p>That split is the lesson of 2026-10-08, not a refactor. The selectors then carried
 * `:visible:not([disabled])` while the page script made the same judgement again; Aside hands a selector to
 * `document.querySelectorAll`, the pseudo-class threw a `SyntaxError`, and the whole lane stopped at its third
 * gate. The syntax was the shallow half. The deep half is that one fact had two judges, and the one that ran
 * first had no test behind it.
 *
 * <h2>Fail closed on identification — four gates before a key is pressed</h2>
 *
 * Typing a date into the wrong page is the one mistake no later check can undo: the verification would then be
 * reading some other screen's inputs and agreeing with itself. So, in order:
 *
 *  1. **the surface** — the published host and hash, and a drawn grid. Anything else is `SURFACE_UNEXPECTED`.
 *  2. **the sign-in wall** — asked before anything, as everywhere else. A signed-out browser is a stop, never
 *     a thing to type into.
 *  3. **exactly two date controls and exactly one 조회** — as the page script accepted them. Zero, one or
 *     three dates is `RANGE_CONTROLS_NOT_FOUND` / `RANGE_CONTROLS_AMBIGUOUS` with the count it looked
 *     through; the query control has its own two codes. A candidate set this runtime could not even look
 *     through is `DATE_CONTROL_CANDIDATES_UNREADABLE` — named apart from `RUNTIME_FAULT` because «the
 *     selector was not a selector» and «the browser went away» need different fixes, and on 2026-10-08 they
 *     were the same word. Never «take the first two».
 *  4. **which field is the from** — read from the values the page ARRIVED with. The list opens on its own
 *     default period, so the two fields already say which of them holds the earlier date. If their current
 *     values do not establish that order, this stops: a range typed into reversed fields reads as a valid
 *     range to every check that only looks at the two dates, and shows the seller's store an empty week.
 *
 * <h2>And then it proves the screen moved</h2>
 *
 * After the search, the census runs again and the period on screen must be **the period that was asked for**,
 * to the day. Not «we filled the fields» — the fields are an intention; the census is the screen. A different
 * period, or an unreadable one, means nothing is read and nothing is delivered.
 *
 * <p>No closure. Serialized with {@code Function.prototype.toString}; nothing here may reference an import or
 * a module-level identifier.
 */

export interface NaverWindowLocatorLike {
  count(): Promise<number>;
  nth(index: number): NaverWindowLocatorLike;
  fill(value: string): Promise<void>;
  click(): Promise<void>;
  inputValue(): Promise<string>;
}

export interface NaverWindowTabLike {
  evaluate(script: string): Promise<unknown>;
  locator(selector: string): NaverWindowLocatorLike;
  waitForLoadState?(state?: string, opts?: { timeout?: number }): Promise<void>;
}

export interface NaverWindowRuntimeEnv {
  openTab(url: string): Promise<NaverWindowTabLike>;
  closeTab(tab: NaverWindowTabLike): Promise<void>;
  wait(ms: number): Promise<void>;
}

export interface NaverReviewWindowRuntimePlan {
  entryUrl: string;
  /** Forwarded page scripts, authored in `src/naver/`. This file composes none of them. */
  controlsScript: string;
  authScript: string;
  readerScript: string;
  rangeScript: string;
  /** The pure-CSS candidate set the controls script walked for date controls. Decides nothing on its own. */
  dateInputSelector: string;
  /** The pure-CSS candidate set it walked for the 조회/검색 control. */
  queryControlSelector: string;
  /** The period asked for: what to type, and the two day offsets the screen must then show. */
  requestedStartValue: string;
  requestedEndValue: string;
  requestedStartDaysBefore: number;
  requestedEndDaysBefore: number;
  settleTimeoutMs: number;
  pollMs: number;
  /** How long the list may take to redraw after the search before the census is asked. */
  searchSettleMs: number;
}

export type NaverWindowRuntimeResult =
  | { ok: true; reading: unknown; range: unknown; elapsedMs: number }
  | {
      ok: false;
      code:
        | "AUTH_REQUIRED"
        | "SURFACE_UNEXPECTED"
        | "DATE_CONTROL_CANDIDATES_UNREADABLE"
        | "RANGE_CONTROLS_NOT_FOUND"
        | "RANGE_CONTROLS_AMBIGUOUS"
        | "QUERY_CONTROL_NOT_FOUND"
        | "QUERY_CONTROL_AMBIGUOUS"
        | "RANGE_ORDER_UNKNOWN"
        | "RANGE_NOT_SETTABLE"
        | "RANGE_MISMATCH"
        | "READ_UNSETTLED"
        | "RUNTIME_FAULT";
      stage: "PREPARE" | "SURFACE" | "AUTH" | "CONTROLS" | "NAVIGATE" | "VERIFY" | "READ";
      /** How many candidates were found, when the stop was about a count. */
      candidates: number | null;
      elapsedMs: number;
    };

export async function asideNaverReviewWindowRuntime(
  plan: NaverReviewWindowRuntimePlan,
  env: NaverWindowRuntimeEnv,
): Promise<NaverWindowRuntimeResult> {
  const startedAt = Date.now();
  const elapsed = () => Date.now() - startedAt;
  const fail = (
    code: Extract<NaverWindowRuntimeResult, { ok: false }>["code"],
    stage: Extract<NaverWindowRuntimeResult, { ok: false }>["stage"],
    candidates?: number,
  ): NaverWindowRuntimeResult => ({
    ok: false,
    code,
    stage,
    candidates: typeof candidates === "number" ? candidates : null,
    elapsedMs: elapsed(),
  });

  let tab: NaverWindowTabLike;
  try {
    tab = await env.openTab(plan.entryUrl);
  } catch (e) {
    return fail("RUNTIME_FAULT", "PREPARE");
  }
  try {
    if (typeof tab.waitForLoadState === "function") {
      try {
        await tab.waitForLoadState("networkidle", { timeout: plan.settleTimeoutMs });
      } catch (e) {
        /* an unsettled page is decided by the gates below, not by a timer */
      }
    }

    // 1. THE SURFACE. The published route, and a grid that is drawn.
    let surface: unknown;
    try {
      surface = await tab.evaluate(plan.controlsScript);
    } catch (e) {
      return fail("RUNTIME_FAULT", "SURFACE");
    }
    const s = (surface ?? {}) as {
      route?: unknown; grid?: unknown;
      dateCandidates?: unknown; dateAccepted?: unknown;
      queryCandidates?: unknown; queryAccepted?: unknown;
    };
    if (s.route !== true) {
      return fail("SURFACE_UNEXPECTED", "SURFACE");
    }

    // 2. THE SIGN-IN WALL. Before the controls, because a signed-out page can draw anything.
    let auth: unknown;
    try {
      auth = await tab.evaluate(plan.authScript);
    } catch (e) {
      return fail("RUNTIME_FAULT", "AUTH");
    }
    if (!auth || typeof auth !== "object" || (auth as { signedIn?: unknown }).signedIn !== true) {
      return fail("AUTH_REQUIRED", "AUTH");
    }
    if (typeof s.grid !== "number" || s.grid <= 0) {
      // Signed in, the right route, and no rows drawn: the screen this would act on is not the one it expects.
      return fail("SURFACE_UNEXPECTED", "SURFACE", typeof s.grid === "number" ? s.grid : undefined);
    }

    // 3. EXACTLY TWO DATE CONTROLS AND EXACTLY ONE 조회 — as the page's own predicate accepted them.
    //
    // A candidate set the script could not even look through (`null`) is its own stop: a selector that is not
    // a selector and a browser that went away are different problems, and until 2026-10-08 they shared a word.
    const dateAccepted = s.dateAccepted;
    const queryAccepted = s.queryAccepted;
    if (!Array.isArray(dateAccepted) || !Array.isArray(queryAccepted)) {
      return fail("DATE_CONTROL_CANDIDATES_UNREADABLE", "CONTROLS",
        typeof s.dateCandidates === "number" ? s.dateCandidates : undefined);
    }
    if (dateAccepted.length < 2) {
      return fail("RANGE_CONTROLS_NOT_FOUND", "CONTROLS", dateAccepted.length);
    }
    if (dateAccepted.length > 2) {
      return fail("RANGE_CONTROLS_AMBIGUOUS", "CONTROLS", dateAccepted.length);
    }
    if (queryAccepted.length === 0) {
      return fail("QUERY_CONTROL_NOT_FOUND", "CONTROLS", 0);
    }
    if (queryAccepted.length > 1) {
      return fail("QUERY_CONTROL_AMBIGUOUS", "CONTROLS", queryAccepted.length);
    }

    // The handles are the accepted indices, in the candidate set's own document order — which is the order a
    // locator enumerates. Neither side re-decides what a control is.
    const dates = tab.locator(plan.dateInputSelector);
    const search = tab.locator(plan.queryControlSelector).nth(Number(queryAccepted[0]));

    // 4. WHICH ONE IS THE FROM. Read off the default period the page arrived with, never assumed from DOM order.
    const first = dates.nth(Number(dateAccepted[0]));
    const second = dates.nth(Number(dateAccepted[1]));
    let firstWas = "";
    let secondWas = "";
    try {
      firstWas = await first.inputValue();
      secondWas = await second.inputValue();
    } catch (e) {
      return fail("RUNTIME_FAULT", "CONTROLS");
    }
    const firstDay = dayNumber(firstWas);
    const secondDay = dayNumber(secondWas);
    if (firstDay === null || secondDay === null || firstDay === secondDay) {
      return fail("RANGE_ORDER_UNKNOWN", "CONTROLS");
    }
    const from = firstDay < secondDay ? first : second;
    const to = firstDay < secondDay ? second : first;

    // NAVIGATE — the three interactions, and nothing else.
    try {
      await from.fill(plan.requestedStartValue);
      await to.fill(plan.requestedEndValue);
    } catch (e) {
      // A calendar-backed field that refuses to be typed into is a real limit of this surface. It is not a
      // reason to start clicking a date picker: that is «any button», which this lane does not have.
      return fail("RANGE_NOT_SETTABLE", "NAVIGATE");
    }
    let fromNow = "";
    let toNow = "";
    try {
      fromNow = await from.inputValue();
      toNow = await to.inputValue();
    } catch (e) {
      return fail("RUNTIME_FAULT", "NAVIGATE");
    }
    if (dayNumber(fromNow) === null || dayNumber(toNow) === null) {
      return fail("RANGE_NOT_SETTABLE", "NAVIGATE");
    }
    try {
      await search.click();
    } catch (e) {
      return fail("RUNTIME_FAULT", "NAVIGATE");
    }
    await env.wait(plan.searchSettleMs);

    // VERIFY — the screen, not the intention. The census is the live-proven reader of this list's own period.
    let range: unknown = null;
    try {
      range = await tab.evaluate(plan.rangeScript);
    } catch (e) {
      return fail("RUNTIME_FAULT", "VERIFY");
    }
    const r = (range ?? {}) as { valuesParsed?: unknown; startDaysBefore?: unknown; endDaysBefore?: unknown };
    if (r.valuesParsed !== 2) {
      return fail("RANGE_MISMATCH", "VERIFY", typeof r.valuesParsed === "number" ? r.valuesParsed : undefined);
    }
    if (r.startDaysBefore !== plan.requestedStartDaysBefore || r.endDaysBefore !== plan.requestedEndDaysBefore) {
      return fail("RANGE_MISMATCH", "VERIFY");
    }

    // READ — the same settle rule as the plain read: two consecutive readings that agree.
    let settled: unknown = null;
    let previous = "";
    const deadline = startedAt + plan.settleTimeoutMs;
    for (;;) {
      let reading: unknown;
      try {
        reading = await tab.evaluate(plan.readerScript);
      } catch (e) {
        return fail("RUNTIME_FAULT", "READ");
      }
      const text = JSON.stringify(reading);
      if (previous !== "" && previous === text) {
        settled = reading;
        break;
      }
      previous = text;
      if (Date.now() + plan.pollMs > deadline) {
        return fail("READ_UNSETTLED", "READ");
      }
      await env.wait(plan.pollMs);
    }
    return { ok: true, reading: settled, range, elapsedMs: elapsed() };
  } finally {
    try {
      await env.closeTab(tab);
    } catch (e) {
      /* a tab that will not close does not change what was read */
    }
  }

  /** A calendar value reduced to a sortable day number, or null when it is not a date. Local to the program. */
  function dayNumber(value: string): number | null {
    const raw = String(value || "").trim();
    const m = /^(\d{4})[.\-/]\s?(\d{1,2})[.\-/]\s?(\d{1,2})\.?$/.exec(raw) || /^(\d{4})(\d{2})(\d{2})$/.exec(raw);
    if (!m) {
      return null;
    }
    const y = Number(m[1]);
    const mo = Number(m[2]);
    const d = Number(m[3]);
    if (mo < 1 || mo > 12 || d < 1 || d > 31) {
      return null;
    }
    return y * 10000 + mo * 100 + d;
  }
}
