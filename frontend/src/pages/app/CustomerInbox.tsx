import { useCallback, useEffect, useMemo, useState, type ReactNode } from "react";
import { analytics } from "../../lib/analytics";
import { Link, useParams, useSearchParams } from "react-router-dom";
import { PageHead } from "../../components/ui/PageHead";
import { Empty } from "../../components/ui/Empty";
import { Btn, BtnLink } from "../../components/ui/Btn";
import { Status } from "../../components/ui/Status";
import { AgentLaunch } from "../../components/ui/AgentLaunch";
import { FilterTab } from "../../components/ui/FilterTab";
import { QuietSelect } from "../../components/ui/QuietSelect";
import { Fact } from "../../components/ui/DecisionRow";
import { InboxDetail } from "../../components/inbox/InboxDetail";
import { InquiryReplyDock } from "../../components/inbox/InquiryReply";
import { useInquiryReply } from "../../components/inbox/useInquiryReply";
import { Facts } from "../../components/ui/ObjectRow";
import { MasterDetail, useWideLayout } from "../../components/workspace/MasterDetail";
import { CaseLayout } from "../../components/workspace/CaseLayout";
import { api } from "../../lib/apiClient";
import { analysisKey, buildAnalysisIndex } from "../../lib/inboxView";
import { plainText, previewText } from "../../lib/plainText";
import { kstDate } from "../../lib/format";
import { elapsedLabel, waitIsDated } from "../../lib/copy/customerOps";
import { productChannelLabel } from "../../lib/productRows";
import {
  RECORD_STATUS_OPTIONS,
  asFeedItem,
  recordRowState,
} from "../../lib/inquiryWorkspace";
import { inquiryReading } from "../../lib/inquiryNextAction";
import type { InquiryQueueItem, InquiryRowItem, ItemAnalysis } from "../../lib/types";
import { useAgentSurface } from "../../lib/agentPanel";

/**
 * 문의 — 지금 처리할 일, then 전체 문의 (Inquiry Operations Workspace v1).
 *
 * <b>The shape, and why it is two reads.</b> This screen used to make one: the inbox feed, `limit=500`,
 * every row rendered in a single column. On the demo org that was 94 rows and 7,100px, an answered
 * inquiry from last week carrying the same weight as the oldest unanswered one, with no way to search.
 * A seller with three thousand inquiries would have been handed three thousand rows.
 *
 * So the two questions are asked separately, of the two reads the product already had:
 *
 * <ul>
 *   <li><b>지금 처리할 일</b> — the WORK QUEUE (`GET /api/inquiries?phase=…`). Its membership is
 *       `InquiryWorkItemPhase.AWAITING_SELLER` = {OPEN, PROPOSED}, declared once in the backend as
 *       「the phases where the work is still waiting for the SELLER to decide something」 and read by
 *       every recommendation surface. Nothing about inclusion is decided on this screen.</li>
 *   <li><b>전체 문의</b> — the RECORD (`GET /api/inquiries/rows`), filtered server-side by 검색 · 채널 ·
 *       답변 상태 · 상품, bounded to one page, with the whole set's count beside it.</li>
 * </ul>
 *
 * <b>An inquiry may be in both.</b> In the queue it is work; in the record it is a record. 리뷰 has
 * read this way since Review Approval Path v1 — its 목록 holds all 4,455 rows including the four in
 * 내 답변 작업.
 *
 * <b>Two shapes, kept.</b> Nothing chosen → the two sections are the page. A row chosen → they step
 * back to a 340px rail and 고객 문의 + AI 답변 take the rest, which is the layout Executive-friendly UX
 * Redesign v1 measured and this package had no reason to disturb. The detail is the same
 * `InboxDetail`, on the same `/inquiries/{inquiryId}` route a chat artifact links to.
 *
 * <b>A deep link always opens.</b> When the named inquiry is not on the page the filters happen to be
 * showing, it is fetched by id through the SAME read — one indexed lookup, where the screen used to
 * keep 500 rows loaded so that any link would resolve.
 *
 * <b>The mixed 문의+리뷰 mode is gone.</b> It was a `scope` prop no route had passed since product
 * assembly A2 (`/inbox` redirects here), kept in case a screen wanted it back. Two reads later it was
 * a branch nothing could reach, describing a feed this screen no longer makes; a mode with no caller
 * is not an option, it is unexercised code claiming to be one.
 */
