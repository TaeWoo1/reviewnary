import { useCallback, useEffect, useState } from "react";
import type { ReactNode } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { PageHead } from "../../components/ui/PageHead";
import { SectionHeader } from "../../components/ui/SectionHeader";
import { Disclosure } from "../../components/ui/Disclosure";
import { Btn } from "../../components/ui/Btn";
import { AgentLaunch } from "../../components/ui/AgentLaunch";
import { dataStateLabel } from "../../components/ui/DataState";
import { useAgentSurface } from "../../lib/agentPanel";
import { api } from "../../lib/apiClient";
import { count, kstDayTime, kstMonthDay, won } from "../../lib/format";
import {
  changeOf,
  counterById,
  figureOf,
  hasReadRange,
  noChangeReason,
  readVerdict,
  shownReadChannels,
  ungatedEdition,
} from "../../lib/reportReading";
import type { Figure } from "../../lib/reportReading";
import type {
  AgentReportListItem,
  AgentReportView,
  ReportCounter,
  ReportIssueFact,
  ReportKind,
  ReportRead,
} from "../../lib/types";

/**
 * 운영 리포트 — <b>기간별 운영 사실을 확인하는 작업 화면</b> (리포트 redesign, 2026-10-06).
 *
 * <p><b>전에 이 화면은 설명하는 대시보드였다.</b> 맨 위에 AI 운영 요약이 서고 그 아래에 숫자가 섰다.
 * 2026-10-06에 측정한 주간 판(9월 28일 ~ 10월 4일)에서 그 구조가 무엇을 만드는지 보였다 — 화면은
 * 「받은 리뷰 0건」, 「받은 문의 0건 · 이전 기간보다 4건 줄음」을 적었고, 모델은 그것을 받아
 * 「문의 감소와 배송 지연 기준 보완 제안」을 제목으로 달았다. 그런데 같은 시각 coverage는 세 채널
 * 아홉 줄 전부가 미증명이었고, 마지막으로 성공한 수집은 문의·리뷰 9월 26일, 주문 9월 5일이었다.
 * <b>그 기간은 한 번도 읽히지 않았다.</b> 화면이 적은 0은 측정된 0이 아니라 침묵이었고, 「4건 줄음」은
 * 읽지 않은 창에서 꺼낸 감소였으며, 요약은 그 위에 한 겹 더 쌓은 문장이었다.
 *
 * <p><b>그래서 순서가 뒤집혔다.</b> 무엇을 어디까지 읽었는지가 숫자 위에 서고(읽은 범위), 그 아래가
 * 기간의 값이며, 읽지 못한 기간의 값은 「확인되지 않음」이다 — 0을 적는 것은 없는 사실을 만드는 일이다.
 *
 * <p><b>그 판정은 서버가 내린다.</b> {@code ReportFactsBuilder}가 Overview의 {@code counted()}를 그대로
 * 써서 기간을 세고, 자격을 갖춘 채널이 없으면 숫자 대신 {@code null}을 <b>저장</b>한다. 이 화면은 그
 * 결정을 그리기만 한다 — 화면이 자기 규칙을 하나 더 가지면 두 규칙이 서로 다른 말을 하기 시작한다.
 *
 * <p><b>읽기는 하나다.</b> 기간 · 수치 · 매출 · 읽은 범위가 전부 같은 스냅샷에서 오고, 같은 판을 다시
 * 열면 네 가지가 모두 그대로다. 읽은 범위만 라이브로 읽던 때에는 숫자는 멈춰 있는데 그 숫자의 자격만
 * 움직였다 — 9월 27일까지 읽고 만든 판이 다음 날에는 10월 8일까지 읽은 것처럼 보였다. 이 화면은 지금의
 * 수집 상태를 말하지 않는다; 그것은 연결 화면의 일이다.
 *
 * <p>{@code pages-copy.test.ts}가 이 파일의 원본을 그대로 훑으므로, 주장 어휘는 여기 설명으로 둔다.
 */
