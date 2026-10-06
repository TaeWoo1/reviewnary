import { useCallback, useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { BtnLink } from "../../components/ui/Btn";
import { Dot } from "../../components/ui/ObjectRow";
import { ConversationWorkspace } from "../../components/conversation/ConversationWorkspace";
import { FIRST_USE_PROMPTS, HOME_PROMPTS } from "../../components/conversation/surfacePrompts";
import { useConversation } from "../../lib/conversation/ConversationProvider";
import { useAgentSurface } from "../../lib/agentPanel";
import { useApiData } from "../../lib/useApiData";
import { api } from "../../lib/apiClient";
import { analytics } from "../../lib/analytics";
import { DISCONNECTED_HEADLINE } from "../../lib/briefing";
import { delegableSentence, firstUseSteps, homeFirstUseState, noDataSentence } from "../../lib/homeFirstUse";
import { matchCommandIntent, INTENT_HEADING } from "../../lib/commandIntents";
import { INQUIRY_NEEDS_REPLY_PATH } from "../../lib/todayInbox";
import { CustomerOpsHome, TodayWorkspace, coHomeApplies } from "../../components/customerOperations/CustomerOpsHome";
import { COPY } from "../../lib/copy/customerOps";
import { UNANSWERED_WORD } from "../../lib/homeSummary";
import type { CustomerOperationsHome } from "../../lib/customerOperationsTypes";
import type { InquiryListArtifact } from "../../lib/conversation/types";
import type { InquiryQueueResponse, MetricKpi, OperationsHome, OverviewResponse, ReviewIssueView } from "../../lib/types";

/**
 * 홈 — the Agent operating workspace (Agentic Operating Workspace v2 §3-C).
 *
 * <b>Work-first, chat as a command bar (Home v3, 2026-09-23).</b> This docblock used to say 「the thread
 * is the page」 and had been wrong for some time: for an org with 고객 운영 관리 open, Home is
 * {@link TodayWorkspace} — the 확인할 일 list with the selected item beside it — and the conversation is a
 * one-line dock under the list. The design contract said the same stale thing and was corrected with this
 * (`docs/reviewnary_design.md` §8-A v3.2).
 *
 * <p>What the screen answers is unchanged — 「오늘 무엇을 해야 하지?」 — and the answer is the LIST. The top
 * carries only obligations (확인할 일 · 실행 대기); 반복 문제 keeps its own section below the work because it
 * is a pattern, not a customer waiting; KPI, trend and per-channel numbers stay at `/overview` and are not
 * copied here. Nothing is chosen until the seller chooses it, and what they chose can be closed.
 *
 * <p><b>그 일이 없는 org도 같은 화면을 본다</b> (product-owner decision, 2026-10-06). 전에는 여기서 갈라져
 * 네 개의 성긴 영역(지금 확인할 리뷰 · 반복 문제 · 최근 수집 상태 · 준비된 작업)과 대화 턴 하나로 된 두 번째
 * Home이 그려졌고, 어느 쪽이 보이는지는 판매자의 데이터가 아니라 배포 설정과 그 한 번의 읽기가 성공했는지가
 * 정했다 — 같은 판매자가 backend가 잠깐 죽은 아침에 다른 제품을 열었다는 뜻이다. 이제 골격은 하나이고
 * ({@link CustomerOpsHome}), 자동 확인에 <b>속한</b> 줄만 그 일이 있을 때 선다. 그 Home과 함께 대화 브리핑
 * (opener turn · 작업량 문장 · 예시 칩)도 사라졌다 — 할 일 목록이 그것을 이미 말한다.
 *
 * <p>남은 대화형 화면은 <b>첫 연결 전</b>뿐이다: 연결할 것이 없으면 목록이 아니라 「무엇을 연결하면 무엇을
 * 받는가」가 화면이고, 그때는 예시 칩이 서는 것이 맞다.
 *
 * <b>The palette is a shortcut, not a planner.</b> A typed sentence that is exactly one of three
 * labels answers locally with an object the page already has; every other sentence goes to the
 * runtime, where the planner plans it or the turn fails.
 */
export function AgentHome({ now = new Date() }: { now?: Date }) {
  useAgentSurface({ surface: "home", label: "오늘의 운영" });
  const conversation = useConversation();
  const overview = useApiData<OverviewResponse>(() => api.getOverviewStrict(7), []);
  /**
   * Operations Home's own read — the four areas above the thread.
   *
   * <b>Fail-soft, and that is the whole failure design.</b> When this read does not land the areas are
   * simply not drawn and the conversation below is untouched: a Home that rendered 「확인 필요 0건」
   * because a query timed out would be telling a seller their morning is clear on the strength of a
   * failure. The overview strip and the proactive turn are separate reads and keep working.
   */
  const [home, setHome] = useState<OperationsHome | null | undefined>(undefined);

  useMemo(() => analytics.track("today_inbox_viewed"), []);

  /**
   * The pilot's one return-visit signal (Pilot Launch Readiness §2) — 「이 조직이 오늘 홈을 열었다」.
   *
   * Deliberately NOT routed through `analytics` above: that module is for vendor sinks, is a no-op
   * without vendor env, and is gated on 분석 consent. This one leaves no deployment, names no person,
   * and is recorded once per organisation per Asia/Seoul day by the server. Fire and forget — nothing
   * awaits it, nothing renders from it, and a failure is silence.
   */
  useEffect(() => {
    void api.recordHomeOpened().catch(() => {});
  }, []);

  useEffect(() => {
    let live = true;
    api
      .getOperationsHomeStrict()
      .then((r) => {
        if (live) setHome(r);
      })
      .catch(() => {
        if (live) setHome(null);
      });
    return () => {
      live = false;
    };
  }, []);

  /**
   * A repeated problem changed state in the 오늘 pane (UI/UX v2 Phase 2). The server's answer is the new truth, so
   * the row in the list is replaced with it at once — no stale lifecycle word beside a pane that says otherwise —
   * and the Home read runs again for the counts only it can recompute. A failed re-read keeps what is on screen.
   */
  const onProblemChanged = useCallback((next: ReviewIssueView) => {
    setHome((current) =>
      current
        ? {
            ...current,
            problems: {
              ...current.problems,
              rows: current.problems.rows.map((row) => (row.issue.id === next.id ? { ...row, issue: next } : row)),
            },
          }
        : current,
    );
    void api
      .getOperationsHomeStrict()
      .then((r) => setHome(r))
      .catch(() => undefined);
  }, []);

  /**
   * 고객 운영 관리's own read (Customer Operations v3.1). When the job is open for this org the Home IS its
   * 「자동 확인 → 내 확인 필요」 and one 「확인 필요」 list; otherwise the Home stays what it was. `undefined` = not
   * read yet — nothing is drawn in its place, so the page does not flash the other Home first.
   */
  const [co, setCo] = useState<CustomerOperationsHome | null | undefined>(undefined);
  const loadCo = useCallback(() => {
    // Through a resolved promise so a client without this call (an older test double) is a failed read, not a crash.
    return Promise.resolve()
      .then(() => api.getCustomerOperationsHome())
      .then((r) => setCo(r))
      .catch(() => setCo(null));
  }, []);
  useEffect(() => {
    void loadCo();
  }, [loadCo]);

  /**
   * **What is waiting is the WORK QUEUE, not the inquiry feed** (Chat-first Outcome & Visual Closure v1
   * §1). The brief read `/api/inquiries/rows?status=UNANSWERED` — the customer's inquiries as records —
   * while its number came from a freshness-qualified KPI, and the two are different questions with
   * different answers: measured on the real org, 10 actionable work items against 21 unanswered records.
   * Neither number is wrong; they were being shown as one. The home brief is about what the seller has to
   * DO, so it now asks the queue that owns that — one read, and its own total.
   *
   * `undefined` = not read yet (say nothing about it), `null` = the read failed.
   */
  const [queue, setQueue] = useState<InquiryQueueResponse | null | undefined>(undefined);
  useEffect(() => {
    let live = true;
    api
      .getInquiryQueueStrict({ size: BRIEF_ROWS })
      .then((r) => {
        if (live) setQueue(r);
      })
      .catch(() => {
        if (live) setQueue(null);
      });
    return () => {
      live = false;
    };
  }, []);

  const data = overview.data;
  // §2: three first-use states, one derivation, from the numbers already on the page. A failed read is
  // `null` and claims nothing — telling a connected seller they have no channels is the one error this
  // screen can make that they cannot check.
  const firstUse = data ? homeFirstUseState(data.metrics.channels) : null;
  const beforeFirstConnection = firstUse?.kind === "NO_CHANNEL";
  // §5: when the brief names the waiting inquiries it also says how many — so the strip stops saying
  // it. Before this the seller read 「현재 미답변 문의 12건」 and 「…문의가 12건 있습니다」 one line apart.
  const strip = data ? contextStrip(data, (queue?.content.length ?? 0) > 0, queue?.totalElements ?? null) : [];
  const anyUnproven = strip.some((kpi) => kpi.freshnessUnproven);

  // 고객 운영 관리가 열린 org에서는 그 일의 상태(자동 확인 · 마지막 확인 · 처리한 것)까지 말한다. 열려 있지
  // 않으면 `null`이고, 화면의 골격은 그대로 서며 그 일에 속한 줄만 빠진다.
  const coHome = !beforeFirstConnection && coHomeApplies(co) ? co : null;
  /**
   * <b>연결된 org의 Home은 하나다</b> (product-owner decision, 2026-10-06).
   *
   * <p>전에는 고객 운영 관리가 열린 org만 이 화면(오늘 · 숫자 · 오늘 먼저 볼 일 · 오늘 달라진 점 · 실행 대기 ·
   * 반복 문제 · 채널)을 보고, 나머지는 네 개의 성긴 영역과 대화 턴으로 된 <b>다른 Home</b>을 봤다. 어느 쪽이
   * 보이는지를 정한 것은 판매자의 데이터가 아니라 배포 설정({@code RESPONSIBILITY_RUNTIME_ORG_IDS})과 그 한 번의
   * 읽기가 성공했는지였다 — 즉 backend가 잠깐 죽은 아침에 같은 판매자가 다른 제품을 열었다. 골격은 이제 하나이고,
   * 자동 확인에 <b>속한</b> 것만 그 일이 있을 때 말한다({@link CustomerOpsHome}).
   *
   * <p>첫 연결 전은 여전히 예외다 — 그때는 할 일 목록이 아니라 「무엇을 연결하면 무엇을 받는가」가 화면이다.
   */
  const workspace = !overview.loading && !beforeFirstConnection && co !== undefined && firstUse?.kind !== "NO_DATA";
  const onBeforeSend = useCallback(
    (text: string): boolean => {
      if (!conversation) return false;
      const key = matchCommandIntent(text);
      if (!key) return false;
      if (key === "TODAY") {
        conversation.addLocalTurn(text, { message: "오늘 확인할 일은 이 대화 맨 위에 정리해 두었습니다.", artifacts: [] });
        return true;
      }
      if (key === "UNANSWERED_INQUIRIES") {
        // 「답변이 필요한 문의」 is ONE question, so the heading, the count, the rows and the destination
        // all come from ONE read. It used to title the answer with the freshness-qualified KPI and fill
        // it with the OPEN work queue — two different sets under one sentence, so the number never
        // described the rows beneath it and neither described the screen the 전체 보기 opened.
        void api
          .getInquiryRowsStrict({ status: "UNANSWERED", order: "OLDEST", limit: 5 })
          .then((page) => {
            const artifact: InquiryListArtifact = {
              artifactId: "local-inquiries",
              type: "INQUIRY_LIST",
              title: `${INTENT_HEADING.UNANSWERED_INQUIRIES} ${page.totalCount}건`,
              totalCount: page.totalCount,
              groups: [
                {
                  key: "UNANSWERED",
                  label: "답변 필요",
                  items: page.items.map((item) => ({
                    workItemId: item.workItemId,
                    inquiryId: item.inquiryId,
                    channelCode: item.channelCode,
                    channelNameKo: item.channelNameKo,
                    receivedAt: item.receivedAt,
                    phase: item.phase,
                    status: item.status,
                    title: item.title,
                    productId: item.productId,
                    productName: item.productName,
                    answerBasis: null,
                    to: `/inquiries/${item.inquiryId}`,
                  })),
                },
              ],
              more: { label: "문의 화면에서 전체 보기", to: INQUIRY_NEEDS_REPLY_PATH },
            };
            conversation.addLocalTurn(text, { message: "바로 보여드립니다.", artifacts: [artifact] });
          })
          .catch(() => conversation.addLocalTurn(text, { message: "문의를 읽지 못했습니다. 문의 화면에서 확인해 주세요.", artifacts: [] }));
        return true;
      }
      return false;
    },
    [conversation, data],
  );

  // What an EMPTY thread opens with: the greeting (arithmetic, never a model) and — as a secondary,
  // single muted line — the three numbers the old strip carried. The transcript is the surface; the
  // numbers are context, one press from `/overview` where nothing moved.
  //
  // <b>The greeting is not the briefing</b> (Agentic Experience v2 §4). 「안녕하세요.」 was the largest
  // text on the page and said the least, with the agent turn under it saying the same thing WITH the
  // work attached — a hello, a numbers line and a brief, three layers before anything actionable. Once
  // there is a brief, the greeting joins the numbers as one quiet line and the brief is the headline.
  // Before the first connection there is no brief and nothing else to say, so the headline stays.
  const legacyLead = (
    <div className="space-y-2">
      {/* <b>A visible page title</b> (UI System v2). This was `sr-only`, so the branch of 오늘 that an org
          without 고객 운영 관리 sees had no page title at all — the first visible thing was either a
          greeting or a first-use headline, and neither names the screen. The other branch's title is the
          same word at the same size, which is the point: both are 오늘. */}
      <h1 className="break-keep text-title font-bold leading-tight tracking-tight text-ink">{COPY.homeTitle}</h1>
      {beforeFirstConnection && firstUse ? (
        // §2 — nothing is connected. What is missing is not a briefing: it is the one thing that can be
        // done, plus what doing it hands over. The sentence names the data types the channels on this
        // seller's own table actually offer, and there is exactly one next action.
        <section className="space-y-1" aria-label="오늘의 브리핑" data-testid="first-use-no-channel">
          <p className="break-keep text-xl font-bold leading-tight text-ink" aria-live="polite">{DISCONNECTED_HEADLINE}</p>
          <p className="break-keep text-base text-muted">{delegableSentence(firstUse)}</p>
          {/* First-use v2 — a headline, one sentence and a button told a seller who had just signed up
              nothing about what they were handing over. Three steps, in the order they happen; the
              middle one is the only promise and it is derived from this seller's own channel table. */}
          <ol className="space-y-2 pt-3">
            {firstUseSteps(firstUse).map((step) => (
              <li key={step.title} className="break-keep">
                <span className="text-sm font-semibold text-ink">{step.title}</span>
                <span className="text-sm text-muted"> — {step.detail}</span>
              </li>
            ))}
          </ol>
          <div className="pt-3">
            <BtnLink to="/connect">채널 연결하기</BtnLink>
          </div>
        </section>
      ) : firstUse?.kind === "NO_DATA" ? (
        // §2 — connected, and nothing has arrived yet. 「먼저 확인할 일은 없습니다」 would be arithmetic
        // truth and operational nonsense on the morning a seller connected: it reads as "the product
        // looked and your store is quiet", which is a claim about their business that no read supports.
        <section className="space-y-1" aria-label="오늘의 브리핑" data-testid="first-use-no-data">
          <p className="break-keep text-xl font-bold leading-tight text-ink" aria-live="polite">{noDataSentence(firstUse)}</p>
          <div className="pt-3">
            <BtnLink to="/connect" variant="outline">채널 연결 상태 보기</BtnLink>
          </div>
        </section>
      ) : null}
      {data && !beforeFirstConnection ? (
        // Reviewnary Visual System v1 §2 — the numbers are the smallest thing on the morning screen.
        // They qualify the briefing under them; a seller who wants them presses 「자세한 숫자 보기」.
        // `sm`, like the other branch's context line: the two Homes state their context at one size.
        // At `xs` this line was the smallest text on the morning screen and carried the only number on it.
        <p className="flex flex-wrap items-center gap-x-2 gap-y-0.5 text-sm text-muted" aria-label="오늘 상태">
          <span className="font-medium text-ink">{greetingLine(now.getHours(), null)}</span>
          {strip.map((kpi) => (
            <span key={kpi.key} className="flex items-center gap-x-2">
              {/* A drawn dot, not a glyph: 「·」 in `line` colour is a text node and it measured 1.12:1
                  — the last two AA violations on this page were both this separator. */}
              <Dot />
              <Link to={STRIP_ROUTE[kpi.key] ?? "/overview"} className="hover:text-ink hover:underline">
                {kpi.label} <span className="font-semibold tabular-nums text-ink">{kpi.value.toLocaleString("ko-KR")}</span>{kpi.unit ?? "건"}
              </Link>
            </span>
          ))}
          {/* Collection state is context, not an alarm: it qualifies the numbers beside it and it is
              read in the same breath as them (secondary disclosure). The warn colour was spending the
              page's strongest signal on machinery. */}
          {anyUnproven ? (
            <span className="flex items-center gap-x-2"><Dot /><span>일부 채널 최신 수집 확인 필요</span></span>
          ) : null}
          <Link to="/overview" className="font-semibold text-brand-700 hover:underline">자세한 숫자 보기</Link>
        </p>
      ) : overview.error ? (
        <p className="text-sm text-muted">
          운영 숫자를 읽지 못했습니다. <Link to="/overview" className="font-semibold text-brand-700 hover:underline">자세한 숫자 보기</Link>
        </p>
      ) : null}

    </div>
  );

  const lead = workspace ? (
    <CustomerOpsHome co={coHome} ops={home} now={now} onChanged={() => void loadCo()} metrics={data?.metrics ?? null} insights={data?.insights ?? null} />
  ) : co === undefined && firstUse?.kind === "WORKING" ? (
    // The job's read has not landed. Draw nothing in its PLACE — rendering the other Home for a moment
    // would flash a different screen — but the title is not in its place: it is the one thing both
    // branches print, it is true before either read lands, and `sr-only` here meant a sighted seller
    // watched a blank page while an invisible heading claimed the screen had one.
    <h1 className="break-keep text-title font-bold leading-tight tracking-tight text-ink">{COPY.homeTitle}</h1>
  ) : (
    legacyLead
  );

  return (
    <ConversationWorkspace
      surface="home"
      lead={lead}
      chips={beforeFirstConnection ? FIRST_USE_PROMPTS : workspace ? [] : HOME_PROMPTS}
      placeholder={workspace ? COPY.composer : "무엇이든 물어보세요"}
      onBeforeSend={onBeforeSend}
      // 오늘 (Home v3.1): the list is the subject here, so the box gives up the shell's one elevation.
      quietDock={workspace}
      // 오늘 (UI/UX v2 Phase 1): the job's Home is a work list with the selected item beside it, and the box sits
      // under the list. The first sentence turns it back into the transcript. Example prompts are left out here —
      // four chips under the box every morning were the same four sentences, and the box already says what it takes.
      emptyLayout={
        workspace
          ? (dock) => (
              <TodayWorkspace
                co={coHome}
                ops={home}
                now={now}
                onChanged={() => void loadCo()}
                onProblemChanged={onProblemChanged}
                metrics={data?.metrics ?? null}
                insights={data?.insights ?? null}
                dock={dock}
              />
            )
          : undefined
      }
    />
  );
}

/**
 * Deterministic. `count` null = not yet read (say nothing about it). Zero says only hello — whether
 * anything is WAITING is the opener turn's sentence (§11), computed from the real workload, so the
 * greeting never contradicts it.
 */
export function greetingLine(hour: number, count: number | null): string {
  const hello = hour < 12 ? "좋은 아침입니다." : "안녕하세요.";
  if (count === null || count === 0) return hello;
  return `${hello} 오늘 제가 먼저 확인한 일이 ${count}개 있습니다.`;
}

const STRIP_ROUTE: Record<string, string> = {
  unansweredInquiries: "/inquiries",
};

/**
 * <b>The Home's numbers are obligations, and obligations only</b> (product-owner decision, 2026-09-30).
 *
 * <p>It used to return three: the waiting inquiries, 「오늘 주문」 (or the 7-day KPI), and 「최근 7일 부정
 * 리뷰」. The last two are not obligations — nothing about them is waiting for the seller — and
 * 주문량 · 매출 · 추이 · 채널별 수치 are owned by `/overview`, which this line still links to. Drawing them
 * here was the Home quoting the dashboard, and the same number under two definitions in two places is the
 * defect this repository has fixed several times (`docs/reviewnary_design.md` §8-A). Nothing was deleted:
 * both numbers are one press away, unchanged, on the screen that owns them.
 *
 * <p><b>And it uses the other Home's nouns.</b> 확인할 일 and 현재 미답변 are what
 * {@link CustomerOpsHome}'s band says, so a seller whose org opens the job and a seller whose org does not
 * read the same two words for the same two facts. Two names twelve pixels apart on two variants of one
 * screen is how one product starts reading as two.
 *
 * <p>`briefNamesInquiries` = the opener turn below is naming the waiting inquiries and their count, so
 * this line drops that number rather than printing it a second time six inches above (§5). With the other
 * two retired, that leaves nothing — and nothing is the correct render: the brief already said it.
 */
export function contextStrip(
  data: OverviewResponse, briefNamesInquiries = false, actionable: number | null = null,
): MetricKpi[] {
  const kpis = data.metrics.kpis;
  const find = (key: string) => kpis.find((k) => k.key === key);
  const out: MetricKpi[] = [];
  const unanswered = find("unansweredInquiries");
  /**
   * **The strip's inquiry number is the same 「처리할 일」 the brief means** (Chat-first Semantic &
   * Surface Finalization v1 §2).
   *
   * It used to be the freshness-qualified KPI, which excludes channels whose collection is unproven —
   * so a seller read 「현재 미답변 문의 0건」 directly above 「지금까지 들어온 문의 3건」 and had to know an
   * internal definition to see that both were true. The queue's own total is what the whole screen
   * means by work, so the strip says that, and the KPI keeps its place on the numbers screen where its
   * exclusions are shown. When the queue read has not landed the old number stands rather than a
   * fabricated zero.
   */
  if (unanswered && !briefNamesInquiries) {
    out.push(actionable != null
      ? { ...unanswered, label: COPY.listTitle, value: actionable }
      : { ...unanswered, label: UNANSWERED_WORD.lead });
  }
  return out;
}

/** 브리핑 한 줄이 얼마나 깊게 읽는가 — 셋은 브리핑이고 열은 문장이 붙은 큐다. */
export const BRIEF_ROWS = 3;
