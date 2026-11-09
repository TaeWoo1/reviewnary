import { useEffect, useState } from "react";
import { PageHead } from "../../components/ui/PageHead";
import { Panel } from "../../components/ui/Panel";
import { Btn } from "../../components/ui/Btn";
import { api } from "../../lib/apiClient";
import { backendMessage } from "../../components/connect/channelShared";
import type { ReviewResponseMode, ReviewResponseRuleRequest } from "../../lib/types";

type Band = keyof ReviewResponseRuleRequest;

/**
 * The three bands, each with what a draft written ahead says for it — the composer's rule, in the seller's words — and
 * the choices it offers. Every band offers 「필요할 때만」: the AI judge reads every rating by the same standard
 * (owner, 2026-10-28), so a ★1 it says needs no response is as possible as a ★5 that does.
 */
export const RESPONSE_BANDS: ReadonlyArray<{ band: Band; label: string; note: string; modes: readonly ReviewResponseMode[] }> = [
  {
    band: "low",
    label: "별점 1~2점",
    note: "불만이 담긴 리뷰예요. 고객이 말한 문제를 짚어 사과하고, 등록된 안내가 있으면 함께 써요.",
    modes: ["AHEAD", "ISSUES_ONLY", "ON_REQUEST"],
  },
  {
    band: "mid",
    label: "별점 3점 · 별점 없음",
    note: "칭찬만 있으면 더 따뜻한 감사로, 아쉬운 점이 있으면 그 점을 짚어 써요.",
    modes: ["AHEAD", "ISSUES_ONLY", "ON_REQUEST"],
  },
  {
    band: "high",
    label: "별점 4~5점",
    note: "기본은 AI가 응대가 필요하다고 본 리뷰만 미리 써요. 칭찬만 있는 리뷰는 누를 때 감사 인사로 써요.",
    modes: ["AHEAD", "ISSUES_ONLY", "ON_REQUEST"],
  },
];

export const MODE_WORD: Record<ReviewResponseMode, string> = {
  AHEAD: "미리 쓰기",
  ISSUES_ONLY: "필요할 때만",
  ON_REQUEST: "누를 때 쓰기",
};

/**
 * <b>리뷰 응대 기준 — which reviews get a reply draft before the seller opens them</b> (product-owner, 2026-10-28:
 * 「리뷰에는 응대가 필요한 경우 초안을 두기로. 판매사마다 응대 기준이 다르니까 custom이 돼야」).
 *
 * <p>One choice per rating band and nothing else. What a draft says is not set here — the drafter already writes a
 * thank-you for praise and a reply for a problem — only whether it is written ahead. The page states the three limits
 * the seller would otherwise discover: nothing goes out before approval, only recent reviews are drafted ahead, and
 * drafting ahead never spends more than half of the day's AI budget.
 */
export function ReviewResponseRule() {
  const [rule, setRule] = useState<ReviewResponseRuleRequest | null>(null);
  const [loadError, setLoadError] = useState(false);
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    void api
      .getReviewResponseRule()
      .then((view) => active && setRule({ low: view.low, mid: view.mid, high: view.high }))
      .catch(() => active && setLoadError(true));
    return () => {
      active = false;
    };
  }, []);

  const choose = (band: Band, mode: ReviewResponseMode) => {
    setSaved(false);
    setRule((current) => (current ? { ...current, [band]: mode } : current));
  };

  async function save() {
    if (!rule) return;
    setSaving(true);
    setSaveError(null);
    try {
      const view = await api.saveReviewResponseRule(rule);
      setRule({ low: view.low, mid: view.mid, high: view.high });
      setSaved(true);
    } catch (error) {
      setSaveError(backendMessage(error) ?? "저장하지 못했어요. 잠시 후 다시 시도해 주세요.");
    } finally {
      setSaving(false);
    }
  }

  return (
    <>
      <PageHead
        title="리뷰 응대 기준"
        description="어떤 리뷰에 답글 초안을 미리 써 둘지 별점대마다 정해요. 미리 쓰지 않은 리뷰는 누를 때 써요."
      />
      {loadError ? (
        <Panel title="리뷰 응대 기준">
          <p className="text-warn">응대 기준을 불러오지 못했어요.</p>
        </Panel>
      ) : rule === null ? (
        <Panel title="리뷰 응대 기준">
          <p className="text-muted">불러오는 중…</p>
        </Panel>
      ) : (
        <div className="space-y-4">
          <ul aria-label="리뷰 응대 기준" className="divide-y divide-line/70 overflow-hidden rounded-2xl border border-line bg-surface">
            {RESPONSE_BANDS.map(({ band, label, note, modes }) => (
              <li key={band} className="flex flex-wrap items-center gap-x-6 gap-y-3 px-5 py-4">
                <div className="min-w-[14rem] flex-1">
                  <p className="font-semibold text-ink">{label}</p>
                  <p className="mt-0.5 break-keep text-sm text-muted">{note}</p>
                </div>
                <div role="radiogroup" aria-label={`${label} 초안`} className="inline-flex rounded-lg border border-line p-0.5">
                  {modes.map((mode) => (
                    <button
                      key={mode}
                      type="button"
                      role="radio"
                      aria-checked={rule[band] === mode}
                      onClick={() => choose(band, mode)}
                      className={`min-h-[36px] rounded-md px-3 text-sm font-semibold focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700 ${
                        rule[band] === mode ? "bg-ink text-surface" : "text-muted hover:text-ink"
                      }`}
                    >
                      {MODE_WORD[mode]}
                    </button>
                  ))}
                </div>
              </li>
            ))}
          </ul>
          <ul className="space-y-1.5 break-keep text-sm text-muted">
            <li>미리 쓴 초안은 승인하기 전까지 고객에게 나가지 않아요. 승인하면 그 리뷰가 「대응 필요」로 함께 기록돼요.</li>
            <li>최근 15일 안의 리뷰만 미리 써요. 이미 초안이 있거나 채널에 답글이 달린 리뷰는 건너뛰어요.</li>
            <li>
              「필요할 때만」은 AI가 응대가 필요하다고 본 리뷰만 미리 써요. AI는 별점과 상관없이 모든 리뷰를 같은 기준으로 읽어요. AI
              판단이 꺼져 있거나 실패한 리뷰는 미리 쓰지 않고, 누를 때 써요.
            </li>
            <li>리뷰를 가져오는 즉시 AI가 읽고 초안을 써요. 응대가 필요하다고 본 리뷰부터, 그 안에서는 별점이 낮은 리뷰부터 써요.</li>
            <li>
              미리 쓰기는 하루 AI 사용량의 절반까지만 써요. 별점 4~5점 감사 인사까지 미리 쓰도록 바꿔도 감사 인사는 4분의 1까지만 써서,
              나중에 들어오는 응대 필요 리뷰 몫을 남겨 둬요. 나머지 절반은 직접 누를 때를 위해 남겨 둬요.
            </li>
          </ul>
          <div className="flex flex-wrap items-center gap-3">
            <Btn onClick={save} disabled={saving}>
              {saving ? "저장하는 중…" : "저장"}
            </Btn>
            {saved ? (
              <span className="text-sm text-muted" role="status">
                저장했어요. 다음 자동 확인부터 반영돼요.
              </span>
            ) : null}
            {saveError ? (
              <span className="text-sm text-bad" role="alert">
                {saveError}
              </span>
            ) : null}
          </div>
        </div>
      )}
    </>
  );
}