export function ReportsV2() {
  const [params, setParams] = useSearchParams();
  const kind: ReportKind = params.get("kind") === "MONTHLY" ? "MONTHLY" : "WEEKLY";
  const reportId = params.get("id");
  const [report, setReport] = useState<AgentReportView | null>(null);
  const [history, setHistory] = useState<AgentReportListItem[] | null>(null);
  const [loading, setLoading] = useState(true);
  const [failed, setFailed] = useState(false);
  const [regenerating, setRegenerating] = useState(false);
  const [regenerateFailed, setRegenerateFailed] = useState(false);

  /*
    리포트는 읽고 나서 묻고 싶어지는 자리다. 가는 것은 「어느 화면인가」뿐이다 — runtime의 도구 목록에
    리포트 READ가 없으므로 리포트 id나 fact 참조는 어떤 도구도 사실로 바꿀 수 없는 힌트가 된다. 이
    화면의 글도 가지 않는다. 다른 모든 화면과 같은 패널, 같은 대화다.
  */
  useAgentSurface(
    report ? { surface: "report", label: `${report.kindLabelKo} 리포트 · ${report.periodLabelKo}` } : null,
  );

  useEffect(() => {
    let active = true;
    setLoading(true);
    setFailed(false);
    const read = reportId ? api.getAgentReport(reportId) : api.getCurrentAgentReport(kind);
    void Promise.allSettled([read, api.listAgentReports(kind)]).then(([reportResult, listResult]) => {
      if (!active) return;
      if (reportResult.status === "fulfilled") {
        setReport(reportResult.value);
      } else {
        setReport(null);
        setFailed(true);
      }
      setHistory(listResult.status === "fulfilled" ? listResult.value : null);
      setLoading(false);
    });
    return () => {
      active = false;
    };
  }, [kind, reportId]);

  const period = report?.facts.period ?? null;

  const switchKind = useCallback(
    (next: ReportKind) => {
      const nextParams = new URLSearchParams();
      if (next === "MONTHLY") nextParams.set("kind", "MONTHLY");
      setParams(nextParams);
    },
    [setParams],
  );

  const regenerate = useCallback(async () => {
    if (!report) return;
    setRegenerating(true);
    setRegenerateFailed(false);
    try {
      const next = await api.regenerateAgentReport(report.kind, report.periodStart);
      setReport(next);
      const nextParams = new URLSearchParams(params);
      nextParams.set("id", next.id);
      setParams(nextParams);
      void api.listAgentReports(report.kind).then(setHistory, () => undefined);
    } catch {
      setRegenerateFailed(true);
    } finally {
      setRegenerating(false);
    }
  }, [params, report, setParams]);

  const kindToggle = (
    <div role="group" aria-label="리포트 기간" className="inline-flex gap-1">
      {(["WEEKLY", "MONTHLY"] as const).map((k) => (
        <Btn
          key={k}
          size="sm"
          variant={k === kind ? "solid" : "outline"}
          aria-pressed={k === kind}
          onClick={() => switchKind(k)}
        >
          {k === "WEEKLY" ? "주간" : "월간"}
        </Btn>
      ))}
    </div>
  );

  return (
    <div className="space-y-6">
      <PageHead
        title="운영 리포트"
        meta={
          <span className="text-sm text-muted">
            {report ? (
              <>
                {report.kindLabelKo} · {report.periodLabelKo} · {kstDayTime(report.generatedAt)}에 만든 판
                {report.version > 1 ? ` · ${report.version}번째` : ""}
              </>
            ) : (
              "끝난 기간 하나를 그대로 얼려 둔 기록입니다"
            )}
          </span>
        }
        action={
          <div className="flex flex-wrap items-center gap-2">
            {kindToggle}
            <AgentLaunch context={{ surface: "report" }} label="이 내용으로 물어보기" />
          </div>
        }
      />

      {/* 읽은 범위는 기간의 값보다 먼저, 그리고 스냅샷과 무관하게 선다. */}
      {report ? (
        <ReadRange
          reads={report.facts.reads ?? []}
          counters={report.facts.counters}
          action={
            <Btn size="sm" variant="outline" onClick={() => void regenerate()} disabled={regenerating}>
              {regenerating ? "다시 만드는 중…" : "지금 자료로 다시 만들기"}
            </Btn>
          }
          note={regenerateFailed ? "새 판을 만들지 못했습니다." : null}
        />
      ) : null}

      {loading ? (
        <p className="text-sm text-muted">기간 기록을 불러오는 중…</p>
      ) : !report || !period ? (
        <p className="text-sm text-warn" data-testid="report-unavailable">
          {failed ? "기간 기록을 불러오지 못했습니다. 잠시 후 다시 열어 주세요." : "이 기간의 기록이 아직 없습니다."}
        </p>
      ) : (
        <>
          <Changed report={report} />
          <Counts report={report} />
          <Issues
            issues={report.facts.issues}
            reviewsRead={["MEASURED", "PARTIAL"].includes(figureOf(counterById(report.facts.counters, "c-reviews")).kind)}
          />
          <Sales report={report} />
          <Prepared report={report} />
        </>
      )}

      {history && history.length > 1 ? (
        <Disclosure label="이전 판" note={`${history.length}건`}>
          <ul className="mt-2 space-y-1 text-sm">
            {history.map((item) => (
              <li key={item.id}>
                <Link
                  to={`/reports?kind=${item.kind}&id=${item.id}`}
                  aria-current={item.id === report?.id ? "true" : undefined}
                  className={`underline-offset-2 hover:text-brand-700 hover:underline ${item.id === report?.id ? "font-semibold text-ink" : "text-muted"}`}
                >
                  {item.periodLabelKo}
                  {item.version > 1 ? ` · ${item.version}번째` : ""} · {kstDayTime(item.generatedAt)}
                </Link>
              </li>
            ))}
          </ul>
        </Disclosure>
      ) : null}
    </div>
  );
}

