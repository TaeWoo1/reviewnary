import { useCallback, useEffect, useRef, useState, type ReactNode } from "react";
import { safetyLabel } from "../../lib/safetyLabel";
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
import { useScreenChanges } from "../../lib/screenChanges";
import type {
  ReviewRecordPageView,
  ReviewRecordRow,
  ReviewTriageTier,
  TriageBehaviorEvent,
} from "../../lib/types";

/**
 * Fifty rows (2026-10-26, owner): a first import is a year of reviews, and twenty one-line rows filled half a
 * 1440×900 screen before the pager — the seller paged where a glance would have done.
 */
const PAGE_SIZE = 50;
const WORD = "리뷰";

type Sort = "attention" | "newest" | "lowest";
type Reply = "pending" | "answered" | "unknown";
type Days = 7 | 30;

/** The 답변 상태 filter: the channel's own statement, in the words the 문의 screen uses for the same fact. */
const REPLY_WORD: Record<Reply, string> = { pending: "미답변", answered: "답변함", unknown: "답변 여부 모름" };
const ALL_REPLIES = "ALL";
const ALL_DAYS = "ALL";

/** The three orders, in the words the control has always used. */
const SORTS: readonly { value: Sort; label: string }[] = [
  { value: "attention", label: "먼저 볼 순" },
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
  /**
   * 「안전 관련만 보기」 (mockup ②, 2026-10-25): the groups stay as they are; this one control lists only the reviews
   * that mention harm, across them, each row saying its own group. Kept in the address, so it reopens as it was.
   */
  const safetyOnly = searchParams.get("safety") === "1";
  const channel = normalizeChannel(searchParams.get("channel"));
  /**
   * <b>정렬이 묶음을 정한다</b> (2026-10-26, owner: 「최신순으로 해도 확인 필요, 지켜보기 항목들이 그대로 유지되고 그
   * 안에서 순서가 바뀌는 구조 — 별론데」). 확인 필요순은 묶음 그대로다 — 그 정렬의 뜻이 묶음 순서이므로. 최신순과
   * 낮은 평점순은 묶음을 풀고 기록 전체를 한 줄로 세운다: 각 행이 자기 묶음을 첫 단어로 말한다(안전 관련만 보기가
   * 이미 그렇게 한다). Linear의 「정렬」과 「묶기」가 따로인 것과 같은 생각이고, 묶기를 따로 두지 않은 것은 셋째
   * 컨트롤이 고를 수 있는 조합이 이 둘뿐이기 때문이다. 정렬은 주소에 산다 — 다시 열어도 같은 목록이다.
   */
  const sort: Sort = parseSort(searchParams.get("sort"));
  const flat = sort !== "attention";
  const reply = parseReply(searchParams.get("reply"));
  const days = parseDays(searchParams.get("days"));
  const narrowed = reply !== null || days !== null;
  const [pageIndex, setPageIndex] = useState(0);
  const [page, setPage] = useState<ReviewRecordPageView | null>(null);
  // Whether `page` was read for the safety filter — the filter's list never shows rows read for the groups.
  const [pageIsSafety, setPageIsSafety] = useState(false);
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
    async (nextSort: Sort, nextTier: ReviewTriageTier | null, nextChannel: string | null, nextPage: number,
      nextSafety = false, nextReply: Reply | null = null, nextDays: Days | null = null, quiet = false) => {
      const ticket = ++seq.current;
      if (!quiet) setLoading(true);
      try {
        const view = await api.getReviewRecordStrict({
          channel: nextChannel ?? undefined,
          sort: nextSort,
          tier: nextTier ?? undefined,
          page: nextPage,
          size: PAGE_SIZE,
          ...(nextSafety ? { safety: true } : {}),
          ...(nextReply ? { reply: nextReply } : {}),
          ...(nextDays ? { days: nextDays } : {}),
        });
        if (ticket !== seq.current) return;
        setPage(view);
        setPageIsSafety(nextSafety);
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
        // A quiet re-read that failed keeps the list the seller is reading.
        if (quiet) return;
        setPage(null);
        setLoadError(true);
      } finally {
        if (ticket === seq.current) setLoading(false);
      }
    },
    [recordBehavior],
  );

  // A flat order asks for no tier: the whole record in that order is what the seller chose.
  const listedTier = flat ? null : openTier;
  // What arrived since this screen opened redraws it by itself (lib/screenChanges.tsx): the same list read again,
  // quietly — no 「불러오는 중」, and the page the seller is on stays the page they are on.
  const changes = useScreenChanges();
  const changesRead = useRef(changes);
  useEffect(() => {
    const quiet = changes !== changesRead.current;
    changesRead.current = changes;
    void load(sort, listedTier, channel, pageIndex, safetyOnly, reply, days, quiet);
  }, [sort, listedTier, channel, pageIndex, safetyOnly, reply, days, load, changes]);

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
      .catch(() => live && changes === 0 && setWorkCount(null));
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
  }, [changes]);

  const channelChoices = uniqueChannels(targets);
  // The channel column earns its width only when the rows can come from more than one channel.
  const manyChannels = (page?.channels.length ?? 0) > 1;
  const totalPages = page === null || page.size <= 0 ? 1 : Math.max(1, Math.ceil(page.total / page.size));
  const recordTotal = page ? page.triageSummary.needsAttention + page.triageSummary.watch + page.triageSummary.fyi : 0;

  /**
   * One row of the record; {@code withGroup} names its group, for every list that runs across them (최신순 · 낮은
   * 평점순 · 안전 관련만 보기).
   *
   * <p><b>행의 순서: 묶음 → 별점 → 고객의 문장 → 주석</b> (2026-10-26, owner). 묶음 머리 아래에서는 묶음이 이미
   * 말했으므로 별점부터다. 별점이 문장 앞에 온 것은 리뷰 목록을 훑는 사람이 문장보다 먼저 별점으로 거르기
   * 때문이다 — 오른쪽 주석 덩어리 안에서는 그 일을 못 했다. 고객의 문장은 여전히 행에서 유일한 잉크다.
   */
  const reviewRow = ({ channelCode, channelNameKo, review, safety }: ReviewRecordRow, withGroup: boolean) => (
                        <li key={review.id}>
                          <Link
                            to={`/reviews/reply/${review.id}?from=record`}
                            className="flex items-center gap-4 rounded-lg py-3 pl-3 pr-3 transition hover:bg-canvas focus:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-brand-700"
                          >
                            {withGroup ? (
                              <span className="flex w-[5.5rem] shrink-0 items-center gap-2 text-sm">
                                <TierDot tier={review.triage.tier} />
                                <span
                                  className={`truncate ${
                                    review.triage.tier === "NEEDS_ATTENTION" ? "font-semibold text-bad" : "text-muted"
                                  }`}
                                >
                                  {TRIAGE_TIER_LABEL[review.triage.tier]}
                                </span>
                              </span>
                            ) : null}
                            {/* <b>별점은 한 글자와 숫자다</b> (리뷰 canonical mockup, 2026-10-05). 읽어 주는 쪽에는 원래
                                문장이 그대로 간다. */}
                            <span
                              className="w-8 shrink-0 text-sm tabular-nums text-muted"
                              aria-label={ratingLabel(review.rating)}
                            >
                              <span aria-hidden>★{review.rating}</span>
                            </span>
                            <span
                              className={`min-w-0 flex-1 truncate break-keep text-base leading-snug ${
                                review.textless ? "text-muted" : "font-medium text-ink"
                              }`}
                            >
                              {review.textless ? "별점만 남긴 리뷰" : previewText(review.preview) || "표시할 수 있는 본문이 없어요"}
                            </span>
                            {/* 오른쪽은 한 덩어리의 주석이다 — 폭이 정해져 있고, 줄이 아니라 묶음으로 약하다. */}
                            <span className="flex shrink-0 items-center gap-3 text-xs text-muted">
                              <span className="flex max-w-[16rem] items-center gap-2 overflow-hidden">
                                {/* <b>한 행에 상태 단어는 하나다</b> (UI audit, 2026-10-06). 판매자 수정이 있으면 AI 표시는
                                    서지 않는다 — 두 사실 모두 상세에 그대로 있다. */}
                                {safety ? (
                                  <span className={`truncate font-semibold ${safety.reading === "WORRY" ? "text-warn" : "text-bad"}`}>
                                    {safetyLabel(safety.kind, safety.reading)}
                                  </span>
                                ) : null}
                                {review.aiMark && !review.sellerCorrection ? <AiMarkChip quiet /> : null}
                                {review.sellerCorrection ? (
                                  <span className="truncate">{`${TRIAGE_CORRECTION_COPY.sellerPrefix} ${TRIAGE_TIER_LABEL[review.sellerCorrection.correctedTier]}`}</span>
                                ) : null}
                                {cases.has(ownerOf(review.id)) ? (
                                  <span className="truncate font-semibold text-brand-700">{COPY.listTitle}</span>
                                ) : null}
                                {review.isNew ? <span className="truncate">새 리뷰</span> : null}
                                {/* The channel's statement, said only where it is a fact worth a word: a posted reply.
                                    미답변 is most of a first import, and a word on nearly every row is no word. */}
                                {review.channelReplyState === "ANSWERED" ? <span className="truncate">답변함</span> : null}
                                {review.mediaCount > 0 ? <span className="truncate">{`사진·영상 ${review.mediaCount}`}</span> : null}
                                {review.triage.tags.length > 0 ? <span className="truncate">{review.triage.tags[0]}</span> : null}
                              </span>
                              {manyChannels ? (
                                <span className="w-12 shrink-0 truncate">{channelShort(channelCode) ?? channelNameKo ?? "채널 미상"}</span>
                              ) : null}
                              <span className="w-44 shrink-0 truncate">{review.productName ?? review.productId ?? "상품 정보 없음"}</span>
                              <span className="w-20 shrink-0 text-right tabular-nums">{review.writtenOn ?? "날짜 없음"}</span>
                            </span>
                          </Link>
                        </li>
  );

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
            setParams({ sort: value === "attention" ? null : value });
            setPageIndex(0);
          }}
        />
        {/* <b>답변 상태</b> (2026-10-26, owner). 채널이 말한 것만 — 미답변과 「답변 여부 모름」은 다른 선택이다. 채널이 한
            번도 말하지 않은 리뷰를 미답변으로 세면 그 수는 증명하지 못하는 의무를 말한다. 각 선택 옆의 수는 그것을
            누르면 나올 수이고, 서버가 수를 주지 않으면(오래된 서버) 이름만 선다. */}
        <QuietSelect
          label="답변 상태"
          value={reply ?? ALL_REPLIES}
          options={[
            { value: ALL_REPLIES, label: "답변 상태 전체" },
            ...(["pending", "answered", "unknown"] as const)
              .filter((value) => value !== "unknown" || reply === "unknown" || (page?.reply?.unknown ?? 0) > 0)
              .map((value) => ({
                value,
                label: page?.reply
                  ? `${REPLY_WORD[value]} ${page.reply[value].toLocaleString("ko-KR")}`
                  : REPLY_WORD[value],
              })),
          ]}
          onChange={(value) => {
            setParams({ reply: value === ALL_REPLIES ? null : value });
            setPageIndex(0);
          }}
        />
        <QuietSelect
          label="기간"
          value={days === null ? ALL_DAYS : String(days)}
          options={[
            { value: ALL_DAYS, label: "전체 기간" },
            { value: "7", label: "최근 7일" },
            { value: "30", label: "최근 30일" },
          ]}
          onChange={(value) => {
            setParams({ days: value === ALL_DAYS ? null : value });
            setPageIndex(0);
          }}
        />
        {page && ((page.safety?.total ?? 0) > 0 || safetyOnly) ? (
          <button
            type="button"
            aria-pressed={safetyOnly}
            onClick={() => {
              setParams({ safety: safetyOnly ? null : "1" });
              setPageIndex(0);
            }}
            className={`rounded-full px-3 py-1 text-sm font-semibold ring-1 transition focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700 ${
              safetyOnly ? "bg-bad/10 text-bad ring-bad/30" : "bg-surface text-ink ring-line hover:bg-canvas"
            }`}
          >
            안전 관련만 보기 {(page.safety?.total ?? 0).toLocaleString("ko-KR")}
            {safetyOnly ? <span aria-hidden className="ml-1.5">✕</span> : null}
          </button>
        ) : null}
        {workCount && workCount > 0 ? (
          <Link to="/customer-operations/cases" className="ml-auto text-sm font-semibold text-brand-700 hover:underline">
            {COPY.listTitle}에 리뷰 {workCount.toLocaleString("ko-KR")}건 →
          </Link>
        ) : null}
      </div>

      {loadError ? (
        <Empty
          title="리뷰를 불러오지 못했어요"
          body="연결 상태를 확인한 뒤 다시 시도해 주세요. 불러오지 못한 목록을 임의로 채우지는 않아요."
          action={
            <Btn size="sm" onClick={() => void load(sort, listedTier, channel, pageIndex, safetyOnly, reply, days)}>
              다시 시도
            </Btn>
          }
        />
      ) : loading && !page ? (
        <p className="text-muted">불러오는 중…</p>
      ) : page && page.items.length === 0 && recordTotal === 0 && narrowed ? (
        /* 조건이 비운 목록은 빈 기록이 아니다 — 기록이 비었다고 말하면 수집을 다시 하라는 말이 된다. */
        <Empty
          title="조건에 맞는 리뷰가 없어요"
          body="답변 상태나 기간을 넓히면 다른 리뷰가 보여요."
          action={
            <Btn size="sm" variant="outline" onClick={() => { setParams({ reply: null, days: null }); setPageIndex(0); }}>
              조건 지우기
            </Btn>
          }
        />
      ) : page && page.items.length === 0 && recordTotal === 0 ? (
        <Empty
          title={`아직 수집된 ${josa(WORD, "이", "가")} 없어요`}
          body="채널 연동에서 리뷰 수집을 한 번 실행하면 이 목록에 쌓여요."
          action={
            <BtnLink to="/connect" size="sm">
              채널 연동로
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
          {/* <b>목록은 페이지 열 안에 있다</b> (UI audit, 2026-10-06). `-mx-4`는 padding 없는
              master-detail `main` 안에서 양쪽으로 16px씩 나갔고, 오른쪽에서는 그대로 뷰포트 밖이었다.
              이제 기록은 {@code max-w-content} 열 안에 서고, 묶음은 12px만 바깥으로 물러난다 — 행의
              글자가 제목과 같은 선에서 시작하면서 hover와 선택이 글자 바깥에서 끝나도록. */}
          {safetyOnly ? (
            <section aria-label="안전 관련 리뷰" className="-mx-3">
              {!pageIsSafety ? (
                <p className="px-3 py-6 text-sm text-muted">불러오는 중…</p>
              ) : page.items.length === 0 ? (
                <p className="px-3 py-6 text-sm text-muted">안전 관련 리뷰가 없어요.</p>
              ) : (
                <ul>{page.items.map((row) => reviewRow(row, true))}</ul>
              )}
            </section>
          ) : flat ? (
            <section aria-label="목록" className="-mx-3">
              {page.items.length === 0 ? (
                <p className="px-3 py-6 text-sm text-muted">{`조건에 맞는 ${josa(WORD, "이", "가")} 없어요.`}</p>
              ) : (
                <ul>{page.items.map((row) => reviewRow(row, true))}</ul>
              )}
            </section>
          ) : (
          <section aria-label="목록" className="-mx-3">
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
                      /* <b>묶음 머리는 이 제품의 구역 제목이다</b> (UI audit, 2026-10-06 — 공통 규칙
                         18/600 + hairline, {@code SectionHeader}가 쓰는 수). 12px bold는 행의 주석과
                         같은 크기여서 묶음과 행이 한 층으로 읽혔다. 펼침 표시와 수는 그대로다. */
                      className="sticky top-0 z-10 flex w-full items-center gap-2 border-b border-line bg-canvas px-3 py-3 text-left transition hover:bg-line/40 focus:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-brand-700"
                    >
                      <span aria-hidden className="w-3 shrink-0 text-xs leading-none text-muted">{open ? "▾" : "▸"}</span>
                      <TierDot tier={value} />
                      <span className="text-lg font-semibold text-ink">{TRIAGE_TIER_LABEL[value]}</span>
                      <span className="text-sm tabular-nums text-muted">{tierCount(page, value).toLocaleString("ko-KR")}</span>
                      {/* Words, not a control: the one control for these is 「안전 관련만 보기」 above. */}
                      {safetyIn(page, value) > 0 ? (
                        <span className="text-xs text-muted">{`· 안전 관련 ${safetyIn(page, value).toLocaleString("ko-KR")}`}</span>
                      ) : null}
                    </button>
                  </h2>
                  {open && page.items.length === 0 ? (
                    /* <b>빈 묶음은 빈 기록이 아니다.</b> 그래서 이 말은 목록을 대신하지 않고 묶음 안에 선다 —
                       다른 두 묶음의 이름과 수가 바로 위아래에 그대로 있어야, 어디가 비었는지가 말이 된다. */
                    <p className="px-3 py-6 pl-12 text-sm text-muted">
                      {`${TRIAGE_TIER_LABEL[value]}가 없어요. 다른 묶음을 열어 보세요.`}
                    </p>
                  ) : open ? (
                    <ul>
                      {page.items.map((row) => reviewRow(row, false))}
                    </ul>
                  ) : null}
                </div>
              );
            })}
          </section>
          )}

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
                    must not be told their record holds what their current filter holds. Under a 답변 상태 or
                    기간 condition it is that condition's count and says so (2026-10-26). */}
                <span className="tabular-nums">
                  {narrowed ? `조건에 맞는 ${recordTotal.toLocaleString("ko-KR")}개` : `총 ${recordTotal}개`}
                </span>
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
                연결 채널(네이버·쿠팡·카페24) 기준 · 파일로 올린 다른 채널 리뷰 {page.outsideVisibleChannels}건은 상품 화면에서 봐요
              </p>
            ) : null}
            {/* What only a channel can say: an import that stopped before the end of its list. */}
            {(page.channelFacts ?? [])
              .filter((f) => f.lastImportAt && !f.lastImportComplete)
              .map((f) => (
                <p key={f.channelCode} className="break-keep leading-relaxed">
                  {channelShort(f.channelCode) ?? f.channelNameKo}: 마지막 수집({formatDateTime(f.lastImportAt!)})이 목록 끝까지
                  확인되지 않은 상태로 끝났어요. 채널에 이보다 더 있을 수 있어요.
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

/** How many of a group's reviews mention harm — said on the group's head; 0 from an older server. */
function safetyIn(page: ReviewRecordPageView, tier: ReviewTriageTier): number {
  const s = page.safety;
  if (!s) return 0;
  return tier === "NEEDS_ATTENTION" ? s.needsAttention : tier === "WATCH" ? s.watch : s.fyi;
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

function parseSort(value: string | null): Sort {
  return value === "newest" || value === "lowest" ? value : "attention";
}

function parseReply(value: string | null): Reply | null {
  return value === "pending" || value === "answered" || value === "unknown" ? value : null;
}

function parseDays(value: string | null): Days | null {
  return value === "7" ? 7 : value === "30" ? 30 : null;
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