export function CustomerInbox() {
  const { itemRef } = useParams();
  // Growth funnel: 문의 workflow opened (no item ref, no text).
  useEffect(() => {
    analytics.track("inquiry_opened");
  }, []);

  const [queue, setQueue] = useState<InquiryQueueItem[] | null>(null);
  const [record, setRecord] = useState<InquiryRowItem[] | null>(null);
  const [recordTotal, setRecordTotal] = useState<number | null>(null);
  // How many pages of the record have been asked for. Rows accumulate rather than replace, so 더 보기
  // lengthens the list a seller is reading instead of moving them to a page they have to navigate back
  // from. Reset by any change to the filters, because a page number means nothing across two questions.
  const [recordPages, setRecordPages] = useState(1);
  const [linked, setLinked] = useState<InquiryRowItem | null>(null);
  const [analyses, setAnalyses] = useState<ItemAnalysis[]>([]);
  /** The server's own total for the queue read — the page below it may be smaller. */
  const [queueTotal, setQueueTotal] = useState<number | null>(null);
  /**
   * <b>탭이 세는 것</b> — 같은 읽기, 같은 조건, 상태만 다르게 세 번.
   *
   * <p>탭은 목록의 축이므로 각 탭은 자기 숫자를 들고 있어야 한다(리뷰·반복 문제가 그렇다). 그 숫자는
   * 지금 걸려 있는 <b>다른</b> 조건(검색어·채널·상품)을 그대로 통과해야 한다 — 「세금계산서」로 좁혀 놓고
   * 탭이 조직 전체의 102를 말하면 탭이 거짓말을 하는 것이다. 그래서 세 번 모두 `limit=1`로, 행이 아니라
   * 합계만 받는다. 실패한 읽기는 숫자를 비워 둔다: 틀린 숫자보다 없는 숫자가 낫다.
   */
  const [counts, setCounts] = useState<{ all: number | null; unanswered: number | null; answered: number | null }>({
    all: null,
    unanswered: null,
    answered: null,
  });
  const [failed, setFailed] = useState(false);
  const [loading, setLoading] = useState(true);

  /**
   * The record's filters ARE the URL, so a narrowed list is a link a seller can keep and a doorway
   * from another screen is an ordinary navigation. `productId` is the one a product opens.
   */
  const [searchParams, setSearchParams] = useSearchParams();
  const status = searchParams.get("status") ?? "ALL";
  const channel = searchParams.get("channel");
  const productId = searchParams.get("productId");
  const q = searchParams.get("q") ?? "";
  const [draftQuery, setDraftQuery] = useState(q);
  useEffect(() => setDraftQuery(q), [q]);

  const setParam = useCallback(
    (key: string, value: string | null) => {
      setSearchParams(
        (prev) => {
          const next = new URLSearchParams(prev);
          if (value == null || value === "" || value === "ALL") next.delete(key);
          else next.set(key, value);
          return next;
        },
        { replace: true },
      );
    },
    [setSearchParams],
  );

  // The queue: read once, independent of the record's filters. Narrowing a search must not change
  // what the seller owes.
  const loadQueue = useCallback(async () => {
    try {
      // ONE read of the phases this product declared as 「waiting for the seller」
      // (`InquiryWorkItemPhase.AWAITING_SELLER`, now the queue endpoint's own default). This used to be
      // two reads whose pages were concatenated here, which put the definition of 「지금 처리할 일」 in
      // this file — and 홈, asking the same endpoint without naming phases, got half of it and printed
      // 11 while this heading printed 21. The concatenation also capped each phase at 100 separately,
      // so a large backlog would have been drawn as its own total.
      const page = await api.getInquiryQueueStrict({ page: 0, size: 100 });
      setQueue(page.content);
      setQueueTotal(page.totalElements);
    } catch {
      // A read that did not happen is not an empty queue; the section says so rather than showing none.
      setQueue(null);
      setQueueTotal(null);
    }
    try {
      setAnalyses(await api.getItemAnalysisStrict());
    } catch {
      setAnalyses([]);
    }
  }, []);

  const loadRecord = useCallback(async () => {
    setLoading(true);
    setFailed(false);
    try {
      // Every requested page, through the same predicate — the read echoes its own page, so the rows on
      // screen and the total beside them are always describing the same question.
      const pages = await Promise.all(
        Array.from({ length: recordPages }, (_, page) =>
          api.getInquiryRowsStrict({
            limit: PAGE_SIZE,
            page,
            order: "NEWEST",
            ...(status !== "ALL" ? { status } : {}),
            ...(channel ? { channel } : {}),
            ...(productId ? { productId } : {}),
            ...(q.trim() ? { q: q.trim() } : {}),
          }),
        ),
      );
      setRecord(pages.flatMap((page) => page.items));
      setRecordTotal(pages[pages.length - 1].totalCount);
    } catch {
      setRecord(null);
      setFailed(true);
    } finally {
      setLoading(false);
    }
  }, [status, channel, productId, q, recordPages]);

  // A new question starts at its first page. Without this, changing a filter would ask for pages 2..n
  // of a list that no longer exists.
  useEffect(() => setRecordPages(1), [status, channel, productId, q]);

  useEffect(() => {
    void loadQueue();
  }, [loadQueue]);
  useEffect(() => {
    void loadRecord();
  }, [loadRecord]);

  // A link naming a row the current page does not hold. One exact read through the same predicate.
  useEffect(() => {
    if (!itemRef) {
      setLinked(null);
      return;
    }
    const onPage = (record ?? []).some((row) => row.inquiryId === itemRef);
    if (onPage) {
      setLinked(null);
      return;
    }
    let active = true;
    void api
      .getInquiryRowsStrict({ inquiryId: itemRef, limit: 1 })
      .then((page) => active && setLinked(page.items[0] ?? null))
      .catch(() => active && setLinked(null));
    return () => {
      active = false;
    };
  }, [itemRef, record]);

  useEffect(() => {
    let live = true;
    const total = (forStatus?: string) =>
      api
        .getInquiryRowsStrict({
          limit: 1,
          ...(forStatus ? { status: forStatus } : {}),
          ...(channel ? { channel } : {}),
          ...(productId ? { productId } : {}),
          ...(q.trim() ? { q: q.trim() } : {}),
        })
        .then((page) => page.totalCount)
        .catch(() => null);
    void Promise.all([total(), total("UNANSWERED"), total("ANSWERED")]).then(([all, unanswered, answered]) => {
      if (live) setCounts({ all, unanswered, answered });
    });
    return () => {
      live = false;
    };
  }, [channel, productId, q]);

  const analysisIndex = useMemo(() => buildAnalysisIndex(analyses), [analyses]);
  const wide = useWideLayout();

  /** The rows a selection may resolve against: the record page, the queue, and the linked row. */
  const selected = useMemo(() => {
    if (!itemRef) return null;
    const fromRecord = (record ?? []).find((row) => row.inquiryId === itemRef);
    if (fromRecord) return asFeedItem(fromRecord);
    if (linked && linked.inquiryId === itemRef) return asFeedItem(linked);
    return null;
  }, [itemRef, record, linked]);

  const workItemId = useMemo(() => {
    if (!itemRef) return null;
    const fromQueue = (queue ?? []).find((row) => row.inquiryId === itemRef);
    if (fromQueue) return fromQueue.workItemId;
    const fromRecord = (record ?? []).find((row) => row.inquiryId === itemRef);
    return fromRecord?.workItemId ?? linked?.workItemId ?? null;
  }, [itemRef, queue, record, linked]);

  const focused = workItemId != null;
  useAgentSurface({
    surface: "inquiries",
    label: focused ? "이 문의" : "문의 목록",
    ...(focused ? { workItemId } : {}),
    ...(channel ? { channelCode: channel } : {}),
    ...(productId ? { productId } : {}),
  });

  /** The product the record is scoped to, named from a row rather than looked up separately. */
  const scopedProductName =
    productId != null
      ? (record ?? []).find((row) => row.productId === productId)?.productName ?? null
      : null;

  /**
   * The work, scoped the way the page is scoped.
   *
   * <b>Measured, not assumed.</b> Unscoped, the queue is the seller's whole workload and that is
   * exactly right. But a seller who pressed 「미답변 문의 1」 on a product arrives at a page whose first
   * section is 21 items about other products, with the one row they asked for 1,900px below it — the
   * doorway would have delivered them to the wrong thing correctly. When the page is about one
   * product, so is the work on it.
   *
   * A filter over rows already read, never a second query: the queue is bounded and every row carries
   * its `productId`.
   */
  const queueRows = useMemo(
    () => (productId ? (queue ?? []).filter((row) => row.productId === productId) : queue ?? []),
    [queue, productId],
  );
  // What the pane shows: the chosen inquiry, or — on a wide screen with nothing chosen — the first row of the work
  // the seller owes, which is the row this screen itself says to look at first.
  const defaultRow = wide && !itemRef ? (record ?? [])[0] ?? null : null;
  const shownRef = itemRef ?? defaultRow?.inquiryId;
  const shownItem = selected ?? (defaultRow ? asFeedItem(defaultRow) : null);
  const shownWorkItemId = itemRef
    ? workItemId
    : defaultRow
      ? (queue ?? []).find((row) => row.inquiryId === defaultRow.inquiryId)?.workItemId ?? defaultRow.workItemId
      : null;

  const channels = useMemo(() => {
    const seen = new Map<string, string>();
    for (const row of record ?? []) {
      if (row.channelCode) seen.set(row.channelCode, row.channelNameKo ?? productChannelLabel(row.channelCode));
    }
    return [...seen.entries()];
  }, [record]);

  const head = (
    <PageHead
      title="문의"
      compact={!!itemRef}
      action={
        <AgentLaunch
          context={{
            surface: "inquiries",
            ...(focused ? { workItemId } : {}),
            ...(channel ? { channelCode: channel } : {}),
          }}
          label={focused ? "이 문의에 대해 물어보기" : "문의에 대해 물어보기"}
        />
      }
      /* No meta: the section below is titled 「지금 처리할 일」 and carries the same count, and the two
         sat 80px apart saying the same words twice. The section owns it, because it owns the rows. */
    />
  );

  /**
   * <b>탭이 이미 고정한 사실은 행에서 반복하지 않는다.</b> 「답변 필요」 탭에서 모든 행이 「답변 필요」라고
   * 말하면 그 단어는 행을 구분하지 못하고 질문의 첫 글자 앞 자리만 차지한다. 전체 탭에서는 행마다 다르므로
   * 그대로 선다 — 리뷰의 목록이 자기 상태 단어를 그리는 것과 같은 규칙이다.
   */
  const stateVaries = status === "ALL";

  const lists = (
    <>
      {/* The work is 확인할 일's (UI/UX v2 Phase 3): this screen is where inquiries are looked up. It used to open
          with its own 「지금 처리할 일」 list — the same inquiries 확인할 일 already lists, in a second layout. */}
      {queue === null ? (
        <p className="text-sm text-warn" role="status">
          처리할 문의 수를 불러오지 못했습니다. 아래 전체 문의는 그대로 보실 수 있습니다.
        </p>
      ) : null}

      {productId ? (
        <div className="flex flex-wrap items-center gap-2" data-testid="record-product-scope">
          <Status tone="info">{scopedProductName ?? "이 상품"}의 문의만 보고 있습니다</Status>
          <button
            type="button"
            onClick={() => setParam("productId", null)}
            className="rounded-md text-sm font-medium text-muted underline underline-offset-2 hover:text-ink focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700"
          >
            전체 문의 보기
          </button>
        </div>
      ) : null}

      {/*
        <b>답변 상태는 설정이 아니라 목록의 축이다</b> (문의 canonical, 2026-10-03). 전에는 「모든 채널」
        옆의 select 였고, 그래서 판매자는 지금 몇 건이 답변을 기다리는지 열어 보기 전에는 알 수 없었다.
        탭은 세 숫자를 동시에 보여 주고, 누르는 것이 곧 그 숫자를 여는 것이다 — 확인할 일·리뷰·반복 문제가
        쓰는 바로 그 {@link FilterTab}이다.
      */}
      <div className="flex flex-wrap items-center gap-x-6 gap-y-2 border-b border-line">
        {RECORD_STATUS_OPTIONS.map((option) => (
          <FilterTab key={option.value} pressed={status === option.value} onClick={() => setParam("status", option.value)}>
            {option.label}
            {COUNT_OF[option.value](counts) != null ? (
              <span className="ml-1.5 tabular-nums">{COUNT_OF[option.value](counts)!.toLocaleString("ko-KR")}</span>
            ) : null}
          </FilterTab>
        ))}
        <span className="ml-auto">
          <QuietSelect
            label="채널"
            value={channel ?? ""}
            options={[{ value: "", label: "모든 채널" }, ...channels.map(([code, name]) => ({ value: code, label: name }))]}
            onChange={(value) => setParam("channel", value || null)}
          />
        </span>
      </div>

      {/* 두 번째 줄: 확인할 일로 가는 문(별개의 work-item 진입점), 그리고 이 화면의 본업인 찾기. */}
      <div className="flex flex-wrap items-center gap-x-6 gap-y-3">
        {queueRows.length > 0 ? (
          <Link to="/customer-operations/cases" className="shrink-0 text-sm font-semibold text-brand-700 hover:underline">
            확인할 일에 문의 {(productId ? queueRows.length : queueTotal ?? queueRows.length).toLocaleString("ko-KR")}건 →
          </Link>
        ) : null}
        <form
          className="min-w-[16rem] flex-1"
          onSubmit={(e) => {
            e.preventDefault();
            setParam("q", draftQuery.trim());
          }}
        >
          <label className="block">
            <span className="sr-only">문의 내용 검색</span>
            <input
              type="search"
              value={draftQuery}
              onChange={(e) => setDraftQuery(e.target.value)}
              placeholder="고객이 쓴 말로 찾기 (예: 세금계산서)"
              className="w-full border-0 border-b border-line bg-transparent px-1 py-2 text-base text-ink placeholder:text-muted focus:border-brand-700 focus:outline-none focus:ring-0"
            />
          </label>
        </form>
      </div>

      {loading ? (
        <p className="px-1 py-6 text-sm text-muted">불러오는 중…</p>
      ) : failed ? (
        <Empty
          title="문의를 불러오지 못했습니다"
          body="연결 상태를 확인한 뒤 다시 시도해 주세요."
          action={<BtnLink to="/connect">채널 연결 확인</BtnLink>}
        />
      ) : (record ?? []).length === 0 ? (
        <Empty
          title={hasNarrowing(q, channel, status, productId) ? "찾는 문의가 없습니다" : "아직 들어온 문의가 없습니다"}
          body={
            hasNarrowing(q, channel, status, productId)
              ? "다른 말로 찾거나 조건을 넓혀 보세요."
              : "채널을 연결하거나 정기 자료 가져오기로 자료를 넘겨주시면, 채널이 달라도 같은 형태로 모아 보여드립니다."
          }
          action={hasNarrowing(q, channel, status, productId) ? undefined : <BtnLink to="/connect">채널 연결하기</BtnLink>}
        />
      ) : (
        <>
          {/*
            <b>고객이 쓴 문장이 행의 첫 시선이다</b> (문의 canonical, 2026-10-03, product-owner decision).

            <p>행은 「상태 → 제목 → 본문 → 채널·상품」이었다. 그 순서는 제목이 목록 어디에도 안 나오던
            결함을 고치려고 정한 것인데, 실제 데이터에서 대가가 드러났다 — 데모 org 최신 11건 중 5건은
            제목이 비었거나 「문의 드립니다」다. 게시판이 자동으로 채운 칸이 가장 큰 글씨를 가져가고,
            고객이 실제로 물어본 문장은 그 아래 회색이었다. 이제 첫 줄은 언제나 고객의 말이고, 제목은
            본문이 말하지 않는 것을 말할 때만 그 아래 사실 줄에 남는다({@link inquiryReading}).

            <p>모양은 리뷰의 행 그대로다. 문의와 리뷰는 같은 종류의 기록이고, 두 목록이 서로 다른 방식으로
            읽히면 판매자는 제품을 두 개 쓰는 셈이 된다. 목록을 감싸던 카드도 함께 사라졌다 — 행 사이에
            선이 있으면 그것이 목록이고, 테두리 안의 테두리는 두 번째 경계였다.
          */}
          <section aria-label="문의 목록" className="-mx-4">
            <ul className="divide-y divide-line">
              {(record ?? []).map((row) => {
                const state = recordRowState(row);
                const reading = inquiryReading(row, previewText);
                const selected = row.inquiryId === shownRef;
                const rest: ReactNode[] = [];
                if (reading.titleContext) rest.push(`제목 「${reading.titleContext}」`);
                if (row.productName) rest.push(row.productName);
                return (
                  <li key={row.inquiryId}>
                    <Link
                      to={`/inquiries/${row.inquiryId}`}
                      aria-current={selected ? "true" : undefined}
                      className={`block px-4 py-4 transition focus:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-brand-700 ${
                        selected ? "bg-canvas" : "hover:bg-canvas"
                      }`}
                    >
                      <span className="flex items-start gap-6">
                        <span className="min-w-0 flex-1">
                          {/*
                            <b>316px rail의 행</b> (문의 redesign, 2026-10-04). 전에는 18px에 `max-w-[62ch]`
                            였다 — 목록이 페이지이던 시절의 치수다. rail에서는 62ch가 닿지 않는 폭이고, 긴
                            질문 하나가 다섯 줄을 먹어 한 화면에 다섯 행만 섰다. 확인할 일의 rail 행이 쓰는
                            치수(16px bold)로 내리고 두 줄에서 끊는다 — 행이 하는 일은 어느 문의인지 알아보게
                            하는 것이고, 전문은 바로 옆 pane의 제목이다.
                          */}
                          <span
                            className={`line-clamp-2 break-keep text-base font-semibold leading-snug [overflow-wrap:anywhere] ${
                              row.status === "ANSWERED" ? "text-muted" : "text-ink"
                            }`}
                          >
                            {reading.question}
                          </span>
                          {/* One line, truncated — never wrapped. 가장 많이 구별해 주는 사실이 마지막이라,
                              잘려야 하는 것은 상품 이름이다. */}
                          <span className="mt-1.5 flex min-w-0 flex-nowrap items-center gap-x-2 overflow-hidden text-xs text-muted">
                            {stateVaries ? (
                              <span className={`shrink-0 font-semibold ${row.status === "ANSWERED" ? "text-muted" : "text-brand-700"}`}>
                                {state.text}
                              </span>
                            ) : null}
                            {stateVaries ? (
                              <Fact fixed>{row.channelNameKo ?? "채널 미상"}</Fact>
                            ) : (
                              <span className="shrink-0">{row.channelNameKo ?? "채널 미상"}</span>
                            )}
                            {rest.map((fact, index) => (
                              <Fact key={index} fixed={index < rest.length - 1}>
                                {fact}
                              </Fact>
                            ))}
                          </span>
                        </span>
                        {/* <b>이 문의가 언제 것인가 — 기다린 날수로, 1년이 넘으면 접수일로</b> (문의
                            redesign, 2026-10-04). 행이 날짜만 들고 있던 동안 11일 기다린 문의와 어제 들어온
                            문의는 같은 모양이었다. 단어의 주인은 {@link elapsedLabel}이라 pane이 옆에서
                            말하는 것과 같은 수가 나온다. */}
                        <span className="shrink-0 pt-1 text-sm tabular-nums text-muted">
                          {elapsedLabel(row.receivedAt, "INQUIRY") ?? kstDate(row.receivedAt)}
                        </span>
                      </span>
                    </Link>
                  </li>
                );
              })}
            </ul>
          </section>
          {/* Said only when there IS more — a page that holds everything says nothing. */}
          {recordTotal != null && recordTotal > (record ?? []).length ? (
            <div className="flex flex-wrap items-center gap-3 pt-3">
              <Btn variant="outline" size="sm" disabled={loading} onClick={() => setRecordPages((n) => n + 1)}>
                {loading ? "불러오는 중…" : "더 보기"}
              </Btn>
              <span className="text-sm tabular-nums text-muted">
                {(record ?? []).length} / {recordTotal}건
              </span>
            </div>
          ) : null}
        </>
      )}
    </>
  );

  /*
    <b>작업대는 화면이 쥔다</b> (문의 canonical, 2026-10-03). 초안·근거·등록 가능 여부는 전부 한 상태이고,
    그것을 읽는 곳이 둘이다: pane 본문과 pane 바닥의 dock. 패널 안에 상태가 있던 동안에는 바닥에 둘 수 있는
    것이 없었다. work item이 없는 문의 — 이미 답변됐거나 목록 밖 — 에서는 {@code null}을 받고 아무것도
    읽지 않는다. hook은 조건부로 부를 수 없으므로 「없음」이 값이다.
  */
  const workspace = useInquiryReply(shownWorkItemId);

  const detail = shownItem ? (
    <InquiryCasePane
      item={shownItem}
      analysis={analysisIndex.get(analysisKey("INQUIRY", shownItem.id))}
      workItemId={shownWorkItemId}
      workspace={workspace}
    />
  ) : itemRef && !loading ? (
    <div>
      <p className="break-keep font-semibold text-ink">문의를 찾을 수 없습니다</p>
      <p className="mt-2 break-keep text-sm leading-relaxed text-muted">
        목록에서 다시 선택해 주세요. 자료가 다시 정리되면서 항목이 바뀌었을 수 있습니다.
      </p>
    </div>
  ) : itemRef ? (
    <p className="text-sm text-muted">불러오는 중…</p>
  ) : null;

  /*
    Master-detail (UI/UX v2 Phase 2). The list and the chosen inquiry each own their scroll, so reading a long
    answer never drags the list — the page used to be one 7,071px scroll whose height was the list's. On a wide
    screen the first 지금 처리할 일 row is open when nothing is chosen; on a narrow one the chosen inquiry replaces
    the list, with the way back above it, exactly as before.
  */
  const list =
    !wide && itemRef ? (
      <>
        {head}
        <Link to="/inquiries" className="text-sm font-semibold text-muted hover:text-ink hover:underline">
          ← 문의 목록
        </Link>
        {detail}
      </>
    ) : (
      <>
        {head}
        {lists}
      </>
    );

  return (
    <MasterDetail
      wide={wide}
      /*
        <b>목록은 rail이고, 읽고 있는 문의가 페이지다</b> (문의 redesign, 2026-10-04 — product-owner
        decision, 1600 mockup을 구현 target으로 승인).

        <p>전에는 선언된 decision pane이었다 — 목록이 페이지이고 선택한 문의가 576px로 옆에 섰다.
        그런데 이 화면에서 판매자가 하는 일은 목록을 훑는 것이 아니라 <b>한 문의를 읽고 답할지 정하는
        것</b>이고, 그 읽기는 고객의 질문 전문 · 준비된 답변 · 근거 · 기록으로 이어진다. 576px은 그것을
        담는 폭이 아니다. 확인할 일이 같은 이유로 rail을 쓰므로 같은 geometry를 쓴다 — 240 rail / 316
        목록 / 나머지 전부.

        <p>선언된 pane 폭은 더 이상 주지 않는다. rail은 열려 있을 때만 rail이고, 닫혀 있으면 목록이 폭을
        전부 가져가므로 그 폭이 쓰이는 경로가 없다. 쓰이지 않는 선언은 사실이 아니다.
      */
      layout="rail"
      list={list}
      detailLabel="문의 상세"
      detail={detail}
      preview
      paneFooter={shownItem && shownWorkItemId ? <InquiryReplyDock workspace={workspace} /> : null}
    />
  );
}