/** 한 구역 — 이름과 그 아래 선 하나. 주문·상품·홈이 쓰는 그 문법이고, 상자가 아니다. */
function Block({
  title,
  note,
  right,
  action,
  children,
}: {
  title: string;
  note?: ReactNode;
  right?: ReactNode;
  action?: ReactNode;
  children: ReactNode;
}) {
  return (
    <section aria-label={title} className="space-y-2">
      <SectionHeader
        title={
          <span className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
            {title}
            {note ? <span className="min-w-0 break-keep text-sm font-normal text-muted">{note}</span> : null}
          </span>
        }
        action={action ?? (right ? <span className="text-xs text-muted">{right}</span> : undefined)}
      />
      {children}
    </section>
  );
}

/**
 * 한 값 — 그리고 <b>적지 않는 쪽</b>.
 *
 * <p>읽지 못한 자리에 0을 적으면 그것은 「없었다」는 말이 된다. 이 화면에서 가장 중요한 한 줄이다.
 */
function Value({ f, unit = "건" }: { f: Figure; unit?: string }) {
  if (f.kind === "UNREAD") {
    return <span className="font-medium text-warn">확인되지 않음</span>;
  }
  return (
    <>
      <b className="font-semibold tabular-nums text-ink">{unit === "원" ? won(f.value) : count(f.value)}</b>
      {unit === "원" ? null : <span className="text-muted">{unit}</span>}
      {f.kind === "PARTIAL" ? (
        <span className="ml-1.5 text-muted">{f.excluded > 0 ? `채널 ${f.excluded}곳 빠짐` : "최신 여부 미확인"}</span>
      ) : null}
      {f.kind === "UNGATED" ? <span className="ml-1.5 text-muted">수집 범위 기록 없음</span> : null}
    </>
  );
}

const READ_COL = {
  type: "w-[110px] shrink-0 pr-4",
  last: "w-[180px] shrink-0 pr-4 tabular-nums",
  period: "w-[130px] shrink-0 pr-4",
  channels: "min-w-0 flex-1 truncate",
};

