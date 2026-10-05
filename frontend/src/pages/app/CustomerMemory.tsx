import { useCallback, useEffect, useMemo, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { PageHead } from "../../components/ui/PageHead";
import { AgentLaunch } from "../../components/ui/AgentLaunch";
import { useAgentSurface } from "../../lib/agentPanel";
import { Empty } from "../../components/ui/Empty";
import { BtnLink } from "../../components/ui/Btn";
import { FilterTab } from "../../components/ui/FilterTab";
import { QuietSelect } from "../../components/ui/QuietSelect";
import { IssueList } from "../../components/memory/IssueList";
import { api } from "../../lib/apiClient";
import {
  ISSUE_GROUP_LABEL,
  ISSUE_GROUP_ORDER,
  aspectChoices,
  issuesInGroup,
  listProvenanceKo,
  type IssueGroupKey,
} from "../../lib/memoryView";
import { MasterDetail } from "../../components/workspace/MasterDetail";
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
 * count and its two dates in columns. Nothing new is read: every figure on the screen comes from the
 * three endpoints this page already called.
 *
 * <p><b>This screen is the list now</b> (canonical mockup, 2026-10-05 — Sentry issue stream). A row used
 * to open the problem in a 576px pane beside the list, where a reading with a trend, a distribution,
 * eighteen quotes, the library and a decision had to be scrolled inside its own column. A row now opens
 * {@code RepeatedIssue}, the problem's own page at the same address it always had — the same arrangement
 * 리뷰 has, and Sentry's. The list is UNCHANGED: the tabs, the filter, the columns, the row and the
 * totals are the ones that shipped, and the tab and filter ride along in the row's address so ← 반복 문제
 * comes back to the list the seller left. Where that list was scrolled to is {@link MasterDetail}'s to
 * remember.
 *
 * The inbox is no longer read here. It used to be loaded for exactly one purpose — deciding whether
 * an evidence quote was allowed to link anywhere — because the only destination was an inbox page
 * that had to already hold the row. The evidence quote now links to the review's own processing
 * surface, which resolves itself from the review id, so whether a seller can reach the review behind
 * a quote no longer depends on what another screen happened to have fetched.
 */
export function CustomerMemory() {
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

  const visible = useMemo(() => all.filter((issue) => !issue.dismissed), [all]);

  // The two filters live in the URL, so a shared address opens the same list — and so a filter the
  // seller set survives opening a problem and coming back.
  // 행이 들고 가는 주소에 그대로 실린다 — 열어 본 문제에서 ← 반복 문제로 돌아오면 같은 탭, 같은 분류다.
  const query = params.toString();
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

  const head = (
    <PageHead title="반복 문제" action={<AgentLaunch context={{ surface: "memory" }} label="이 내용으로 물어보기" />} />
  );

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
  ) : (
    <>
      {head}
      {controls}
      {shown.length > 0 ? (
        <IssueList
          issues={shown}
          selectedId={null}
          hrefFor={(id) => `/memory/${id}${query ? `?${query}` : ""}`}
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

  /* {@link MasterDetail} draws the list column exactly as it did — its own scroller, its own content
     width — and is handed no detail, because the problem is a page now. Keeping the layout is what makes
     「목록은 freeze」 true at the pixel: nothing about this column moved when the pane left it. */
  return <MasterDetail wide={false} list={list} detail={null} detailLabel="선택한 반복 문제" scrollKey="memory-list" />;
}

const ALL_ASPECTS = "ALL";

function parseGroup(value: string | null): IssueGroupKey | null {
  return ISSUE_GROUP_ORDER.find((key) => key === value) ?? null;
}