/**
 * One inquiry in the pane, in {@link CaseLayout}'s reading order: where and when, then the customer's words and the
 * answer (the response panel owns both, so the layout draws no second copy of the question), then the automatic
 * classification, folded.
 *
 * <b>한 사실을 한 번만 그린다.</b> work item이 없는 문의 — 실제 live NAVER 문의가 그렇다 — 에서 같은
 * 문장이 한 화면에 <b>세 번</b> 나왔다: 이 layout의 제목, 그 아래 {@link InboxDetail}의 제목, 그 아래
 * 「문의 발췌」의 본문. 셋 다 `previewText(item.snippet)`이거나 그 본문 자체였다. 원인은 둘이다 —
 * `asFeedItem`이 제목과 본문을 한 칸으로 합쳤고(지금은 합치지 않는다), header를 그릴 사람이 둘이었다.
 *
 * <p>이제 <b>layout이 header의 유일한 주인</b>이다: meta(채널·상품·시각)와 제목은 여기서만 그리고,
 * {@code InboxDetail}은 header를 아예 그리지 않는다. 답변 패널이 뜰 때는 패널이 제목과 본문을 자기
 * 첫 블록으로 인쇄하므로 제목만 {@code titleHidden}으로 감춘다 — meta는 패널이 그리지 않으니 남는다.
 */
