import { useCallback, useEffect, useMemo, useState } from "react";
import { Link, useParams, useSearchParams } from "react-router-dom";
import { PageHead } from "../../components/ui/PageHead";
import { AgentLaunch } from "../../components/ui/AgentLaunch";
import { useAgentSurface } from "../../lib/agentPanel";
import { Empty } from "../../components/ui/Empty";
import { Btn, BtnLink } from "../../components/ui/Btn";
import { FilterTab } from "../../components/ui/FilterTab";
import { QuietSelect } from "../../components/ui/QuietSelect";
import { IssueList } from "../../components/memory/IssueList";
import { IssueReading } from "../../components/memory/IssueReading";
import { useRepeatedIssue } from "../../components/memory/useRepeatedIssue";
import { api } from "../../lib/apiClient";
import { kstDate } from "../../lib/format";
import { nextActionKo } from "../../lib/reviewIssuesView";
import {
  ISSUE_GROUP_LABEL,
  ISSUE_GROUP_ORDER,
  aspectChoices,
  issuesInGroup,
  lifecycleSinceKo,
  listProvenanceKo,
  resolveIssueSelection,
  type IssueGroupKey,
} from "../../lib/memoryView";
import { MasterDetail, useWideLayout } from "../../components/workspace/MasterDetail";
import type { ReviewIssueView } from "../../lib/types";

/**
 * 반복 문제 (was 고객운영 메모리) — what keeps coming back, and the evidence for it. One name since UI/UX v2: the
 * Home's section, the nav entry and this page's title all say 반복 문제.
 *
 * SCOPE FENCE (v1): recurring issues, their evidence, their trend, and per-product signals. There
 * is NO search input, by decision — search over past inquiries, reviews and replies is
 * retrieval-backed work outside v1 and gated on a separate scope decision. Rendering a search box
 * before that capability exists would promise it. `memoryScope.test.tsx` holds this fence.
 *
 * <p><b>Canonical (2026-10-03).</b> The three groups became the list's tabs, so 「확인 필요 0」 can be
 * stated instead of a heading silently not appearing; the row is one line with its state, its evidence
 * count and its two dates in columns; and the pane is the Decision Workspace width with the one action
 * docked at its floor. Nothing new is read: every figure on the screen comes from the three endpoints
 * this page already called.
 *
 * The inbox is no longer read here. It used to be loaded for exactly one purpose — deciding whether
 * an evidence quote was allowed to link anywhere — because the only destination was an inbox page
 * that had to already hold the row. The evidence quote now links to the review's own processing
 * surface, which resolves itself from the review id, so whether a seller can reach the review behind
 * a quote no longer depends on what another screen happened to have fetched.
 */
