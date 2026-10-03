import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { Link, useLocation, useSearchParams } from "react-router-dom";
import { Facts } from "../../components/ui/ObjectRow";
import { Empty } from "../../components/ui/Empty";
import { Btn, BtnLink } from "../../components/ui/Btn";
import { Fact } from "../../components/ui/DecisionRow";
import { AiMarkChip, TriageTierChip } from "../../components/reviews/TriageTierChip";
import { ReviewReadDetail } from "../../components/reviews/ReviewReadDetail";
import { formatDateTime, josa, parseTierParam } from "../../components/reviews/recordParts";
import { QuietSelect } from "../../components/ui/QuietSelect";
import { FilterTab } from "../../components/ui/FilterTab";
import { MasterDetail, useWideLayout } from "../../components/workspace/MasterDetail";
import { CaseLayout } from "../../components/workspace/CaseLayout";
import { OperationsCaseView } from "./OperationsCase";
import { api } from "../../lib/apiClient";
import { ratingLabel } from "../../lib/reviewRecord";
import { plainText, previewText } from "../../lib/plainText";
import { channelShort, COPY, sourceLabel } from "../../lib/copy/customerOps";
import { TRIAGE_CORRECTION_COPY, TRIAGE_TIERS, TRIAGE_TIER_LABEL } from "../../lib/reviewTriage";
import { useReviewLocate } from "../../lib/actionWindow/locate/useReviewLocate";
import type { ReviewAccount } from "../../lib/reviewAccounts";
import type {
  ChannelReviewDetailView,
  ReviewDecisionContext,
  ReviewDecisionLogEntry,
  ReviewRecordChannelFacts,
  ReviewRecordPageView,
  ReviewTriageTier,
  TriageBehaviorEvent,
} from "../../lib/types";

const PAGE_SIZE = 20;
const WORD = "리뷰";

type Sort = "attention" | "newest" | "lowest";

/** The three orders, in the words the control has always used. */
const SORTS: readonly { value: Sort; label: string }[] = [
  { value: "attention", label: "확인 필요순" },
  { value: "newest", label: "최신순" },
  { value: "lowest", label: "낮은 평점순" },
];

/**
 * The channel filter's unfiltered value. A `select` needs a value for 「전체」 and the URL has none — the
 * parameter is simply absent — so the two are translated at the control rather than letting an empty
 * string become a channel code anywhere else.
 */
const ALL_CHANNELS = "ALL";

/**
 * <b>리뷰 — the organisation's record</b> (UI/UX v2 Phases 2–3).
 *
 * <p><b>A place to look things up, not a place to work.</b> The work — every review that waits on the seller, and
 * the seller's own reply work before approval — is 확인할 일's; what they approved and have not posted is 실행
 * 대기's. The 「내 답변 작업」 area this screen used to open with is therefore gone: its rows are 확인할 일's rows, its
 * 「작업에서 제외」 stands in the Review Case, and its history sits under 확인할 일. This screen links there once.
 *
 * <p><b>One record screen.</b> The per-account record (`/reviews/:accountId`) redirects here with its channel as the
 * filter; its read detail — the facts, the seller's standing answer, `[쿠팡에서 보기]`, the AI pilot's silver — is
 * this screen's right-hand pane now ({@link ReviewReadDetail}). A review that 고객 운영 관리 opened a case about is
 * shown as that case, in the same grammar, because the case is where its judgement already is.
 *
 * <p>One read orders, filters, pages and counts (`GET /api/reviews/record`); nothing is merged here.
 */
