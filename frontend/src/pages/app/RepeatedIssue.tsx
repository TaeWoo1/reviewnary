import { Link, useParams, useSearchParams } from "react-router-dom";
import { BtnLink } from "../../components/ui/Btn";
import { Disclosure } from "../../components/ui/Disclosure";
import { Empty } from "../../components/ui/Empty";
import { Facts } from "../../components/ui/ObjectRow";
import { CaseLayout } from "../../components/workspace/CaseLayout";
import { EvidenceQuote } from "../../components/memory/repeat/EvidenceQuote";
import { EvidenceTrend } from "../../components/memory/repeat/EvidenceTrend";
import { IssueDecision } from "../../components/memory/repeat/IssueDecision";
import { IssueGrounding } from "../../components/memory/repeat/IssueGrounding";
import { PaneBlock } from "../../components/memory/repeat/PaneBlock";
import { RatingSpread } from "../../components/memory/repeat/RatingSpread";
import { RepeatByProduct } from "../../components/memory/repeat/RepeatByProduct";
import { useRepeatedIssue } from "../../components/memory/useRepeatedIssue";
import { useAgentSurface } from "../../lib/agentPanel";
import { kstDate, kstToday } from "../../lib/format";
import { lifecycleSinceKo } from "../../lib/memoryView";
import {
  CHANGE_EXPLANATION_KO,
  SEVERITY_LABEL_KO,
  changeBadges,
  investigationHintKo,
  provenanceKo,
  suppressedQuoteCount,
  surgeLine,
} from "../../lib/reviewIssuesView";

/**
 * <b>반복 문제 하나 — 제 주소, 제 페이지</b> (canonical mockup, 2026-10-05 — Sentry issue detail).
 *
 * <p><b>왜 pane에서 나왔나.</b> 이 화면이 다루는 것은 「여러 증거가 하나로 묶인 문제」이고, 그것은 수·추이·
 * 분포·대표 증거·상태를 한 번에 봐야 판단된다. 1600에서 그 물건은 목록 980 옆 576px 칸에 들어가 있었고,
 * 그 안에서 월별 막대 → 상품 → 별점 → 인용 → 지식 → 결정 → 기록이 전부 세로로 쌓여 패널 안쪽 스크롤로만
 * 읽혔다 — 마지막 문장은 접힌 채였다. Sentry가 하나의 issue를 제 페이지로 여는 것과 같은 이유다.
 *
 * <p><b>목록은 그대로다.</b> 탭·분류·표·총계·행의 리듬 어느 것도 이 패키지가 건드리지 않았다. 바뀐 것은
 * 행을 눌렀을 때 열리는 곳 하나이고, 목록의 탭과 분류는 주소에 실려 따라왔다가 ← 반복 문제로 돌아간다.
 *
 * <p><b>읽기 둘, 쓰기 하나 — 전부 쓰던 것이다.</b> {@link useRepeatedIssue}가 하던 그대로:
 * detail과 repeat-context를 함께 띄워 따로 정착시키고, 결정은 issue lifecycle의 두 transition이다.
 * 새 endpoint 0 · 새 workflow 0 · 새 숫자 0. 이 페이지가 세는 것은 하나도 없다.
 *
 * <p><b>열 하나, 900px</b> — 리뷰 상세의 canonical baseline과 같은 {@link CaseLayout} page 읽기이고,
 * 안쪽 스크롤은 없다. 페이지가 스크롤된다.
 */