export function CustomerMemory() {
  const { issueId } = useParams();
  const [params, setParams] = useSearchParams();
  const [issues, setIssues] = useState<ReviewIssueView[] | null>(null);
  const [loading, setLoading] = useState(true);
  const [failed, setFailed] = useState(false);

  // The same conversation every other screen opens — the panel, not a second chat (Reports v1 §3).
  // ONLY THE SCREEN TRAVELS. The label deliberately does not name the opened issue: `AgentContext`
  // has no issue field, so a header saying 「접착 부족」 would promise a scope nothing carries, and
  // the follow-up would be answered by whatever problem the sentence itself names.
  useAgentSurface({ surface: "memory", label: "반복 문제" });

  const load = useCallback(async () => {
    setLoading(true);
    setFailed(false);
    try {
      setIssues(await api.getReviewIssuesStrict());
    } catch {
      setIssues(null);
      setFailed(true);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const all = issues ?? [];
  const wide = useWideLayout();

  const onIssueChanged = useCallback((next: ReviewIssueView) => {
    setIssues((current) =>
      current ? current.map((issue) => (issue.id === next.id ? next : issue)) : current,
    );
  }, []);

  const visible = useMemo(() => all.filter((issue) => !issue.dismissed), [all]);

  // The two filters live in the URL, so a shared address opens the same list — and so a filter the
  // seller set survives opening a problem and coming back.
  const group = parseGroup(params.get("group"));
  const aspect = params.get("aspect");
  const aspects = useMemo(() => aspectChoices(visible), [visible]);
  const shown = useMemo(() => {
    const inGroup = group ? issuesInGroup(visible, group) : visible;
    return aspect ? inGroup.filter((issue) => issue.aspect === aspect) : inGroup;
  }, [visible, group, aspect]);

  const setFilter = useCallback(
    (next: { group?: IssueGroupKey | null; aspect?: string | null }) => {
      const updated = new URLSearchParams(params);
      for (const [key, value] of Object.entries(next)) {
        if (value) updated.set(key, value);
        else updated.delete(key);
      }
      setParams(updated, { replace: true });
    },
    [params, setParams],
  );

  // Master-detail (UI/UX v2 Phase 1): on a wide screen the first problem is open when none is chosen. The old
  // first screen was two thirds 「왼쪽에서 이슈를 고르면…」 — an empty panel asking for a click before it said
  // anything. The list order is the server's (worst first), so 「first」 is not a new ranking.
  // The default is pinned once chosen: acting on a problem can move it to another group, and a default recomputed
  // from the new order would swap the pane to a different problem under the seller's cursor.
  const [pinned, setPinned] = useState<string | null>(null);
  const firstId = shown[0]?.id ?? null;
  useEffect(() => {
    if (wide && !issueId && pinned === null && firstId) setPinned(firstId);
  }, [wide, issueId, pinned, firstId]);
  const selection = resolveIssueSelection(all, issueId ?? (wide ? pinned ?? firstId ?? undefined : undefined));
  const found = selection.kind === "FOUND" ? selection.issue : null;

  // The pane's two reads and its one write, owned here because the action is docked below the scroller.
  const workspace = useRepeatedIssue(found, onIssueChanged);

  const head = (
    <PageHead title="반복 문제" action={<AgentLaunch context={{ surface: "memory" }} label="이 내용으로 물어보기" />} />
  );

  const detail =
    found ? (
      <IssueReading key={found.id} issue={found} workspace={workspace} docked />
    ) : selection.kind === "MISSING" ? (
      <div>
        <p className="break-keep font-semibold text-ink">이 문제를 찾을 수 없습니다</p>
        <p className="mt-2 break-keep text-sm leading-relaxed text-muted">
          목록에서 다시 선택해 주세요. 기록이 정리되면서 문제가 합쳐졌거나 바뀌었을 수 있습니다.
        </p>
      </div>
    ) : null;

  const controls = (
    <div className="flex flex-wrap items-end justify-between gap-x-6 gap-y-2 border-b border-line">
      <div className="flex flex-wrap items-center gap-6" role="group" aria-label="상태 필터">
        {ISSUE_GROUP_ORDER.map((key) => (
          <FilterTab
            key={key}
            pressed={group === key}
            onClick={() => setFilter({ group: key })}
          >
            {ISSUE_GROUP_LABEL[key]} {issuesInGroup(visible, key).length}
          </FilterTab>
        ))}
        <FilterTab pressed={group === null} onClick={() => setFilter({ group: null })}>
          전체 {visible.length}
        </FilterTab>
      </div>
      {aspects.length > 1 ? (
        <div className="flex flex-wrap items-center gap-4 pb-2">
          <QuietSelect
            label="분류"
            value={aspect ?? ALL_ASPECTS}
            options={[
              { value: ALL_ASPECTS, label: "전체 분류" },
              ...aspects.map((value) => ({ value, label: value })),
            ]}
            onChange={(value) => setFilter({ aspect: value === ALL_ASPECTS ? null : value })}
          />
        </div>
      ) : null}
    </div>
  );

  const list = loading ? (
    <>
      {head}
      <p className="text-muted">불러오는 중…</p>
    </>
  ) : failed ? (
    <>
      {head}
      <Empty
        title="기록을 불러오지 못했습니다"
        body="연결 상태를 확인한 뒤 다시 시도해 주세요."
        action={<BtnLink to="/connect">채널 연결 확인</BtnLink>}
      />
    </>
  ) : visible.length === 0 ? (
    <>
      {head}
      <Empty
        title="아직 쌓인 기록이 없습니다"
        body="문의와 리뷰가 모이면, 같은 문제가 몇 번 반복됐는지와 무엇을 근거로 그렇게 보는지를 여기에서 확인합니다."
        action={<BtnLink to="/connect">채널 연결하기</BtnLink>}
      />
    </>
  ) : !wide && issueId ? (
    // Narrow: the chosen problem takes the column, with the way back to the list above it.
    <>
      {head}
      <Link to="/memory" className="text-sm font-semibold text-muted hover:text-ink hover:underline">
        ← 반복 문제 목록
      </Link>
      {found ? <IssueReading key={found.id} issue={found} workspace={workspace} /> : detail}
    </>
  ) : (
    <>
      {head}
      {controls}
      {shown.length > 0 ? (
        <IssueList
          issues={shown}
          selectedId={found?.id ?? null}
          hrefFor={(id) => (wide ? `/memory/${id}?${params.toString()}` : `/memory/${id}`)}
        />
      ) : (
        <p className="text-muted">이 조건에 해당하는 문제가 없습니다.</p>
      )}
      {/* Totals and the honest provenance below the work, never above it. */}
      <div className="flex flex-wrap items-baseline justify-between gap-x-6 gap-y-1 border-t border-line pt-3 text-xs text-muted">
        <div className="flex flex-wrap items-baseline gap-4 tabular-nums">
          <span className="font-semibold text-ink">총 {visible.length}개</span>
          <span>심각 {visible.filter((issue) => issue.severity === "HIGH").length}개</span>
          <span>조치 중 {visible.filter((issue) => issue.lifecycleState === "ACTING").length}개</span>
        </div>
        <p className="min-w-0 break-keep leading-relaxed">{listProvenanceKo(visible)}</p>
      </div>
    </>
  );

  // One action, at the floor of the pane — reachable however far the reading is scrolled. Absent in the
  // three states where the next move is reviewnary's, which is also where `nextActionKo` returns null.
  const actionLabel = found ? nextActionKo(found.lifecycleState) : null;
  const dock =
    found && actionLabel ? (
      <div className="-mb-6 flex items-center justify-between gap-4 border-t border-line bg-surface pb-6 pt-4">
        <p className="min-w-0 break-keep text-xs tabular-nums text-muted">
          {lifecycleSinceKo(found, workspace.detail?.history ?? null, kstDate)}
        </p>
        <Btn size="sm" onClick={workspace.submit} disabled={workspace.busy}>
          {workspace.busy ? "기록 중…" : actionLabel}
        </Btn>
      </div>
    ) : null;

  return (
    <MasterDetail
      wide={wide}
      pane="decision"
      list={list}
      detailLabel="선택한 반복 문제"
      detail={visible.length > 0 ? detail : null}
      preview
      paneFooter={dock}
    />
  );
}

const ALL_ASPECTS = "ALL";

function parseGroup(value: string | null): IssueGroupKey | null {
  return ISSUE_GROUP_ORDER.find((key) => key === value) ?? null;
}