export function ReviewRecord({ targets, head }: { targets: ReviewAccount[]; head?: ReactNode }) {
  const [searchParams, setSearchParams] = useSearchParams();
  const { search } = useLocation();
  const rawTier = searchParams.get("tier");
  const tier = parseTierParam(rawTier);
  const channel = normalizeChannel(searchParams.get("channel"));
  const selectedId = searchParams.get("review");
  const wide = useWideLayout();
  const [sort, setSort] = useState<Sort>("attention");
  const [pageIndex, setPageIndex] = useState(0);
  const [page, setPage] = useState<ReviewRecordPageView | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState(false);
  const [workCount, setWorkCount] = useState<number | null>(null);
  const [cases, setCases] = useState<Map<string, string>>(new Map());
  const seq = useRef(0);

  const setParams = useCallback(
    (changes: Record<string, string | null>) => {
      setSearchParams(
        (prev) => {
          const params = new URLSearchParams(prev);
          for (const [key, value] of Object.entries(changes)) {
            if (value) params.set(key, value);
            else params.delete(key);
          }
          return params;
        },
        { replace: true },
      );
    },
    [setSearchParams],
  );

  // An unknown tier is scrubbed rather than sent — the server would refuse it, and the screen would say nothing.
  useEffect(() => {
    if (rawTier !== null && tier === null) setParams({ tier: null });
  }, [rawTier, tier, setParams]);

  const recordBehavior = useCallback((accountId: string | null, events: TriageBehaviorEvent[]) => {
    if (!accountId || events.length === 0) return;
    try {
      void Promise.resolve(api.recordChannelReviewTriageBehavior(accountId, events)).catch(() => undefined);
    } catch {
      // Silver: never fails the screen.
    }
  }, []);

  const load = useCallback(
    async (nextSort: Sort, nextTier: ReviewTriageTier | null, nextChannel: string | null, nextPage: number) => {
      const ticket = ++seq.current;
      setLoading(true);
      try {
        const view = await api.getReviewRecordStrict({
          channel: nextChannel ?? undefined,
          sort: nextSort,
          tier: nextTier ?? undefined,
          page: nextPage,
          size: PAGE_SIZE,
        });
        if (ticket !== seq.current) return;
        setPage(view);
        setLoadError(false);
        // The pilot's exposure silver, per account, for rows the pilot raised — as the channel record did.
        if (view.aiPilotEnabled) {
          for (const facts of view.channelFacts ?? []) {
            if (!facts.capability?.aiTriage) continue;
            recordBehavior(
              facts.accountId,
              view.items
                .filter((row) => row.channelCode === facts.channelCode && row.review.aiMark !== null)
                .map((row) => ({ reviewId: row.review.id, kind: "AI_ATTENTION_SHOWN" as const })),
            );
          }
        }
      } catch {
        if (ticket !== seq.current) return;
        setPage(null);
        setLoadError(true);
      } finally {
        if (ticket === seq.current) setLoading(false);
      }
    },
    [recordBehavior],
  );

  useEffect(() => {
    void load(sort, tier, channel, pageIndex);
  }, [sort, tier, channel, pageIndex, load]);

  // How much review work 확인할 일 holds — the one link to the work from a record screen. And which reviews have a
  // case, so the pane can show the case rather than a second reading of the same review. Both fail silent.
  useEffect(() => {
    let live = true;
    Promise.resolve()
      .then(() => api.getReviewWorkStrict())
      .then((work) => {
        if (!live) return;
        const committed = work.committed.reduce((n, account) => n + account.todo.length, 0);
        setWorkCount(work.attentionTotal + committed);
      })
      .catch(() => live && setWorkCount(null));
    Promise.resolve()
      .then(() => api.getCustomerOperationsDecisions())
      .then((d) => {
        if (!live) return;
        // Keyed by the owner address — the identity 확인할 일 dedupes by — so no URL is parsed back into an id.
        const map = new Map<string, string>();
        for (const row of d.rows) if (row.subjectKind === "REVIEW") map.set(row.to, row.caseId);
        setCases(map);
      })
      .catch(() => undefined);
    return () => {
      live = false;
    };
  }, []);

  const channelChoices = uniqueChannels(targets);
  const totalPages = page === null || page.size <= 0 ? 1 : Math.max(1, Math.ceil(page.total / page.size));
  const recordTotal = page ? page.triageSummary.needsAttention + page.triageSummary.watch + page.triageSummary.fyi : 0;
  const selectedRow = page?.items.find((row) => row.review.id === selectedId) ?? null;
  const shownId = selectedId ?? (wide ? page?.items[0]?.review.id ?? null : null);
  const shownRow = page?.items.find((row) => row.review.id === shownId) ?? selectedRow;
  const hrefFor = (reviewId: string) => {
    const params = new URLSearchParams(search);
    params.set("review", reviewId);
    return `?${params.toString()}`;
  };

  const detail = shownId ? (
    cases.has(ownerOf(shownId)) ? (
      <OperationsCaseView key={shownId} caseId={cases.get(ownerOf(shownId))!} variant="pane" />
    ) : (
      <ReviewReadPane
        key={shownId}
        reviewId={shownId}
        docked={wide}
        facts={page?.channelFacts?.find((f) => f.channelCode === shownRow?.channelCode) ?? null}
        aiPilotEnabled={page?.aiPilotEnabled ?? false}
        recordBehavior={recordBehavior}
      />
    )
  ) : null;

  const record = (
    <>
      {head}

      {/*
        <b>분류는 이 화면의 축이고, 채널과 정렬은 그 축 위의 설정이다</b> (리뷰 canonical mockup, 2026-10-03).

        <p>Three segmented groups stood here — 채널, 분류, 정렬 — and at 1600×1000 they were ~120px of grey
        slabs between the heading and the first customer sentence. The tier is what this screen is ordered
        and counted by, so it reads as the list's own tabs, in the order the workflow has always used
        (확인 필요 · 지켜보기 · 참고, then 전체). The other two are not axes and no longer look like one.
      */}
      <div className="flex flex-wrap items-end justify-between gap-x-6 gap-y-2 border-b border-line">
        <div className="flex flex-wrap items-center gap-6" role="group" aria-label="분류 필터">
          {TRIAGE_TIERS.map((value) => (
            <FilterTab
              key={value}
              pressed={tier === value}
              onClick={() => {
                setParams({ tier: value, review: null });
                setPageIndex(0);
              }}
            >
              {TRIAGE_TIER_LABEL[value]} {page ? tierCount(page, value) : 0}
            </FilterTab>
          ))}
          <FilterTab
            pressed={tier === null}
            onClick={() => {
              setParams({ tier: null, review: null });
              setPageIndex(0);
            }}
          >
            전체 {recordTotal}
          </FilterTab>
        </div>
        <div className="flex flex-wrap items-center gap-4 pb-2">
          {workCount && workCount > 0 ? (
            <Link to="/customer-operations/cases" className="text-sm font-semibold text-brand-700 hover:underline">
              {COPY.listTitle}에 리뷰 {workCount.toLocaleString("ko-KR")}건 →
            </Link>
          ) : null}
          {/* 채널은 필터. 「전체」 is the seller-visible channels — and when the organisation holds reviews
              elsewhere (a file-uploaded G마켓 export), the line under the list says so rather than letting
              「전체」 claim them. */}
          <QuietSelect
            label="채널"
            value={channel ?? ALL_CHANNELS}
            options={[
              { value: ALL_CHANNELS, label: "전체 채널" },
              ...channelChoices.map((code) => ({ value: code, label: channelShort(code) ?? code })),
            ]}
            onChange={(value) => {
              setParams({ channel: value === ALL_CHANNELS ? null : value, review: null });
              setPageIndex(0);
            }}
          />
          <QuietSelect
            label="정렬"
            value={sort}
            options={SORTS}
            onChange={(value) => {
              setSort(value as Sort);
              setPageIndex(0);
              setParams({ review: null });
            }}
          />
        </div>
      </div>

      {loadError ? (
        <Empty
          title="리뷰를 불러오지 못했습니다"
          body="연결 상태를 확인한 뒤 다시 시도해 주세요. 불러오지 못한 목록을 임의로 채우지는 않습니다."
          action={
            <Btn size="sm" onClick={() => void load(sort, tier, channel, pageIndex)}>
              다시 시도
            </Btn>
          }
        />
      ) : loading && !page ? (
        <p className="text-muted">불러오는 중…</p>
      ) : page && page.items.length === 0 && tier !== null ? (
        // An empty FILTER is not an empty record.
        <Empty
          title={`${TRIAGE_TIER_LABEL[tier]}에 해당하는 ${josa(WORD, "이", "가")} 없습니다`}
          body={`다른 분류를 눌러 보시거나 전체를 보세요. 수집된 ${josa(WORD, "은", "는")} 그대로 있습니다.`}
          action={
            <Btn size="sm" onClick={() => setParams({ tier: null })}>
              전체 보기
            </Btn>
          }
        />
      ) : page && page.items.length === 0 ? (
        <Empty
          title={`아직 수집된 ${josa(WORD, "이", "가")} 없습니다`}
          body="채널 연결에서 리뷰 수집을 한 번 실행하면 이 목록에 쌓입니다."
          action={
            <BtnLink to="/connect" size="sm">
              채널 연결로
            </BtnLink>
          }
        />
      ) : page ? (
        <>
          {/*
            <b>고객이 쓴 문장이 행의 첫 시선이다</b> (리뷰 canonical mockup, 2026-10-03).

            <p>The row was 「tier chip · ★ · 채널 · 날짜」 over the sentence over the product: three ranks with
            the system's own vocabulary on top. It is now the sentence, then one line that qualifies it —
            the same facts, re-columned, in the reading 확인할 일's queue already uses, down to the hairline
            between facts ({@link Fact}). The card around the list is gone with it: a white canvas with a
            rule between rows is the list, and a border around the border was the second one.
          */}
          <section aria-label="목록" className="-mx-4">
            <ul className="divide-y divide-line">
              {page.items.map(({ channelCode, channelNameKo, review }) => {
                const selected = review.id === shownId;
                return (
                  <li key={review.id}>
                    <Link
                      to={hrefFor(review.id)}
                      aria-current={selected ? "true" : undefined}
                      className={`block px-4 py-4 transition focus:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-brand-700 ${
                        selected ? "bg-brand-50 shadow-selected" : "hover:bg-canvas"
                      }`}
                    >
                      <span className="flex items-start gap-6">
                        <span className="min-w-0 flex-1">
                          <span
                            className={`block max-w-[62ch] break-keep text-lg font-semibold leading-snug [overflow-wrap:anywhere] ${
                              review.textless ? "font-normal text-muted" : "text-ink"
                            }`}
                          >
                            {review.textless ? "별점만 남긴 리뷰" : previewText(review.preview) || "표시할 수 있는 본문이 없습니다"}
                          </span>
                          {/* One line, truncated — never wrapped. The facts are ordered most-identifying-last,
                              so the product is the one that gives way. */}
                          <span className="mt-1.5 flex min-w-0 flex-nowrap items-center gap-x-2 overflow-hidden text-xs text-muted">
                            <TriageTierChip tier={review.triage.tier} quiet />
                            {review.aiMark ? <AiMarkChip quiet /> : null}
                            {review.sellerCorrection ? (
                              <Fact fixed>{`${TRIAGE_CORRECTION_COPY.sellerPrefix} ${TRIAGE_TIER_LABEL[review.sellerCorrection.correctedTier]}`}</Fact>
                            ) : null}
                            <Fact fixed>{ratingLabel(review.rating)}</Fact>
                            <Fact fixed>{channelShort(channelCode) ?? channelNameKo ?? "채널 미상"}</Fact>
                            {cases.has(ownerOf(review.id)) ? (
                              <Fact fixed>
                                <span className="font-semibold text-brand-700">{COPY.listTitle}</span>
                              </Fact>
                            ) : null}
                            {review.isNew ? <Fact fixed>새 리뷰</Fact> : null}
                            {review.mediaCount > 0 ? <Fact fixed>{`사진·영상 ${review.mediaCount}`}</Fact> : null}
                            <Fact>{review.productName ?? review.productId ?? "상품 정보 없음"}</Fact>
                          </span>
                        </span>
                        <span className="shrink-0 pt-1 text-sm tabular-nums text-muted">
                          {review.writtenOn ?? "날짜 없음"}
                        </span>
                      </span>
                    </Link>
                  </li>
                );
              })}
            </ul>
          </section>

          {/*
            <b>기록의 총계와 수집의 유보는 목록 뒤에 선다</b> (product-owner decision, 2026-10-03).

            <p>They stood above the list — the record's totals, the channel scope, and a paragraph per channel
            whose last import stopped early. Not one of them is the thing a seller came to read, and together
            they were the first 150px of the screen. Nothing is deleted and nothing is folded: the same
            sentences, in the same words, under the rows they qualify.
          */}
          <div className="space-y-2 border-t border-line pt-4 text-xs text-muted">
            <div className="flex flex-wrap items-center justify-between gap-x-6 gap-y-2">
              <Facts>
                <span className="tabular-nums">{rangeLabel(page)}</span>
                {/* This 「총 N개」 is the WHOLE record and stays. It is not the number on the pressed tab
                    above — filter to 확인 필요 and this still says 22 while the tab says 1 — and a seller
                    must not be told their record holds what their current filter holds. */}
                <span className="tabular-nums">{`총 ${recordTotal}개`}</span>
                {page.newCount > 0 ? (
                  <span className="font-semibold tabular-nums text-brand-700">{`새로 들어온 ${page.newCount}개`}</span>
                ) : null}
              </Facts>
              {totalPages > 1 ? (
                <nav className="flex items-center gap-3" aria-label="리뷰 목록 페이지">
                  <Btn
                    size="sm"
                    variant="outline"
                    disabled={pageIndex <= 0 || loading}
                    onClick={() => {
                      setPageIndex((n) => Math.max(0, n - 1));
                      setParams({ review: null });
                    }}
                  >
                    이전
                  </Btn>
                  <span className="tabular-nums">
                    {page.page + 1} / {totalPages} 페이지
                  </span>
                  <Btn
                    size="sm"
                    variant="outline"
                    disabled={pageIndex >= totalPages - 1 || loading}
                    onClick={() => {
                      setPageIndex((n) => n + 1);
                      setParams({ review: null });
                    }}
                  >
                    다음
                  </Btn>
                </nav>
              ) : null}
            </div>
            {(page.outsideVisibleChannels ?? 0) > 0 && channel === null ? (
              <p className="break-keep leading-relaxed">
                연결 채널(네이버·쿠팡·카페24) 기준 · 파일로 올린 다른 채널 리뷰 {page.outsideVisibleChannels}건은 상품 화면에서 봅니다
              </p>
            ) : null}
            {/* What only a channel can say: an import that stopped before the end of its list. */}
            {(page.channelFacts ?? [])
              .filter((f) => f.lastImportAt && !f.lastImportComplete)
              .map((f) => (
                <p key={f.channelCode} className="break-keep leading-relaxed">
                  {channelShort(f.channelCode) ?? f.channelNameKo}: 마지막 수집({formatDateTime(f.lastImportAt!)})이 목록 끝까지
                  확인되지 않은 상태로 끝났습니다. 채널에 이보다 더 있을 수 있습니다.
                </p>
              ))}
          </div>
        </>
      ) : null}
    </>
  );

  const list =
    !wide && selectedId ? (
      <>
        {head}
        <button
          type="button"
          onClick={() => setParams({ review: null })}
          className="text-left text-sm font-semibold text-muted hover:text-ink hover:underline"
        >
          ← 리뷰 목록
        </button>
        {detail}
      </>
    ) : (
      record
    );

  /*
    <b>리뷰의 pane도 Decision Workspace의 pane이다</b> (리뷰 canonical redesign, 2026-10-03). The width was a
    contract about what the column HOLDS, not about which screen it is on: 고객의 말 → 왜 올라왔나요 → 반복
    신호 → 이 상품에 대해 아는 것 → 판단과 조치 is the same reading 확인할 일 took it for. 440 and 576 are
    unchanged, and so is the 1440 breakpoint below which this screen falls back to the layout's own pane.
  */
  return (
    <MasterDetail
      wide={wide}
      pane="decision"
      list={list}
      detailLabel="리뷰 상세"
      detail={detail}
      /*
        <b>문은 바닥에 고정된다</b> (product-owner decision, 2026-10-03). Measured at 1366×768 with the pane
        at its contracted 440: 판단과 조치 begins at y=976 and 이 리뷰 처리하기 ended at y=1,105 — 337px below
        the fold, on a screen whose whole point is to decide. Nothing above it is cut or folded to fix that;
        the action leaves the flow and takes the pane's floor, which is what `sticky bottom-0` does — under a
        short pane it sits right after the last line, under a tall one it rides the edge while the case scrolls.

        <p>A review 고객 운영 관리 opened a case about is shown AS that case, and that reading carries its own
        action — docking a second one under it would put two primary verbs on one column.
      */
      preview
      paneFooter={
        shownId && !cases.has(ownerOf(shownId)) ? (
          /* A hairline, because this bar has a tall document passing under it. The shared footer draws none
             — that was decided for the Home's preview, which is usually shorter than its column and would
             get a rule with nothing beneath it. Here the pane always overflows, and without the edge the
             button reads as the last line of 이 상품에 대해 우리가 아는 것 rather than as the pane's floor. */
          /* `-mb-6 pb-6` carries the bar's own surface down over the dock's trailing padding: without it the
             document scrolls through that 24px and a half-line of 이 상품에 대해 아는 것 shows under the button. */
          <div className="-mb-6 border-t border-line bg-surface pb-6 pt-4">
            <BtnLink to={`/reviews/reply/${shownId}?from=record`} size="sm">
              이 리뷰 처리하기
            </BtnLink>
          </div>
        ) : null
      }
    />
  );
}