export function RepeatedIssue() {
  const { issueId = "" } = useParams();
  const [params] = useSearchParams();
  // 목록이 들고 있던 탭과 분류는 주소에 실려 왔다. 돌아갈 때 그대로 돌려준다 — 스크롤 위치는 목록 자신이
  // 기억한다(`MasterDetail`의 `scrollKey`).
  const query = params.toString();
  const back = `/memory${query ? `?${query}` : ""}`;

  // 같은 대화, 같은 표면 이름. 목록과 한 화면이던 때와 달라지지 않는다 — `AgentContext`에 issue 칸이
  // 없으므로 이 페이지도 열린 문제의 이름을 대화에 넘기지 않는다.
  useAgentSurface({ surface: "memory", label: "반복 문제" });

  const workspace = useRepeatedIssue(issueId);
  const { issue, detail, context, loading, failed, contextFailed } = workspace;

  const nav = (
    <Link to={back} className="text-sm font-semibold text-muted hover:text-ink hover:underline">
      ← 반복 문제
    </Link>
  );

  if (loading && !issue) {
    return (
      <div className="space-y-4">
        {nav}
        <p className="text-sm text-muted">불러오는 중…</p>
      </div>
    );
  }

  if (failed || !issue) {
    return (
      <div className="space-y-4">
        {nav}
        <Empty
          title="이 문제를 불러오지 못했습니다"
          body="연결 상태를 확인한 뒤 다시 시도해 주세요. 목록에서 다시 선택하실 수도 있습니다."
          action={<BtnLink to={back}>반복 문제 목록</BtnLink>}
        />
      </div>
    );
  }

  const evidence = detail?.evidence ?? [];
  const quotable = evidence.filter((row) => row.quote && row.quote.trim().length > 0);
  const shownQuotes = quotable.slice(0, 2);
  const restQuotes = quotable.slice(2);
  const suppressed = detail ? suppressedQuoteCount(evidence) : 0;
  const badges = changeBadges(issue.change);
  const surge = surgeLine(issue.change);
  const hint = investigationHintKo(issue);
  const productCount = context?.evidence.byProduct.length ?? 0;

  return (
    <CaseLayout
      variant="page"
      /* 둘째 열 없음 — 리뷰 상세와 같은 읽는 열 900. 속성 둘은 제목 아래 평문으로 선다. */
      rail="none"
      decisionLabel="판단과 조치"
      nav={nav}
      title={issue.title}
      /* <b>이 문제를 정의하는 수 둘</b> (Sentry의 Events · Users 자리). 근거가 몇 건이고 상품 몇 곳에서
         반복되는가 — 목록에서 이 행을 고른 이유이고, 아래 블록 둘이 각각 풀어 쓰는 것의 머리값이다.
         상품 수는 repeat-context가 돌아왔을 때만 선다: 못 읽은 수를 0으로 적는 것이 이 화면에서 가장
         나쁜 출력이다. */
      headerAction={
        <dl className="flex items-end gap-6">
          <div className="text-right">
            <dt className="text-xs text-muted">근거</dt>
            <dd className="mt-0.5 text-xl font-bold leading-none tabular-nums text-ink">
              {issue.evidenceCount.toLocaleString("ko-KR")}건
            </dd>
          </div>
          {productCount > 0 ? (
            <div className="text-right">
              <dt className="text-xs text-muted">상품</dt>
              <dd className="mt-0.5 text-xl font-bold leading-none tabular-nums text-ink">
                {productCount.toLocaleString("ko-KR")}곳
              </dd>
            </div>
          ) : null}
        </dl>
      }
      sub={
        <Facts>
          <span className="font-semibold text-ink">{issue.lifecycleLabelKo}</span>
          <span>심각도 {SEVERITY_LABEL_KO[issue.severity]}</span>
          {issue.firstEvidenceOn ? (
            <span className="tabular-nums">
              {issue.firstEvidenceOn}
              {issue.lastEvidenceOn && issue.lastEvidenceOn !== issue.firstEvidenceOn
                ? ` ~ ${issue.lastEvidenceOn}`
                : ""}
            </span>
          ) : null}
        </Facts>
      }
      subject={
        <>
          {/* <b>왜 지금인가는 머리말을 갖지 않는다</b> — 리뷰 상세와 같은 자리, 같은 한 줄. 판단된 변화가
              없으면 없다고 말한다. 밑줄 하나로 「무엇이고 왜 지금인가」를 닫는다. */}
          <section aria-label="변화와 신호" className="space-y-1.5 border-b border-line pb-5">
            {badges.length > 0 ? (
              <ul className="space-y-1.5">
                {badges.map((badge) => (
                  <li key={badge.kind} className="break-keep text-base leading-relaxed text-muted">
                    <span className="font-semibold text-ink">{badge.labelKo}</span> — {CHANGE_EXPLANATION_KO[badge.kind]}
                  </li>
                ))}
              </ul>
            ) : (
              <p className="break-keep text-base leading-relaxed text-ink">
                최근 판단된 변화는 없지만 관련 리뷰가 기록되어 있습니다.
              </p>
            )}
            {surge ? <p className="text-sm tabular-nums text-muted">{surge}</p> : null}
            {hint ? <p className="break-keep leading-relaxed text-ink">{hint}</p> : null}
          </section>

          {/* <b>집계는 쌓지 않고 한 띠로 선다</b> (Sentry의 Trends & Aggregates). 언제 들어왔고 어디서
              반복되는가는 같은 질문의 두 축이고, 세로로 쌓으면 둘을 함께 보는 일이 스크롤이 된다.
              900px는 두 축을 나란히 둘 수 있는 폭이다 — 576px pane은 아니었다. */}
          <div className="grid gap-x-10 gap-y-4 border-b border-line pb-5 lg:grid-cols-2">
            <PaneBlock label="언제 들어왔나">
              {detail ? (
                <EvidenceTrend evidence={evidence} throughMonth={kstToday().slice(0, 7)} />
              ) : (
                <p className="text-sm text-muted">근거를 불러오는 중…</p>
              )}
              <RatingSpread distribution={context?.evidence.ratingDistribution ?? null} failed={contextFailed} />
            </PaneBlock>
            {/* 못 읽었으면 블록 자체가 없다. 이름만 선 빈 칸은 「어디서 반복되나」에 대한 답이 없다는
                뜻이 아니라 답이 비어 있다는 뜻으로 읽힌다 — 보지 못한 사실에 대한 주장이다. */}
            {context && !contextFailed ? (
              <PaneBlock label="어디서 반복되나">
                {/* `region={false}`: 이름은 이 블록이 가진다. 같은 이름의 구역 둘은 한 문제에 대한 두 답이다. */}
                <RepeatByProduct evidence={context.evidence} failed={false} region={false} />
              </PaneBlock>
            ) : null}
          </div>

          {/* <b>이 화면에서 제일 큰 읽을거리</b>. 인용 둘이 서고 나머지는 접힘 하나 뒤에 있다 — 결정 위에
              쌓인 열여덟 개의 인용은 더 많은 근거가 아니라 아무도 끝까지 읽지 않는 글이다. */}
          <section aria-label="근거" className="border-b border-line pb-5">
            <div className="flex items-baseline gap-2.5">
              <h2 className="text-base font-bold text-ink">근거</h2>
              <p className="text-sm text-muted">
                고객이 쓴 문장 {issue.evidenceCount.toLocaleString("ko-KR")}건 가운데 둘
              </p>
            </div>
            <div className="mt-3 space-y-2">
              {loading ? (
                <p className="text-muted">근거를 불러오는 중…</p>
              ) : shownQuotes.length > 0 ? (
                <ul className="space-y-3 border-l-2 border-line pl-3">
                  {shownQuotes.map((row) => (
                    <EvidenceQuote key={`${row.reviewId}-${row.unitOrdinal}`} row={row} />
                  ))}
                </ul>
              ) : (
                <p className="text-muted">표시할 수 있는 인용이 없습니다.</p>
              )}
              {suppressed > 0 ? (
                <p className="text-xs text-muted">인용을 표시할 수 없는 근거가 {suppressed}건 더 있습니다.</p>
              ) : null}
              {restQuotes.length > 0 ? (
                <Disclosure
                  label={`근거 ${issue.evidenceCount.toLocaleString("ko-KR")}건 모두 보기`}
                  summaryClassName="-ml-2"
                >
                  <ul className="space-y-3 border-l-2 border-line pl-3 pt-2">
                    {restQuotes.map((row) => (
                      <EvidenceQuote key={`${row.reviewId}-${row.unitOrdinal}`} row={row} />
                    ))}
                  </ul>
                </Disclosure>
              ) : null}
            </div>
          </section>
        </>
      }
      context={
        <>
          <IssueGrounding knowledge={context?.knowledge ?? null} failed={contextFailed} />
          {/* 개선 기회 · 판매자가 남기는 한 문장 · 이 화면의 유일한 primary. 전부 쓰던 블록 그대로다. */}
          <IssueDecision
            issueId={issue.id}
            state={issue.lifecycleState}
            note={workspace.note}
            onNoteChange={workspace.setNote}
            busy={workspace.busy}
            error={workspace.error}
            onSubmit={workspace.submit}
            since={lifecycleSinceKo(issue, detail?.history ?? null, kstDate)}
          />
        </>
      }
      more={
        <PaneBlock label="기록">
          {detail && detail.history.length > 0 ? (
            <ul className="space-y-2">
              {[...detail.history].reverse().map((event) => (
                <li key={`${event.at}-${event.toState}`} className="text-xs tabular-nums text-muted">
                  <span className="font-semibold text-ink">{event.toStateLabelKo}</span>
                  {" · "}
                  {event.actor === "OPERATOR" ? "운영자" : "reviewnary"}
                  {" · "}
                  {kstDate(event.at)}
                  {event.note ? (
                    <span className="mt-0.5 block break-keep text-sm leading-relaxed text-ink">{event.note}</span>
                  ) : null}
                </li>
              ))}
            </ul>
          ) : (
            <p className="text-xs text-muted">아직 기록된 상태 변화가 없습니다.</p>
          )}
          <p className="break-keep text-xs leading-relaxed text-muted">{provenanceKo(issue)}</p>
        </PaneBlock>
      }
    />
  );
}