function InquiryCasePane({
  item,
  analysis,
  workItemId,
  workspace,
}: {
  item: ReturnType<typeof asFeedItem>;
  analysis: ItemAnalysis | undefined;
  workItemId: string | null;
  workspace: ReturnType<typeof useInquiryReply>;
}) {
  /*
    <b>pane의 제목은 고객이 쓴 질문 전체다</b> (문의 canonical, 2026-10-03). 목록의 행은 발췌를 보여 주지만
    여기서는 자르지 않는다 — 판매자가 답을 쓰기 전에 읽어야 하는 것이 그 문장 전부이기 때문이고, 리뷰의
    pane이 고객의 리뷰 전문을 제목으로 세우는 것과 같은 이유다. 그래서 {@code previewText}가 아니라
    {@code plainText}로 읽고, 답변 블록은 같은 문장을 다시 그리지 않는다({@code docked}).

    <p>상세 읽기가 도착하기 전에는 행이 가진 것으로 그린다. 둘은 같은 문의의 같은 두 칸이고, 늦게 오는 쪽이
    더 길 뿐이다.
  */
  const detail = workspace.detail;
  const reading = inquiryReading(
    { title: detail?.title ?? item.title, snippet: detail?.details ?? item.snippet },
    plainText,
  );
  /*
    목록의 행과 같은 주인, 같은 단어 — 그것이 elapsed 계약이다. 1년이 넘은 문의에서 그 단어는 접수일 자체가
    되므로, 바로 왼쪽의 날짜와 같은 사실을 두 번 말하게 된다. 그때는 날짜만 남긴다.
  */
  const waited = waitIsDated(item.receivedAt) ? null : elapsedLabel(item.receivedAt, "INQUIRY");
  const state = recordRowState(item);
  return (
    <CaseLayout
      key={item.id}
      variant="pane"
      label="선택한 문의"
      decisionLabel="답변"
      meta={
        <Facts>
          <span className={`font-semibold ${item.status === "ANSWERED" ? "text-muted" : "text-brand-700"}`}>
            {state.text}
          </span>
          <span>{item.channelNameKo}</span>
          <span className="tabular-nums">{kstDate(item.receivedAt)}</span>
          {waited ? <span>{waited}</span> : null}
        </Facts>
      }
      title={reading.question}
      sub={[item.productName, reading.titleContext ? `제목 「${reading.titleContext}」` : null]
        .filter(Boolean)
        .join(" · ")}
      // No 「전체 화면으로」: this route IS the inquiry's own screen, so the link would point at the page it is on.
      decision={
        <InboxDetail
          item={item}
          analysis={analysis}
          workItemId={workItemId}
          workspace={workspace}
          docked
        />
      }
    />
  );
}


/** 어떤 탭이 어떤 숫자를 말하는가. 탭의 값과 숫자를 한 곳에서 묶어 둔다. */
const COUNT_OF: Record<string, (c: { all: number | null; unanswered: number | null; answered: number | null }) => number | null> = {
  ALL: (c) => c.all,
  UNANSWERED: (c) => c.unanswered,
  ANSWERED: (c) => c.answered,
};

/** One page of the record. The seller narrows rather than scrolls; the count says what is behind it. */
const PAGE_SIZE = 50;

/** Whether the seller asked for something, so an empty result is 「찾는 게 없다」 and not 「아무것도 없다」. */
function hasNarrowing(q: string, channel: string | null, status: string, productId: string | null): boolean {
  return q.trim() !== "" || channel != null || status !== "ALL" || productId != null;
}

