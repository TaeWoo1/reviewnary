import { useEffect, useState } from "react";
import { PageHead } from "../../components/ui/PageHead";
import { Panel } from "../../components/ui/Panel";
import { api } from "../../lib/apiClient";
import type { AgentUsageReport, AgentUsageRow, ReplyOutcomeView } from "../../lib/types";
import { HANDOFF_WORD } from "../../components/customerOperations/AgentHandoffs";

const KIND_WORD: Record<string, string> = {
  REPLY: "답글 에이전트",
  DRAFT: "초안(이전 방식)",
  PLAN: "AI 담당자 계획",
  JUDGE: "AI 담당자 판정",
  CONVERSE: "AI 담당자 대화",
  INVESTIGATE: "조사",
  INTERPRET: "해석",
  TRIAGE: "리뷰 분류(이전 방식)",
  RETRIEVE: "검색 보조",
};
const STEP_WORD: Record<string, string> = {
  CLASSIFY: "판단",
  DRAFT: "초안",
  CRITIQUE: "검토",
  REVISE: "고치기",
  FIGURE_CHECK: "사실·숫자 확인(초안에 포함, 한도에서 안 빠짐)",
  NEED_PLAN: "문의 항목 나누기",
  NEED_JUDGE: "항목별 근거 판정",
  INTENT: "질문 뜻 풀기",
  ELIGIBILITY: "근거 적합성",
  EMBED_QUESTION: "질문 임베딩",
  EMBED_PASSAGE: "자료 임베딩",
};
const TRIGGER_WORD: Record<string, string> = { SELLER: "판매자가 누름", INGEST: "가져온 직후", SCHEDULED: "자동 확인" };
const ACTOR_WORD: Record<string, string> = { USER: "판매자", RUN: "자동 실행", QA: "QA", BENCHMARK: "벤치마크" };

/** Today in the seller's calendar, as the backend's KST day. */
function kstToday(): string {
  return new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Seoul", year: "numeric", month: "2-digit", day: "2-digit" })
    .format(new Date());
}

const n = (value: number) => value.toLocaleString("ko-KR");

/**
 * <b>AI 사용량 — how many model calls a day takes, by what they were</b> (owner, 2026-10-28: 「사용량은 데모 돌려 보면서
 * 가늠을 해 봐야 할 것 같아. 모니터링 잘 할 수 있도록만 해 줘」).
 *
 * <p>Counts and sums only, one row per kind · step · trigger · actor, so a day can be read as 「연동 직후 판단 몇 번 · 초안
 * 몇 번 · 실행당 몇 번」. No content and no run ids leave the server. The limits stay where they are until a demo shows
 * what a day actually costs.
 */