/**
 * 읽은 범위 — 숫자보다 먼저, 그리고 <b>숫자와 같은 시각에 얼어 있다</b>.
 *
 * <p>「마지막 확인」도 「이 기간」도 이 판이 만들어질 때의 값이다. 오늘의 수집 상태는 여기 오지 않는다 —
 * 그것은 다른 사실이고 연결 화면의 일이며, 섞으면 멈춰 있는 숫자 위에서 근거만 혼자 움직인다.
 */
function ReadRange({
  reads,
  counters,
  action,
  note,
}: {
  reads: ReportRead[];
  counters: ReportCounter[];
  action?: ReactNode;
  note: string | null;
}) {
  const recorded = hasReadRange(reads);
  const ungated = ungatedEdition(counters);
  const verdicts = reads.map((read) => readVerdict(read));
  const anyShort = verdicts.some((v) => v.warn);

  return (
    <Block
      title="읽은 범위"
      note={
        !recorded ? (
          <span className="font-medium text-warn">
            {ungated
              ? "이 판은 수집 범위를 확인하기 전에 만들어졌습니다 — 아래 숫자가 어디까지 읽은 것인지 기록이 없습니다."
              : "이 판에는 읽은 범위가 기록되어 있지 않습니다."}
          </span>
        ) : anyShort ? (
          <span className="font-medium text-warn">
            읽지 못한 기간의 값은 0이 아니라 「확인되지 않음」으로 적습니다.
          </span>
        ) : (
          <span>이 기간은 모든 채널이 말한 자료로 서 있습니다.</span>
        )
      }
      action={action}
    >
      {!recorded ? (
        <p className="border-t border-line/70 py-2 text-sm text-warn" data-testid="report-no-read-range">
          다시 만들면 각 숫자가 어느 채널까지 읽은 것인지 함께 기록됩니다.
        </p>
      ) : (
        <>
          <div aria-hidden="true" className="flex items-center pb-1 text-xs text-muted">
            <span className={READ_COL.type}>종류</span>
            <span className={READ_COL.last}>마지막 확인</span>
            <span className={READ_COL.period}>이 기간</span>
            <span className={READ_COL.channels}>채널</span>
          </div>
          <ul data-testid="report-reads">
            {reads.map((read, i) => (
              <li key={read.dataType} className="flex items-baseline border-t border-line/70 py-2 text-sm">
                <span className={`${READ_COL.type} font-medium text-ink`}>{read.labelKo}</span>
                <span className={`${READ_COL.last} text-muted`}>
                  {read.lastReadAt ? kstDayTime(read.lastReadAt) : "읽은 적 없음"}
                </span>
                <span className={`${READ_COL.period} ${verdicts[i].warn ? "text-warn" : "text-muted"}`}>
                  {verdicts[i].text}
                </span>
                {/* 이름은 제품이 노출하는 채널만 — 판매자가 붙일 수도, 고칠 수도 없는 채널을
                    「무엇이 빠졌는가」의 목록에 세우지 않는다 ({@link shownReadChannels}). 판정과
                    숫자는 저장된 집합 그대로다. */}
                <span className={`${READ_COL.channels} text-muted`}>
                  {shownReadChannels(read).length === 0
                    ? "연결된 채널이 없습니다"
                    : shownReadChannels(read)
                        .map((c) => `${c.channelNameKo} ${dataStateLabel(c.state)}`)
                        .join(" · ")}
                </span>
              </li>
            ))}
          </ul>
          {note ? <p className="border-t border-line/70 py-2 text-sm text-bad">{note}</p> : null}
        </>
      )}
    </Block>
  );
}

const ROW = {
  label: "min-w-0 flex-1 truncate pr-4",
  value: "w-[200px] shrink-0 pr-4 text-right",
  against: "w-[300px] shrink-0 text-right",
};

/**
 * 이번 기간에 달라진 것 — <b>서버가 두 창을 다 측정했을 때만 선다</b>.
 *
 * <p>저장된 delta가 있으면 비교가 성립한 것이고, 없으면 성립하지 않은 것이다. 화면이 두 수를 빼서 다시
 * 만들지 않는 이유가 그것이다 — 그렇게 만든 차이가 「0건, 이전 기간보다 4건 줄음」이었다.
 */
