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
 * Four interactions exist here and no fifth is reachable:
 *
 *  1. open the calendar bound to the period's **from** field, and choose a day on it,
 *  2. the same for its **to** field,
 *  3. step the open calendar one month at a time,
 *  4. press the list's own 조회/검색.
 *
 * That is navigation of a read — the same thing a seller does with their own hands to look at last month. It
 * is not a mutation: nothing is written to a review, no review's state is changed, nothing is sent, nothing is
 * removed, no row-level control is touched, and no control the page's own predicate did not name is pressed.
 * `aside-guard` holds this file to `click` on count-guarded handles and to a bounded number of sites, so a
 * fifth interaction cannot be added without that test failing.
 *
 * <p>**There is no `fill` here any more, and that is a finding rather than a tidy-up.** The period's two
 * fields are `input[type=text]` with `readOnly = true`, inside the Seller Center's own range-picker component.
 * The first live catch-up typed into them and stopped at `RANGE_NOT_SETTABLE`, correctly. Typing is not a way
 * into this period; the calendar is the only way in, and so the calendar is what this lane learnt to use.
 *
 * <h2>One judge for «is this a control»</h2>
 *
 * The plan carries <b>pure-CSS candidate sets</b> and the page scripts
 * ({@code buildNaverReviewControlsScript}, {@code buildNaverReviewPickerScript}) are the only things that
 * decide which candidates are controls a person could use — visible, enabled, and carrying the right meaning.
 * They answer in indices into those same candidate sets, in document order, and this file acts on exactly
 * those indices.
 *
 * <p>That split is the lesson of 2026-10-08, not a refactor. The selectors then carried
 * `:visible:not([disabled])` while the page script made the same judgement again; Aside hands a selector to
 * `document.querySelectorAll`, the pseudo-class threw a `SyntaxError`, and the whole lane stopped at its third
 * gate. The syntax was the shallow half. The deep half is that one fact had two judges, and the one that ran
 * first had no test behind it.
 *
 * <h2>Why the census is read again before every press</h2>
 *
 * An index is only true for the document that produced it, and **opening a calendar adds controls to the
 * document**: the component is not in the page at all until its opener is pressed. So an opener index taken
 * before the first calendar opened is not the opener index afterwards. Every press here is made against a
 * census taken immediately before it, and each of those censuses re-asserts the gates — two date controls,
 * one 조회 — rather than trusting the first one. A stale index is how «press the control the page accepted»
 * silently becomes «press whatever is now in that position».
 *
 * <h2>Fail closed on identification — the gates, in order</h2>
 *
 * Choosing a day on the wrong page is the one mistake no later check can undo: the verification would then be
 * reading some other screen's inputs and agreeing with itself. So, in order:
 *
 *  1. **the surface** — the published host and hash, and a drawn grid. Anything else is `SURFACE_UNEXPECTED`.
 *  2. **the sign-in wall** — asked before anything, as everywhere else. A signed-out browser is a stop, never
 *     a thing to act on.
 *  3. **exactly two date controls and exactly one 조회** — as the page script accepted them. Zero, one or
 *     three dates is `RANGE_CONTROLS_NOT_FOUND` / `RANGE_CONTROLS_AMBIGUOUS` with the count it looked
 *     through; the query control has its own two codes. A candidate set this runtime could not even look
 *     through is `DATE_CONTROL_CANDIDATES_UNREADABLE` — named apart from `RUNTIME_FAULT` because «the
 *     selector was not a selector» and «the browser went away» need different fixes, and on 2026-10-08 they
 *     were the same word. Never «take the first two».
 *  4. **which field is the from** — read from the values the page ARRIVED with. The list opens on its own
 *     default period, so the two fields already say which of them holds the earlier date. If their current
 *     values do not establish that order, this stops: a range set into reversed fields reads as a valid
 *     range to every check that only looks at the two dates, and shows the seller's store an empty week.
 *  5. **one opener, structurally bound to that field** — the smallest group holding that one date control and
 *     exactly one pressable thing. Zero or several is a stop with the count.
 *  6. **a day view, agreed by three independent facts** — a `YYYY.MM` title, seven weekday headings, and a
 *     full grid of day cells. The probe that declared this calendar unusable had climbed from the readonly
 *     input to the input's own 190x34 wrapper and counted another view's template there; requiring all three
 *     is what makes that particular wrong answer impossible to reach.
 *  7. **one month step, and proof that it stepped** — identification narrows the field; the re-read of the
 *     title is what holds. There is no accessible label on this calendar's arrows (they are `<button>`s whose
 *     only child is an `aria-hidden` `<i>` with no text, no `title`, no `aria-label`), so a step is
 *     identified by two markers that must agree — an icon naming a single step, and a handler that moves
 *     inside the view already shown — and then the month must have moved by exactly one, in the direction
 *     asked for, or the read stops where it stands.
 *  8. **one selectable cell for the day asked for** — among the cells the page accepted as days of the month
 *     on show. `past` / `future` / `disabled` are the component's own words, and they are the whole reason a
 *     cell is never chosen by the number printed on it: a September page prints 「3」 twice.
 *
 * <h2>And then it proves the screen moved</h2>
 *
 * Each field is checked against the day that was asked for as soon as its calendar closes — a calendar that
 * did not take the day is `RANGE_NOT_SETTABLE` there and then. After the search, the census runs again and the
 * period on screen must be **the period that was asked for**, to the day. Not «we set the fields» — the
 * fields are an intention; the census is the screen. A different period, or an unreadable one, means nothing
 * is read and nothing is delivered.
 *
 * <p>No closure. Serialized with {@code Function.prototype.toString}; nothing here may reference an import or
 * a module-level identifier.
 */