export function AgentUsage() {
  const [date, setDate] = useState(kstToday());
  const [report, setReport] = useState<AgentUsageReport | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let live = true;
    setFailed(false);
    void api
      .getAgentUsageStrict(date)
      .then((r) => live && setReport(r))
      .catch(() => live && setFailed(true));
    return () => {
      live = false;
    };
  }, [date]);

  const reply = report?.rows.filter((r) => r.kind === "REPLY") ?? [];
  // Every reply-agent run judges exactly once, so the judge step's distinct runs are the day's runs.
  const replyRuns = reply.filter((r) => r.step === "CLASSIFY").reduce((sum, r) => sum + r.runs, 0);
  const replyCalls = reply.reduce((sum, r) => sum + r.calls, 0);

  return (
    <>
      <PageHead title="AI 사용량" description="하루 동안 AI를 몇 번, 무엇에 썼는지 단계와 계기별로 보여 드려요. 글 내용은 담지 않아요." />
      <div className="mb-4 flex flex-wrap items-center gap-3">
        <label className="text-sm font-semibold text-ink" htmlFor="usage-date">
          날짜
        </label>
        <input
          id="usage-date"
          type="date"
          value={date}
          onChange={(e) => e.target.value && setDate(e.target.value)}
          className="min-h-[36px] rounded-lg border border-line bg-surface px-3 text-sm text-ink"
        />
      </div>
      {failed ? (
        <Panel title="AI 사용량">
          <p className="text-warn">사용량을 불러오지 못했어요.</p>
        </Panel>
      ) : report === null ? (
        <Panel title="AI 사용량">
          <p className="text-muted">불러오는 중…</p>
        </Panel>
      ) : (
        <div className="space-y-4">
          <p className="break-keep text-sm text-muted" data-testid="usage-summary">
            {report.enabled
              ? `실제 AI 호출 ${n(report.sellerAndRunCalls)} / ${n(report.llmCallsLimit)}번 (자동 판단·검토·재시도 포함)` +
                (report.automaticCallsLimit !== undefined && report.automaticCallsLimit < report.llmCallsLimit
                  ? ` · 그중 자동 작업 ${n(report.automaticCalls ?? 0)} / ${n(report.automaticCallsLimit)}번` +
                    ` (나머지 ${n(report.llmCallsLimit - report.automaticCallsLimit)}번은 판매자 요청용)`
                  : "") +
                (report.itemsLimit ? ` · 판매자가 맡긴 일 ${n(report.sellerItems ?? 0)} / ${n(report.itemsLimit)}건` : "")
              : "사용량 기록이 꺼져 있어요."}
            {replyCalls > 0 && replyRuns > 0 ? ` · 답글 에이전트 실행당 평균 ${(replyCalls / replyRuns).toFixed(1)}번` : ""}
          </p>
          {report.rows.length === 0 ? (
            <p className="text-sm text-muted">이 날은 AI를 쓰지 않았어요.</p>
          ) : (
            <div className="overflow-x-auto rounded-2xl border border-line bg-surface">
              <table className="w-full min-w-[640px] text-sm" aria-label="AI 사용량">
                <thead className="border-b border-line text-left text-muted">
                  <tr>
                    <th className="px-4 py-2 font-semibold">무엇</th>
                    <th className="px-4 py-2 font-semibold">단계</th>
                    <th className="px-4 py-2 font-semibold">계기</th>
                    <th className="px-4 py-2 font-semibold">누가</th>
                    <th className="px-4 py-2 text-right font-semibold">호출</th>
                    <th className="px-4 py-2 text-right font-semibold">평균 시간</th>
                    <th className="px-4 py-2 text-right font-semibold">입력 · 출력 토큰</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-line/70 tabular-nums text-ink">
                  {report.rows.map((row: AgentUsageRow, i) => (
                    <tr key={`${row.kind}-${row.step}-${row.trigger}-${row.actor}-${i}`}>
                      <td className="px-4 py-2">{KIND_WORD[row.kind] ?? row.kind}</td>
                      <td className="px-4 py-2">{row.step ? (STEP_WORD[row.step] ?? row.step) : "—"}</td>
                      <td className="px-4 py-2">{row.trigger ? (TRIGGER_WORD[row.trigger] ?? row.trigger) : "—"}</td>
                      <td className="px-4 py-2">{ACTOR_WORD[row.actor] ?? row.actor}</td>
                      <td className="px-4 py-2 text-right">{n(row.calls)}</td>
                      <td className="px-4 py-2 text-right">
                        {row.elapsedMs > 0 ? `${(row.elapsedMs / row.calls / 1000).toFixed(1)}초` : "—"}
                      </td>
                      <td className="px-4 py-2 text-right">
                        {row.inputTokens + row.outputTokens > 0 ? `${n(row.inputTokens)} · ${n(row.outputTokens)}` : "—"}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}
      <AgentOutcomes />
    </>
  );
}

const pct = (part: number, whole: number) => (whole > 0 ? `${Math.round((part / whole) * 100)}%` : "—");

/**
 * <b>답글 에이전트 결과 — what the calls came to</b> (second outside review, 2026-10-28: cost is not the measure alone).
 * Over the last seven days: how runs ended, why they went to a person, and of the drafts saved, how many the seller
 * approved as written. Quiet when it cannot load: the usage table above is the page.
 */
function AgentOutcomes() {
  const [view, setView] = useState<ReplyOutcomeView | null>(null);
  useEffect(() => {
    let live = true;
    void Promise.resolve()
      .then(() => api.getReplyOutcomesStrict(7))
      .then((v) => live && setView(v ?? null))
      .catch(() => undefined);
    return () => {
      live = false;
    };
  }, []);
  if (!view || !view.runs) return null;
  const handoffs = view.byOutcome.HANDOFF ?? 0;
  const approved = view.approvedAsWritten + view.approvedAfterEdit + (view.approvedAfterRewrite ?? 0);
  return (
    <section aria-label="답글 에이전트 결과" className="mt-6 space-y-2">
      <h2 className="text-section font-bold tracking-tight text-ink">답글 에이전트 결과 (최근 {n(view.days)}일)</h2>
      <ul className="space-y-1 break-keep text-sm text-ink" data-testid="agent-outcomes">
        <li>
          실행 {n(view.runs)}번 · 초안 저장 {n(view.saved)} · 사람에게 넘김 {n(handoffs)} ({pct(handoffs, view.runs)}) · 실패{" "}
          {n(view.byOutcome.FAILED ?? 0)}
        </li>
        <li>
          초안을 저장한 리뷰 {n(view.saved)}건 중 AI 문장 그대로 승인 {n(view.approvedAsWritten)} (
          {pct(view.approvedAsWritten, approved)}) · 판매자가 고쳐서 승인 {n(view.approvedAfterEdit)} · AI가 다시 쓴 문장 승인{" "}
          {n(view.approvedAfterRewrite ?? 0)} · 아직 기다림 {n(view.waiting)}
          {view.medianMinutesToApproval !== null ? ` · 초안부터 승인까지 보통 ${n(view.medianMinutesToApproval)}분` : ""}
        </li>
        {Object.keys(view.handoffReasons).length > 0 ? (
          <li className="text-muted">
            넘긴 이유:{" "}
            {Object.entries(view.handoffReasons)
              .map(([reason, count]) => `${HANDOFF_WORD[reason]?.label ?? reason} ${n(count)}`)
              .join(" · ")}
          </li>
        ) : null}
      </ul>
    </section>
  );
}