function Changed({ report }: { report: AgentReportView }) {
  const periodic = report.facts.counters.filter((c) => c.periodic);
  const moved = periodic
    .map((counter) => ({ counter, moved: changeOf(counter) }))
    .filter((row) => row.moved !== null && row.moved.delta !== 0);
  const first = periodic[0] ?? null;

  return (
    <Block
      title="이번 기간에 달라진 것"
      note={`${kstMonthDay(report.facts.period.previousStart)} ~ ${kstMonthDay(report.facts.period.previousEnd)}과 비교`}
    >
      {moved.length === 0 ? (
        <p className="border-t border-line/70 py-2 text-sm text-muted" data-testid="report-no-change">
          {first ? `달라진 것을 세울 수 없습니다 — ${noChangeReason(first)}.` : "비교할 수치가 없습니다."}
        </p>
      ) : (
        <ul data-testid="report-changes">
          {moved.map(({ counter, moved: m }) => (
            <li key={counter.id} className="flex items-baseline border-t border-line/70 py-2 text-sm">
              <span className={`${ROW.label} text-ink`}>{counter.labelKo}</span>
              {/* 빠진 채널은 아래 구역의 같은 수치가 들고 있다 — 여기 한 번 더 적으면 한 줄이 세 마디가 된다. */}
              <span className={`${ROW.value} tabular-nums text-ink`}>
                <b className="font-semibold">{amount(m!.current, counter)}</b>
              </span>
              <span className={`${ROW.against} text-muted`}>
                이전 기간 <span className="tabular-nums">{amount(m!.previous, counter)}</span> ·{" "}
                <span className={m!.delta > 0 ? "font-medium text-warn" : "text-muted"}>
                  {m!.delta > 0 ? `${amount(m!.delta, counter)} 늘음` : `${amount(-m!.delta, counter)} 줄음`}
                </span>
              </span>
            </li>
          ))}
        </ul>
      )}
    </Block>
  );
}

/** 「317건」 · 「₩5,721,102」 — 단위는 사실에 붙어 있다. */
function amount(value: number, counter: ReportCounter): string {
  return counter.unit === "원" ? won(value) : `${count(value)}건`;
}

/** 리뷰와 문의 — 기간의 값, 그리고 기간과 무관한 지금 수치는 다른 줄에. */
function Counts({ report }: { report: AgentReportView }) {
  const counters = report.facts.counters;
  const unanswered = counterById(counters, "c-unanswered-now");
  const generatedAt = report.generatedAt;

  /* 기간은 제목 줄이 이미 말했다. 구역마다 한 번 더 적으면 같은 사실이 한 화면에 네 번 선다. */
  return (
    <>
      <Block title="리뷰" note="이 기간에 들어온 것">
        <ul data-testid="report-reviews">
          <Row label="받은 리뷰" f={figureOf(counterById(counters, "c-reviews"))} />
          <Row label="부정 리뷰" f={figureOf(counterById(counters, "c-negative-reviews"))} />
        </ul>
      </Block>

      <Block title="문의" note="이 기간에 들어온 것">
        <ul data-testid="report-inquiries">
          <Row label="받은 문의" f={figureOf(counterById(counters, "c-inquiries"))} />
          {unanswered ? (
            <li className="flex items-baseline border-t border-line/70 py-2 text-sm">
              <span className={ROW.label}>
                {/*
                  <b>기간이 없을 뿐, 시점이 없는 것은 아니다.</b> 이 수치는 판을 만든 그 순간의 값이고,
                  사흘 뒤에 그 판을 열면 「지금」은 더 이상 그 순간이 아니다. 그래서 저장된 라벨
                  (「현재 답변이 필요한 문의」)을 쓰지 않고, 이 줄이 무엇의 수치인지 화면이 직접 적는다 —
                  옛 판과 새 판이 같은 말을 하게 하는 방법이기도 하다. 0은 문이 아니므로 열 것이 있을 때만
                  링크가 된다.
                */}
                {unanswered.to && (unanswered.current ?? 0) > 0 ? (
                  <Link to={unanswered.to} className="text-ink underline-offset-2 hover:text-brand-700 hover:underline">
                    만든 시점 답변이 필요했던 문의
                  </Link>
                ) : (
                  <span className="text-ink">만든 시점 답변이 필요했던 문의</span>
                )}
              </span>
              <span className={ROW.value}>
                <Value f={figureOf(unanswered)} />
              </span>
              <span className={`${ROW.against} text-muted`}>{kstDayTime(generatedAt)} 기준</span>
            </li>
          ) : null}
        </ul>
      </Block>
    </>
  );
}