export interface NaverWindowLocatorLike {
  count(): Promise<number>;
  nth(index: number): NaverWindowLocatorLike;
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

/** A KST civil day, as the calendar on screen draws it. */
export interface NaverWindowDay {
  year: number;
  month: number;
  day: number;
}

export interface NaverReviewWindowRuntimePlan {
  entryUrl: string;
  /** Forwarded page scripts, authored in `src/naver/`. This file composes none of them. */
  controlsScript: string;
  authScript: string;
  readerScript: string;
  rangeScript: string;
  /** The open calendar, read as structure: which cells are days of the month on show, and where the steps are. */
  pickerScript: string;
  /** The pure-CSS candidate set the controls script walked for date controls. Decides nothing on its own. */
  dateInputSelector: string;
  /** The pure-CSS candidate set it walked for everything pressable — the 조회, the openers, the month steps. */
  queryControlSelector: string;
  /** The pure-CSS candidate set the picker script walked for day cells. */
  dayCellSelector: string;
  /** The period asked for: the two days to choose, and the two offsets the screen must then show. */
  requestedStart: NaverWindowDay;
  requestedEnd: NaverWindowDay;
  requestedStartDaysBefore: number;
  requestedEndDaysBefore: number;
  /** How many single-month steps one field may take before the read gives up. */
  maxMonthMoves: number;
  /** How long the list's own router may take to draw the review route before the read gives up. */
  routeWaitTimeoutMs: number;
  routePollMs: number;
  settleTimeoutMs: number;
  pollMs: number;
  /** How long a calendar may take to draw or redraw before it is read. */
  pickerSettleMs: number;
  /** How long the list may take to redraw after the search before the census is asked. */
  searchSettleMs: number;
}

export type NaverWindowRuntimeResult =
  | { ok: true; reading: unknown; range: unknown; monthMoves: number; elapsedMs: number }
  | {
      ok: false;
      code:
        | "AUTH_REQUIRED"
        | "SURFACE_UNEXPECTED"
        | "ROUTE_NOT_READY"
        | "DATE_CONTROL_CANDIDATES_UNREADABLE"
        | "RANGE_CONTROLS_NOT_FOUND"
        | "RANGE_CONTROLS_AMBIGUOUS"
        | "QUERY_CONTROL_NOT_FOUND"
        | "QUERY_CONTROL_AMBIGUOUS"
        | "RANGE_ORDER_UNKNOWN"
        | "CALENDAR_OPENER_NOT_FOUND"
        | "CALENDAR_OPENER_AMBIGUOUS"
        | "PICKER_VIEW_UNREADABLE"
        | "MONTH_NAV_NOT_FOUND"
        | "MONTH_NAV_AMBIGUOUS"
        | "MONTH_NAV_UNVERIFIED"
        | "MONTH_NAV_EXHAUSTED"
        | "DAY_CELL_NOT_FOUND"
        | "DAY_CELL_AMBIGUOUS"
        | "RANGE_NOT_SETTABLE"
        | "RANGE_MISMATCH"
        | "READ_UNSETTLED"
        | "RUNTIME_FAULT";
      stage: "PREPARE" | "SURFACE" | "AUTH" | "CONTROLS" | "PICK_START" | "PICK_END" | "NAVIGATE" | "VERIFY" | "READ";
      /** How many candidates were found, when the stop was about a count. */
      candidates: number | null;
      /**
       * For a query-control stop: how many candidates carried the word before the form test.
       *
       * <p>Two numbers because the two tests fail for different reasons and the fix is in a different place:
       * «2 labelled, 0 accepted» means the period's form is not what we think it is; «0 labelled» means the
       * list does not print 조회 any more.
       */
      labelled: number | null;
      /** How many month steps had been taken when it stopped — audit, and the bound's own witness. */
      monthMoves: number;
      elapsedMs: number;
    };

export async function asideNaverReviewWindowRuntime(
  plan: NaverReviewWindowRuntimePlan,
  env: NaverWindowRuntimeEnv,
): Promise<NaverWindowRuntimeResult> {
  const startedAt = Date.now();
  const elapsed = () => Date.now() - startedAt;
  let monthMoves = 0;
  const fail = (
    code: Extract<NaverWindowRuntimeResult, { ok: false }>["code"],
    stage: Extract<NaverWindowRuntimeResult, { ok: false }>["stage"],
    candidates?: number,
    labelled?: number,
  ): NaverWindowRuntimeResult => ({
    ok: false,
    code,
    stage,
    candidates: typeof candidates === "number" ? candidates : null,
    labelled: typeof labelled === "number" ? labelled : null,
    monthMoves,
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

    type Census = {
      dateAccepted: number[];
      queryAccepted: number[];
      openers: { dateIndex: number; openerIndex: number; openerCandidates: number }[];
    };

    // Read the census and re-assert every gate. Called again before each press, because an index belongs to
    // the document that produced it and an open calendar is a different document.
    const readCensus = async (
      stage: Extract<NaverWindowRuntimeResult, { ok: false }>["stage"],
      requireGrid: boolean,
    ): Promise<{ ok: true; census: Census } | { ok: false; result: NaverWindowRuntimeResult }> => {
      let raw: unknown;
      try {
        raw = await tab.evaluate(plan.controlsScript);
      } catch (e) {
        return { ok: false, result: fail("RUNTIME_FAULT", stage) };
      }
      const s = (raw ?? {}) as {
        route?: unknown; grid?: unknown;
        dateCandidates?: unknown; dateAccepted?: unknown;
        queryLabelled?: unknown; queryAccepted?: unknown; openers?: unknown;
      };
      if (s.route !== true) {
        return { ok: false, result: fail("SURFACE_UNEXPECTED", "SURFACE") };
      }
      if (requireGrid && (typeof s.grid !== "number" || s.grid <= 0)) {
        return {
          ok: false,
          result: fail("SURFACE_UNEXPECTED", "SURFACE", typeof s.grid === "number" ? s.grid : undefined),
        };
      }
      const dateAccepted = s.dateAccepted;
      const queryAccepted = s.queryAccepted;
      if (!Array.isArray(dateAccepted) || !Array.isArray(queryAccepted)) {
        return {
          ok: false,
          result: fail("DATE_CONTROL_CANDIDATES_UNREADABLE", "CONTROLS",
            typeof s.dateCandidates === "number" ? s.dateCandidates : undefined),
        };
      }
      if (dateAccepted.length < 2) {
        return { ok: false, result: fail("RANGE_CONTROLS_NOT_FOUND", "CONTROLS", dateAccepted.length) };
      }
      if (dateAccepted.length > 2) {
        return { ok: false, result: fail("RANGE_CONTROLS_AMBIGUOUS", "CONTROLS", dateAccepted.length) };
      }
      if (queryAccepted.length === 0) {
        // `labelled` says which test excluded them: «two carried the word, none was in the period's form» and
        // «nothing on the page says 조회» send the next person to different halves of the problem.
        return {
          ok: false,
          result: fail("QUERY_CONTROL_NOT_FOUND", "CONTROLS", 0,
            typeof s.queryLabelled === "number" ? s.queryLabelled : undefined),
        };
      }
      if (queryAccepted.length > 1) {
        return {
          ok: false,
          result: fail("QUERY_CONTROL_AMBIGUOUS", "CONTROLS", queryAccepted.length,
            typeof s.queryLabelled === "number" ? s.queryLabelled : undefined),
        };
      }
      const openers: Census["openers"] = [];
      if (Array.isArray(s.openers)) {
        for (const entry of s.openers as unknown[]) {
          const o = (entry ?? {}) as { dateIndex?: unknown; openerIndex?: unknown; openerCandidates?: unknown };
          openers.push({
            dateIndex: typeof o.dateIndex === "number" ? o.dateIndex : -1,
            openerIndex: typeof o.openerIndex === "number" ? o.openerIndex : -1,
            openerCandidates: typeof o.openerCandidates === "number" ? o.openerCandidates : 0,
          });
        }
      }
      return {
        ok: true,
        census: {
          dateAccepted: dateAccepted.map((v) => Number(v)),
          queryAccepted: queryAccepted.map((v) => Number(v)),
          openers,
        },
      };
    };

    // 1 & 2. THE SURFACE AND THE SIGN-IN WALL. The wall is asked between them, because a signed-out page can
    // draw anything at all — including something that looks like a grid.
    // 1. WHERE WE LANDED — and the three different answers that used to be one word.
    //
    // `route` was one boolean over host AND hash, so a sign-in redirect, a route that had not drawn yet, and
    // a genuinely different page all read as SURFACE_UNEXPECTED. On 2026-10-09 the first of those happened:
    // no session, NAVER sent the tab to accounts.commerce.naver.com, and the seller was told the review
    // screen could not be found — «nothing you can do» — while the sign-in recovery built for exactly this
    // sat unused. The census now reports the halves apart, and this reads them apart.
    let landing: { hostOk: boolean; hashOk: boolean; authHost: boolean };
    try {
      landing = await readLanding();
    } catch (e) {
      return fail("RUNTIME_FAULT", "SURFACE");
    }
    if (landing.authHost) {
      // A host on the closed list of NAVER sign-in origins. The seller can act on this, so say so.
      return fail("AUTH_REQUIRED", "SURFACE");
    }
    if (!landing.hostOk) {
      // Any other host. A page nobody has seen is not a login page, and this lane will not say it is.
      return fail("SURFACE_UNEXPECTED", "SURFACE");
    }
    if (!landing.hashOk) {
      // The seller centre, with its router still working. Waited on rather than refused — `openTab` returns
      // when a page looks interactive, which for a hash-routed app can be before it has drawn.
      const deadline = Date.now() + plan.routeWaitTimeoutMs;
      for (;;) {
        if (Date.now() >= deadline) {
          return fail("ROUTE_NOT_READY", "SURFACE");
        }
        await env.wait(plan.routePollMs);
        try {
          landing = await readLanding();
        } catch (e) {
          return fail("RUNTIME_FAULT", "SURFACE");
        }
        if (landing.authHost) {
          return fail("AUTH_REQUIRED", "SURFACE");
        }
        if (!landing.hostOk) {
          return fail("SURFACE_UNEXPECTED", "SURFACE");
        }
        if (landing.hashOk) {
          break;
        }
      }
    }

    let auth: unknown;
    try {
      auth = await tab.evaluate(plan.authScript);
    } catch (e) {
      return fail("RUNTIME_FAULT", "AUTH");
    }
    if (!auth || typeof auth !== "object" || (auth as { signedIn?: unknown }).signedIn !== true) {
      return fail("AUTH_REQUIRED", "AUTH");
    }

    // 3. THE GATES, with the grid required this time.
    const gated = await readCensus("CONTROLS", true);
    if (!gated.ok) {
      return gated.result;
    }

    const dates = tab.locator(plan.dateInputSelector);
    const press = tab.locator(plan.queryControlSelector);
    const cells = tab.locator(plan.dayCellSelector);

    // 4. WHICH ONE IS THE FROM. Read off the default period the page arrived with, never assumed from DOM order.
    const firstIndex = Number(gated.census.dateAccepted[0]);
    const secondIndex = Number(gated.census.dateAccepted[1]);
    const first = dates.nth(firstIndex);
    const second = dates.nth(secondIndex);
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
    const earlierIsFirst = firstDay < secondDay;
    const fromIndex = earlierIsFirst ? firstIndex : secondIndex;
    const toIndex = earlierIsFirst ? secondIndex : firstIndex;

    type SideState = {
      open: boolean;
      year: number | null;
      month: number | null;
      prev: number[];
      next: number[];
      days: [number, number][];
    };

    const readSide = async (dateIndex: number): Promise<SideState | null> => {
      let raw: unknown;
      try {
        raw = await tab.evaluate(plan.pickerScript);
      } catch (e) {
        return null;
      }
      const r = (raw ?? {}) as { readable?: unknown; sides?: unknown };
      if (r.readable !== true || !Array.isArray(r.sides)) {
        return null;
      }
      for (const entry of r.sides as unknown[]) {
        const s = (entry ?? {}) as {
          dateIndex?: unknown; open?: unknown; year?: unknown; month?: unknown;
          prev?: unknown; next?: unknown; days?: unknown;
        };
        if (typeof s.dateIndex !== "number" || s.dateIndex !== dateIndex) {
          continue;
        }
        const days: [number, number][] = [];
        if (Array.isArray(s.days)) {
          for (const pair of s.days as unknown[]) {
            if (Array.isArray(pair) && typeof pair[0] === "number" && typeof pair[1] === "number") {
              days.push([pair[0], pair[1]]);
            }
          }
        }
        return {
          open: s.open === true,
          year: typeof s.year === "number" ? s.year : null,
          month: typeof s.month === "number" ? s.month : null,
          prev: Array.isArray(s.prev) ? (s.prev as unknown[]).map((v) => Number(v)) : [],
          next: Array.isArray(s.next) ? (s.next as unknown[]).map((v) => Number(v)) : [],
          days,
        };
      }
      return null;
    };

    // 5 … 8 for one field: open its calendar, step to the month, choose the day, prove the field took it.
    const pickDay = async (
      dateIndex: number,
      field: NaverWindowLocatorLike,
      want: NaverWindowDay,
      stage: "PICK_START" | "PICK_END",
    ): Promise<NaverWindowRuntimeResult | null> => {
      // The census is read HERE, not reused: the other field's calendar may have come and gone since, and
      // every control it added or took away shifted the positions this press is about to use.
      const fresh = await readCensus(stage, true);
      if (!fresh.ok) {
        return fresh.result;
      }
      let bound: { dateIndex: number; openerIndex: number; openerCandidates: number } | null = null;
      for (const entry of fresh.census.openers) {
        if (entry.dateIndex === dateIndex) {
          bound = entry;
        }
      }
      if (bound === null || bound.openerCandidates === 0) {
        return fail("CALENDAR_OPENER_NOT_FOUND", stage, bound === null ? 0 : bound.openerCandidates);
      }
      if (bound.openerIndex < 0) {
        return fail("CALENDAR_OPENER_AMBIGUOUS", stage, bound.openerCandidates);
      }
      const opener = press.nth(bound.openerIndex);
      try {
        await opener.click();
      } catch (e) {
        return fail("RUNTIME_FAULT", stage);
      }
      await env.wait(plan.pickerSettleMs);

      let state = await readSide(dateIndex);
      if (state === null || !state.open || state.year === null || state.month === null) {
        return fail("PICKER_VIEW_UNREADABLE", stage);
      }

      const wantMonths = want.year * 12 + want.month;
      // The bound is PER FIELD. Both calendars open on the list's current month, so a period a year back needs
      // about twelve steps on each of them; a shared allowance would stop the second field halfway for no
      // reason but the first one's distance. `monthMoves` still counts the whole read, for the audit.
      let moves = 0;
      for (;;) {
        const haveMonths = state.year * 12 + state.month;
        if (haveMonths === wantMonths) {
          break;
        }
        if (moves >= plan.maxMonthMoves) {
          return fail("MONTH_NAV_EXHAUSTED", stage, moves);
        }
        const back = haveMonths > wantMonths;
        const steps = back ? state.prev : state.next;
        if (steps.length === 0) {
          return fail("MONTH_NAV_NOT_FOUND", stage, 0);
        }
        if (steps.length > 1) {
          return fail("MONTH_NAV_AMBIGUOUS", stage, steps.length);
        }
        const step = press.nth(Number(steps[0]));
        try {
          await step.click();
        } catch (e) {
          return fail("RUNTIME_FAULT", stage);
        }
        moves += 1;
        monthMoves += 1;
        await env.wait(plan.pickerSettleMs);
        state = await readSide(dateIndex);
        if (state === null || !state.open || state.year === null || state.month === null) {
          return fail("PICKER_VIEW_UNREADABLE", stage);
        }
        // The identification narrowed the field to one control; THIS is what holds. One month, in the
        // direction asked for — anything else and the arrow was not the arrow we thought it was.
        if (state.year * 12 + state.month - haveMonths !== (back ? -1 : 1)) {
          return fail("MONTH_NAV_UNVERIFIED", stage, moves);
        }
      }

      const matching: number[] = [];
      for (const pair of state.days) {
        if (pair[1] === want.day) {
          matching.push(pair[0]);
        }
      }
      if (matching.length === 0) {
        return fail("DAY_CELL_NOT_FOUND", stage, state.days.length);
      }
      if (matching.length > 1) {
        return fail("DAY_CELL_AMBIGUOUS", stage, matching.length);
      }
      const cell = cells.nth(Number(matching[0]));
      try {
        await cell.click();
      } catch (e) {
        return fail("RUNTIME_FAULT", stage);
      }
      await env.wait(plan.pickerSettleMs);

      let took = "";
      try {
        took = await field.inputValue();
      } catch (e) {
        return fail("RUNTIME_FAULT", stage);
      }
      if (dayNumber(took) !== want.year * 10000 + want.month * 100 + want.day) {
        return fail("RANGE_NOT_SETTABLE", stage);
      }
      return null;
    };

    const startStop = await pickDay(fromIndex, dates.nth(fromIndex), plan.requestedStart, "PICK_START");
    if (startStop !== null) {
      return startStop;
    }
    const endStop = await pickDay(toIndex, dates.nth(toIndex), plan.requestedEnd, "PICK_END");
    if (endStop !== null) {
      return endStop;
    }

    // NAVIGATE — the search, on an index read after both calendars are done with.
    const ready = await readCensus("NAVIGATE", true);
    if (!ready.ok) {
      return ready.result;
    }
    const search = press.nth(Number(ready.census.queryAccepted[0]));
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
    return { ok: true, reading: settled, range, monthMoves, elapsedMs: elapsed() };
  } finally {
    try {
      await env.closeTab(tab);
    } catch (e) {
      /* a tab that will not close does not change what was read */
    }
  }

  /**
   * Where the tab actually is, as three booleans from the page's own census. Throws only when the evaluate
   * itself fails, which the caller reads as a runtime fault rather than a verdict about the page.
   */
  async function readLanding(): Promise<{ hostOk: boolean; hashOk: boolean; authHost: boolean }> {
    const raw = await tab.evaluate(plan.controlsScript);
    const r = (raw ?? {}) as { hostOk?: unknown; hashOk?: unknown; authHost?: unknown };
    return {
      hostOk: r.hostOk === true,
      hashOk: r.hashOk === true,
      authHost: r.authHost === true,
    };
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
