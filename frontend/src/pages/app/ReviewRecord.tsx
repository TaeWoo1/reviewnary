import { useCallback, useEffect, useRef, useState, type ReactNode } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { Facts } from "../../components/ui/ObjectRow";
import { Empty } from "../../components/ui/Empty";
import { Btn, BtnLink } from "../../components/ui/Btn";
import { AiMarkChip } from "../../components/reviews/TriageTierChip";
import { formatDateTime, josa, parseTierParam } from "../../components/reviews/recordParts";
import { QuietSelect } from "../../components/ui/QuietSelect";
import { api } from "../../lib/apiClient";
import { ratingLabel } from "../../lib/reviewRecord";
import { previewText } from "../../lib/plainText";
import { channelShort, COPY } from "../../lib/copy/customerOps";
import { TRIAGE_CORRECTION_COPY, TRIAGE_TIERS, TRIAGE_TIER_LABEL } from "../../lib/reviewTriage";
import type { ReviewAccount } from "../../lib/reviewAccounts";
import type {
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
  const rawTier = searchParams.get("tier");
  const tier = parseTierParam(rawTier);
  /**
   * <b>열려 있는 묶음</b>. 셋 중 하나는 늘 열려 있다 — 「전체」라는 네 번째 상태는 없앴다. 그것은 탭일 때의
   * 개념이고, 그룹에서 「전체」는 세 그룹을 다 펴는 것인데 참고 4천 건을 펴는 화면은 아무도 읽지 않는다.
   * 열지 않은 두 묶음도 이름과 수를 달고 거기 서 있으므로 기록 전체는 여전히 한눈에 보인다.
   */
  const openTier: ReviewTriageTier = tier ?? "NEEDS_ATTENTION";
  const channel = normalizeChannel(searchParams.get("channel"));
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
    void load(sort, openTier, channel, pageIndex);
  }, [sort, openTier, channel, pageIndex, load]);

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

  const record = (
    <>
      {head}

      {/*
        <b>분류는 축이 아니라 목록의 뼈대다</b> (리뷰 canonical mockup, 2026-10-05 — Linear Issues list).

        <p>확인 필요 · 지켜보기 · 참고가 탭으로 서 있었다. 탭은 「지금 무엇을 보고 있는가」를 묻지만,
        셋은 서로 다른 목록이 아니라 한 목록의 세 구간이다. 그래서 목록 안의 그룹 머리로 내려갔다 —
        이름과 수를 달고, 열린 그룹만 행을 편다. 숫자는 필터와 무관한 기록 전체의 수다.
      */}
      <div className="flex flex-wrap items-center gap-x-6 gap-y-2 pb-3">
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
            setParams({ channel: value === ALL_CHANNELS ? null : value });
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
          }}
        />
        {workCount && workCount > 0 ? (
          <Link to="/customer-operations/cases" className="ml-auto text-sm font-semibold text-brand-700 hover:underline">
            {COPY.listTitle}에 리뷰 {workCount.toLocaleString("ko-KR")}건 →
          </Link>
        ) : null}
      </div>

      {loadError ? (
        <Empty
          title="리뷰를 불러오지 못했습니다"
          body="연결 상태를 확인한 뒤 다시 시도해 주세요. 불러오지 못한 목록을 임의로 채우지는 않습니다."
          action={
            <Btn size="sm" onClick={() => void load(sort, openTier, channel, pageIndex)}>
              다시 시도
            </Btn>
          }
        />
      ) : loading && !page ? (
        <p className="text-muted">불러오는 중…</p>
      ) : page && page.items.length === 0 && recordTotal === 0 ? (
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
            <b>고객이 쓴 문장이 행의 첫 시선이다</b> (리뷰 canonical mockup, 2026-10-05 — Linear Issues list).

            <p>행은 한 줄이다. 맨 앞이 고객의 문장이고, 그것만 잉크색이다. 분류 태그·별점·채널·상품·날짜는
            오른쪽 한 덩어리의 주석으로 모였다 — 열마다 따로 다투지 않도록 묶어서, 더 작게. 행 사이의 줄도
            없앴다. 흰 바탕 위의 한 줄짜리 문장들이 이미 목록이고, 그 사이의 선은 읽을 것이 하나 더 있다는
            뜻이 아니었다.

            <p>그룹 머리는 sticky다. 스무 행을 내려가도 지금 읽는 것이 어느 묶음인지 남는다.
          */}
          <section aria-label="목록" className="-mx-4">
            {TRIAGE_TIERS.map((value) => {
              const open = value === openTier;
              return (
                <div key={value}>
                  <h2>
                    <button
                      type="button"
                      aria-expanded={open}
                      onClick={() => {
                        setParams({ tier: value });
                        setPageIndex(0);
                      }}
                      className="sticky top-0 z-10 flex w-full items-center gap-2 border-b border-line bg-canvas px-4 py-2 text-left transition hover:bg-line/60 focus:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-brand-700"
                    >
                      <span aria-hidden className="w-3 shrink-0 text-xs leading-none text-muted">{open ? "▾" : "▸"}</span>
                      <TierDot tier={value} />
                      <span className="text-xs font-bold text-ink">{TRIAGE_TIER_LABEL[value]}</span>
                      <span className="text-xs tabular-nums text-muted">{tierCount(page, value).toLocaleString("ko-KR")}</span>
                    </button>
                  </h2>
                  {open && page.items.length === 0 ? (
                    /* <b>빈 묶음은 빈 기록이 아니다.</b> 그래서 이 말은 목록을 대신하지 않고 묶음 안에 선다 —
                       다른 두 묶음의 이름과 수가 바로 위아래에 그대로 있어야, 어디가 비었는지가 말이 된다. */
                    <p className="px-4 py-6 pl-16 text-sm text-muted">
                      {`${TRIAGE_TIER_LABEL[value]}에 해당하는 ${josa(WORD, "이", "가")} 없습니다. 다른 묶음을 열어 보세요.`}
                    </p>
                  ) : open ? (
                    <ul>
                      {page.items.map(({ channelCode, channelNameKo, review }) => (
                        <li key={review.id}>
                          <Link
                            to={`/reviews/reply/${review.id}?from=record`}
                            className="flex items-center gap-8 py-3 pl-16 pr-4 transition hover:bg-canvas focus:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-brand-700"
                          >
                            <span
                              className={`min-w-0 flex-1 truncate break-keep text-base leading-snug ${
                                review.textless ? "text-muted" : "font-medium text-ink"
                              }`}
                            >
                              {review.textless ? "별점만 남긴 리뷰" : previewText(review.preview) || "표시할 수 있는 본문이 없습니다"}
                            </span>
                            {/* 오른쪽은 한 덩어리의 주석이다 — 폭이 정해져 있고, 줄이 아니라 묶음으로 약하다. */}
                            <span className="flex shrink-0 items-center gap-3 text-xs text-muted">
                              <span className="flex max-w-[16rem] items-center gap-2 overflow-hidden">
                                {review.aiMark ? <AiMarkChip quiet /> : null}
                                {review.sellerCorrection ? (
                                  <span className="truncate">{`${TRIAGE_CORRECTION_COPY.sellerPrefix} ${TRIAGE_TIER_LABEL[review.sellerCorrection.correctedTier]}`}</span>
                                ) : null}
                                {cases.has(ownerOf(review.id)) ? (
                                  <span className="truncate font-semibold text-brand-700">{COPY.listTitle}</span>
                                ) : null}
                                {review.isNew ? <span className="truncate">새 리뷰</span> : null}
                                {review.mediaCount > 0 ? <span className="truncate">{`사진·영상 ${review.mediaCount}`}</span> : null}
                                {review.triage.tags.length > 0 ? <span className="truncate">{review.triage.tags[0]}</span> : null}
                              </span>
                              {/* <b>별점은 한 글자와 숫자다</b> (리뷰 canonical mockup, 2026-10-05). 「★★☆☆☆ 2점」은
                                  13px에서 90px을 쓰고 두 줄로 접혀 행을 46px에서 63px로 밀어 올렸다 — 이 행에서
                                  가장 덜 중요한 사실이 가장 많은 자리를 차지한 셈이다. 읽어 주는 쪽에는 원래 문장이
                                  그대로 간다. */}
                              <span className="w-10 shrink-0 text-right tabular-nums" aria-label={ratingLabel(review.rating)}>
                                <span aria-hidden>★{review.rating}</span>
                              </span>
                              <span className="w-12 shrink-0 truncate">{channelShort(channelCode) ?? channelNameKo ?? "채널 미상"}</span>
                              <span className="w-44 shrink-0 truncate">{review.productName ?? review.productId ?? "상품 정보 없음"}</span>
                              <span className="w-20 shrink-0 text-right tabular-nums">{review.writtenOn ?? "날짜 없음"}</span>
                            </span>
                          </Link>
                        </li>
                      ))}
                    </ul>
                  ) : null}
                </div>
              );
            })}
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

  /*
    <b>목록은 화면 전체이고, 읽는 곳은 리뷰 처리 화면이다</b> (리뷰 canonical mockup, 2026-10-05).

    <p>이 화면은 440px pane을 달고 있었다. 한 줄짜리 행에 고객의 문장을 먼저 세우려면 가로가 필요하고,
    pane은 1600에서 그 가로의 1/3을 가져간다. 그리고 pane이 읽어 주던 것 — 왜 올라왔나요, 반복 신호,
    이 상품에 대해 아는 것, 판단과 조치 — 은 전부 `/reviews/reply/:id`가 이미 가지고 있다. 두 번 읽는
    대신 한 번 읽고, 그 한 번이 고칠 수도 있는 곳이다. 행을 누르면 거기로 간다.

    <p>Pane이 혼자 가지고 있던 둘은 그 화면으로 옮겼다 — 채널의 답변 등록 상태와 「리뷰 원문 보기」.
  */
  return record;
}

/**
 * The group's own mark. 확인 필요 is the only one that carries colour: it is the only one that is a claim
 * about the seller's time, and a list where every group has a coloured dot has no coloured dot.
 */
function TierDot({ tier }: { tier: ReviewTriageTier }) {
  return (
    <span
      aria-hidden
      className={`h-2 w-2 shrink-0 rounded-full ${
        tier === "NEEDS_ATTENTION" ? "bg-bad" : tier === "WATCH" ? "border border-muted" : "border border-line"
      }`}
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