function Row({ label, f, unit }: { label: string; f: Figure; unit?: string }) {
  return (
    <li className="flex items-baseline border-t border-line/70 py-2 text-sm">
      <span className={`${ROW.label} text-ink`}>{label}</span>
      <span className={ROW.value}>
        <Value f={f} unit={unit} />
      </span>
      <span className={ROW.against} />
    </li>
  );
}

const ISSUE_COL = {
  title: "min-w-0 flex-1 truncate pr-4",
  count: "w-[160px] shrink-0 pr-4 text-right tabular-nums",
  severity: "w-[90px] shrink-0 pr-4 text-right",
  product: "w-[200px] shrink-0 truncate text-right",
};

/**
 * 반복 문제 — 근거 리뷰가 이 기간이나 직전 기간에 있었던 것.
 *
 * <p>근거는 리뷰이므로 리뷰 수집에 그대로 얹혀 있다. 서버가 그 창을 측정하지 못했으면 {@code measured}가
 * 거짓으로 저장되고, 그러면 「없음」도 「늘었다」도 쓸 수 없다.
 */
function Issues({ issues, reviewsRead }: { issues: ReportIssueFact[]; reviewsRead: boolean }) {
  const measured = issues.length === 0 ? reviewsRead : issues.every((i) => i.measured !== false);
  return (
    <Block
      title="반복 문제"
      note={
        /* 셀 건수가 없으면 단서도 없다 — 비어 있는 이유는 아래 한 줄이 이미 말한다. */
        measured || issues.length === 0 ? (
          "근거 리뷰가 이 기간이나 직전 기간에 있었던 것"
        ) : (
          <span className="text-warn">아래 건수는 이 판이 읽은 범위까지입니다</span>
        )
      }
      right={issues.length > 0 ? `${issues.length}건` : undefined}
    >
      {issues.length === 0 ? (
        <p className="border-t border-line/70 py-2 text-sm text-muted" data-testid="report-no-issues">
          {/* 근거가 리뷰이므로, 리뷰를 읽지 못한 기간에는 「없었다」도 말할 수 없다. */}
          {reviewsRead
            ? "이 기간에 근거 리뷰가 붙은 반복 문제가 없습니다."
            : "리뷰를 이 기간까지 읽지 못해, 반복 문제가 없었는지 확인할 수 없습니다."}
        </p>
      ) : (
        <ul data-testid="report-issues">
          {issues.map((issue) => (
            <li key={issue.id}>
              <Link
                to={issue.to}
                className="-mx-2 flex items-baseline rounded border-t border-line/70 px-2 py-2 text-sm transition hover:bg-canvas/60 focus:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-brand-700"
              >
                <span className={`${ISSUE_COL.title} font-medium text-ink`}>{issue.title}</span>
                <span className={`${ISSUE_COL.count} text-muted`}>
                  이번 <b className="font-semibold text-ink">{count(issue.current)}</b> · 이전 {count(issue.previous)}
                </span>
                {/* 늘었다는 것은 두 창을 다 읽었을 때만 할 수 있는 말이다. 아니면 심각도만 적는다. */}
                <span
                  className={`${ISSUE_COL.severity} ${issue.measured !== false && issue.delta > 0 ? "font-medium text-warn" : "text-muted"}`}
                >
                  {issue.measured !== false && issue.delta > 0 ? `${count(issue.delta)} 늘음` : issue.severityLabelKo}
                </span>
                <span className={`${ISSUE_COL.product} text-muted`}>{issue.productName ?? ""}</span>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </Block>
  );
}

/**
 * 매출 — <b>이 판의 사실이다</b>.
 *
 * <p>전에는 이 구역만 {@code /api/orders/summary}를 다시 읽었고, 그래서 같은 판을 다시 열면 금액이 움직일
 * 수 있었다. 지금은 금액도 채널별 몫도 스냅샷에 저장되어 있고({@code c-revenue} · {@code s-<channel>}),
 * 주문 수집과 같은 관문을 통과한 값이다 — 통과하지 못했으면 0이 아니라 「확인되지 않음」이 선다.
 */
function Sales({ report }: { report: AgentReportView }) {
  const revenue = counterById(report.facts.counters, "c-revenue");
  const orders = counterById(report.facts.counters, "c-orders");
  const share = report.facts.salesByChannel ?? [];
  const moved = changeOf(revenue);

  return (
    <Block title="매출" note="일자 집계입니다 — 한 건씩 읽지 않는 채널까지 포함합니다" right={report.facts.period.labelKo}>
      <ul data-testid="report-sales">
        <li className="flex items-baseline border-t border-line/70 py-2 text-sm">
          <span className={`${ROW.label} text-ink`}>기간 매출</span>
          <span className={ROW.value}>
            <Value f={figureOf(revenue)} unit="원" />
          </span>
          <span className={`${ROW.against} text-muted`}>
            {moved ? (
              <>
                이전 기간 <span className="tabular-nums">{won(moved.previous)}</span>
              </>
            ) : (
              "이전 기간은 확인되지 않음"
            )}
          </span>
        </li>
        <Row label="주문" f={figureOf(orders)} />
      </ul>
      {share.length > 0 ? (
        <>
          <p className="pt-1 text-xs text-muted">채널별</p>
          <ul data-testid="report-sales-channels">
            {share.map((row) => (
              <li key={row.id} className="flex items-baseline border-t border-line/70 py-2 text-sm">
                <span className={`${ROW.label} text-ink`}>{row.channelNameKo}</span>
                <span className={`${ROW.value} tabular-nums text-ink`}>{won(row.amount)}</span>
                <span className={ROW.against} />
              </li>
            ))}
          </ul>
        </>
      ) : null}
    </Block>
  );
}

/**
 * 준비된 일 — 반복 문제와 회사 지식에서 나온 것.
 *
 * <p>전에는 이 아래에 「다음에 할 일」이 한 구역 더 있었고, 그 줄들은 전부 이 페이지에 이미 서 있는 줄을
 * 다시 쓴 것이었다(미답변 수치 · 이 구역의 기회 · 늘어난 반복 문제). 목적지는 하나도 사라지지 않는다.
 */
function Prepared({ report }: { report: AgentReportView }) {
  const opportunities = report.facts.opportunities;
  return (
    <Block
      title="준비된 일"
      note="반복 문제와 회사 지식에서 나온 것"
      right={opportunities.length > 0 ? `${opportunities.length}건` : undefined}
    >
      {opportunities.length === 0 ? (
        <p className="border-t border-line/70 py-2 text-sm text-muted">이 기간의 자료에서 준비된 일이 없습니다.</p>
      ) : (
        <ul data-testid="report-prepared">
          {opportunities.map((o) => (
            <li key={o.id}>
              <Link
                to={o.to}
                className="-mx-2 block rounded border-t border-line/70 px-2 py-2 transition hover:bg-canvas/60 focus:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-brand-700"
              >
                <span className="flex flex-wrap items-baseline gap-x-2 text-sm">
                  <span className="font-medium text-ink">{o.issueTitle}</span>
                  <span className="text-muted">
                    {o.kindLabelKo} · {o.statusLabelKo}
                    {o.productName ? ` · ${o.productName}` : ""}
                  </span>
                </span>
                <span className="mt-0.5 block break-keep text-sm text-muted">{o.recommendationKo}</span>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </Block>
  );
}