/**
 * One review, read — the old channel record's detail, in the pane. Reads the review by its own id (org-scoped), and
 * acts through the one account the channel facts name: `[쿠팡에서 보기]` and the pilot's silver are account-bound.
 */
function ReviewReadPane({
  reviewId,
  docked,
  facts,
  aiPilotEnabled,
  recordBehavior,
}: {
  reviewId: string;
  /**
   * <b>The pane is drawing this, so the pane's floor carries the door.</b> Below the master-detail
   * breakpoint there is no pane and no floor — the detail is drawn in the list's own column — and the door
   * goes back where the facts it follows are. A docked action that exists only on wide screens would be an
   * action that disappears on narrow ones.
   */
  docked: boolean;
  facts: ReviewRecordChannelFacts | null;
  aiPilotEnabled: boolean;
  recordBehavior: (accountId: string | null, events: TriageBehaviorEvent[]) => void;
}) {
  const [detail, setDetail] = useState<ChannelReviewDetailView | null | undefined>(undefined);
  /**
   * <b>결정 맥락 — 같은 두 GET, 쓰기는 하나도 없다</b> (리뷰 canonical mockup, 2026-10-03).
   *
   * <p>반복 신호 and 이 상품에 대해 아는 것 are what makes this pane a place to DECIDE rather than a second
   * reading of the row. Both come from the reads 리뷰 처리 already uses — no endpoint, no table, no model
   * call is added — and both fail silent: a pane that could not see the issue memory says nothing about it
   * rather than 「없습니다」.
   */
  const [context, setContext] = useState<ReviewDecisionContext | null>(null);
  const [contextFailed, setContextFailed] = useState(false);
  const [log, setLog] = useState<ReviewDecisionLogEntry[] | null>(null);
  const [logFailed, setLogFailed] = useState(false);
  const accountId = detail?.sellerAccountId ?? facts?.accountId ?? null;
  const locate = useReviewLocate(accountId ?? "");
  const capability = facts?.capability ?? null;
  const pilotOn = aiPilotEnabled && (capability?.aiTriage ?? false);

  useEffect(() => {
    let live = true;
    api
      .getReviewWorkspace(reviewId)
      .then((view) => live && setDetail(view))
      .catch(() => live && setDetail(null));
    return () => {
      live = false;
    };
  }, [reviewId]);

  useEffect(() => {
    let live = true;
    setContext(null);
    setContextFailed(false);
    setLog(null);
    setLogFailed(false);
    Promise.resolve()
      .then(() => api.getReviewDecisionContext(reviewId))
      .then((view) => live && setContext(view))
      .catch(() => {
        if (!live) return;
        setContext(null);
        setContextFailed(true);
      });
    Promise.resolve()
      .then(() => api.getReviewDecisionLog(reviewId))
      .then((rows) => live && setLog(rows))
      .catch(() => {
        if (!live) return;
        setLog(null);
        setLogFailed(true);
      });
    return () => {
      live = false;
    };
  }, [reviewId]);

  useEffect(() => {
    if (detail && pilotOn && (detail.aiMark !== null || detail.triage.tier === "NEEDS_ATTENTION")) {
      recordBehavior(accountId, [{ reviewId: detail.id, kind: "REVIEW_OPENED" }]);
    }
  }, [detail, pilotOn, accountId, recordBehavior]);

  const record = useCallback((events: TriageBehaviorEvent[]) => recordBehavior(accountId, events), [recordBehavior, accountId]);
  const title = useMemo(() => {
    if (!detail) return "리뷰";
    const body = detail.body ? plainText(detail.body).trim() : "";
    return detail.textless || body.length === 0 ? "별점만 남긴 리뷰" : body;
  }, [detail]);

  if (detail === undefined) return <p className="text-sm text-muted">불러오는 중…</p>;
  if (detail === null) return <p className="text-muted">리뷰를 불러오지 못했습니다.</p>;

  return (
    <CaseLayout
      variant="pane"
      label="선택한 리뷰"
      decisionLabel="읽기"
      /* The blocks below are a reading, not a workspace: no form, no control that writes. `preview` is the
         depth that says so, and it is what draws 반복 신호 and 이 상품에 대해 아는 것 without the card each
         carries on the full case. */
      depth="preview"
      meta={
        <Facts>
          {/* <b>판정이 머리말의 첫 단어다</b> (리뷰 canonical mockup, 2026-10-03). It was the first chip of
              the block below, 155px further down, where it stood between the customer's sentence and the
              reason for it. The header owns the closed facts about WHICH review this is, and which bucket
              it is in is the one the seller reads first. Said once: {@link ReviewReadDetail} draws it only
              when nothing above it does. */}
          <TriageTierChip tier={detail.triage.tier} quiet />
          <span>{sourceLabel(facts?.channelCode ?? facts?.channelNameKo ?? null, "REVIEW", detail.rating)}</span>
          <span className="tabular-nums">{detail.writtenOn ?? "날짜 없음"}</span>
        </Facts>
      }
      title={title}
      sub={detail.productName ?? undefined}
      decision={
        <ReviewReadDetail
          header="caller"
          pilotOn={pilotOn}
          capability={capability}
          word={WORD}
          recordBehavior={record}
          detail={detail}
          context={context}
          contextFailed={contextFailed}
          door={!docked}
          log={log}
          logFailed={logFailed}
          locate={locate}
          run={locate.reviewId === detail.id ? locate.view : null}
          running={locate.reviewId === detail.id && locate.starting}
          unavailable={locate.reviewId === detail.id ? locate.unavailable : null}
        />
      }
    />
  );
}

