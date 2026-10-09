import { useEffect, useState } from "react";
import { api } from "../../lib/apiClient";
import type { ReviewAutoCheckView } from "../../lib/types";
import { backendMessage } from "./channelShared";

/**
 * <b>「새 리뷰 자동 확인」 — 켜져 있는 것을 끌 수 있는 자리.</b>
 *
 * <p>연결하면 켜져 있으므로, 이 토글이 하는 일은 사실상 끄는 것이다. 그래서 이 자리에는 동의 문구도, 기기
 * 선택도, 주기 선택도 없다 — 판매자가 고를 것이 하나뿐이면 화면도 하나만 그린다.
 *
 * <p>읽을 수 있는 리뷰 화면이 없는 채널(공식 API로 들어오는 리뷰)에서는 <b>아무것도 그리지 않는다</b>.
 * 「꺼짐」으로 그리면 누군가 끈 것처럼 읽히고, 그런 결정을 한 사람은 없다.
 *
 * <p>멈춤은 설정이 아니다. 도우미가 없으면 다음 확인에서 저절로 풀리고, 로그인 벽은 판매자가 로그인할 때
 * 풀린다 — 둘 다 켜진 상태로 멈춰 있는 것이고, 문장이 그렇게 말한다.
 */
export function ReviewAutoCheckToggle({
  accountId,
  onReport,
}: {
  accountId: string;
  onReport: (message: string, isError: boolean) => void;
}) {
  const [view, setView] = useState<ReviewAutoCheckView | null>(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    let live = true;
    api
      .reviewAutoCheck(accountId)
      .then((v) => {
        if (live) setView(v);
      })
      .catch(() => {
        // 설정을 읽지 못한 것은 설정이 꺼진 것과 다르다. 아무 말도 하지 않는다.
        if (live) setView(null);
      });
    return () => {
      live = false;
    };
  }, [accountId]);

  if (!view?.supported) {
    return null;
  }

  async function toggle() {
    setSaving(true);
    try {
      const next = await api.setReviewAutoCheck(accountId, !view!.enabled);
      setView(next);
      onReport(
        next.enabled
          ? "새 리뷰를 자동으로 확인합니다."
          : "자동 확인을 껐습니다. 필요할 때 「지금 확인」으로 가져올 수 있습니다.",
        false,
      );
    } catch (e) {
      onReport(backendMessage(e) ?? "설정 저장에 실패했습니다. 잠시 후 다시 시도해 주세요.", true);
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="flex flex-col items-start gap-1 md:items-end">
      <button
        type="button"
        data-testid="review-auto-check-toggle"
        disabled={saving}
        onClick={toggle}
        className={`rounded-xl px-4 py-2 text-base font-semibold ${
          view.enabled ? "bg-good/10 text-good" : "bg-canvas text-muted"
        } disabled:cursor-not-allowed disabled:opacity-50`}
      >
        {saving ? "저장 중…" : view.enabled ? "자동 확인 켜짐" : "자동 확인 꺼짐"}
      </button>
      {view.enabled && view.paused ? (
        <p className="break-keep text-sm text-muted">
          {view.paused === "PAUSED_AUTH"
            ? "판매자센터 로그인이 필요해 기다리고 있습니다."
            : "이 컴퓨터의 도우미가 연결되면 이어서 확인합니다."}
        </p>
      ) : null}
    </div>
  );
}
