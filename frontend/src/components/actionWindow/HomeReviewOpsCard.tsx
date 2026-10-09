import { Link } from "react-router-dom";
import type { ActionWindowRunView } from "../../lib/actionWindow/contract";
import {
  channelLabel,
  resolveCopy,
  CHECKPOINT_PROMPT_TITLE,
  HOME_REVIEW_OPS_COPY,
} from "../../lib/actionWindow/copy";
import { RunStatusBadge } from "./RunStatusBadge";

/**
 * "리뷰 수집" activity strip — a compact, read-only summary of the current
 * review run that deep-links into the operations workbench. It NEVER starts or
 * commands a run (that lives on /operations); it only reflects and links.
 *
 * Honesty: the caller passes `run` ONLY when it is honestly presentable as live
 * activity — a live-bridge run, or the DEV fixture preview — and `null` otherwise,
 * so the real-data screen never shows a seeded/mock run as a live job. A
 * `WAITING_FOR_HUMAN` run surfaces the checkpoint prompt and points the primary
 * action at the run detail ("확인하러 가기").
 *
 * <p><b>진행 중인 일이 없으면 이 줄은 없다</b> (2026-10-09). `run === null`일 때 이것은
 * 「진행 중인 네이버 리뷰 가져오기 작업이 없습니다.」라고 적었다 — 아무 일도 없는 정상 화면에
 * 아무 일도 없다고 적은 한 행이고, 그 문장이 판매자에게 가르치는 유일한 것은 우리 쪽 작업의
 * 이름이었다. 상태는 상태가 있을 때만 나타난다.
 *
 * <p><b>상자도, 제 이름을 다시 부르는 머리말도 없다</b> (2026-10-07). 이것이 서는 자리는 「자료 가져오기」
 * 구역 안이고, 거기에는 이미 이름과 그 아래 선이 있다 — 그 안에서 둥근 상자가 또 「네이버 리뷰 기간별
 * 가져오기」라고 적는 것은 한 구역에 이름이 둘이라는 뜻이었다. 이름은 읽어 주는 쪽을 위해
 * {@code aria-label}로 남는다.
 */
export function HomeReviewOpsCard({ run }: { run: ActionWindowRunView | null }) {
  if (!run) {
    return null;
  }
  return (
    <section aria-label={HOME_REVIEW_OPS_COPY.sectionTitle} className="border-t border-line/70 py-2">
      <RunSummary run={run} />
    </section>
  );
}

function RunSummary({ run }: { run: ActionWindowRunView }) {
  const needsHuman = run.status === "WAITING_FOR_HUMAN";
  return (
    <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-2">
          <p className="break-keep font-medium text-ink">{resolveCopy(run.runCopyKey, run.runCopyParams)}</p>
          <RunStatusBadge status={run.status} />
        </div>
        <p className="mt-0.5 text-sm text-muted">
          {channelLabel(run.channelCode)} · 진행 {run.progress.completedSteps} / {run.progress.totalSteps} 단계
        </p>
        {needsHuman ? (
          <p className="mt-0.5 break-keep text-sm text-warn">
            <span className="font-semibold">{CHECKPOINT_PROMPT_TITLE}</span>
            {run.currentStep ? ` · ${resolveCopy(run.currentStep.copyKey, run.currentStep.copyParams)}` : ""}
          </p>
        ) : null}
      </div>
      <Open to={needsHuman ? "/connect/imports/current" : "/connect/imports"}>
        {needsHuman ? HOME_REVIEW_OPS_COPY.goToCheckpoint : HOME_REVIEW_OPS_COPY.open}
      </Open>
    </div>
  );
}

function Open({ to, children }: { to: string; children: string }) {
  return (
    <Link
      to={to}
      className="shrink-0 rounded text-sm font-semibold text-brand-700 underline-offset-4 hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700"
    >
      {children}
    </Link>
  );
}