/** The review's owner address — the key a case names its subject by. */
function ownerOf(reviewId: string): string {
  return `/reviews/reply/${reviewId}`;
}

function tierCount(page: ReviewRecordPageView, tier: ReviewTriageTier): number {
  if (tier === "NEEDS_ATTENTION") return page.triageSummary.needsAttention;
  if (tier === "WATCH") return page.triageSummary.watch;
  return page.triageSummary.fyi;
}

/** Which slice of the list is on screen, from the response — never from local state. */
export function rangeLabel(page: ReviewRecordPageView | null): string {
  if (page === null || page.items.length === 0) return "0개 표시 중";
  const first = page.page * page.size + 1;
  // 「· 총 N개」 is gone (2026-10-01): the pressed tier chip above the list carries the total, and this
  // hint is here to say WHERE IN IT the seller is. Repeating the denominator beside the window made the
  // same number the loudest thing on a header the list had not started under yet.
  return `${first}–${first + page.items.length - 1}번째`;
}

function normalizeChannel(value: string | null): string | null {
  if (!value) return null;
  const code = value.toUpperCase();
  return ["NAVER", "COUPANG", "CAFE24"].includes(code) ? code : null;
}

function uniqueChannels(targets: ReviewAccount[]): string[] {
  const order = ["NAVER", "COUPANG", "CAFE24"];
  const codes = new Set(targets.map((t) => t.channel.code));
  return order.filter((c) => codes.has(c));
}

