import { useEffect, useState } from "react";
import { Btn } from "../ui/Btn";
import { api } from "../../lib/apiClient";
import type { ReviewGuidanceOption, ReviewGuidanceOptions } from "../../lib/types";

/**
 * <b>이 리뷰에 맞는 안내</b> (2026-10-27) — 다음 초안이 어떤 안내를 근거로 쓰는지, 판매자가 그 자리에서 정한다.
 *
 * <p><b>왜 여기서 묻나.</b> 판정용 복사본에서 잰 것: 「고객은 이렇게 말해요」로 미리 고른 표현은 처음 보는 리뷰에서 맞게
 * 붙어야 할 43건 중 27건만 찾았고, 처리한 리뷰의 말을 표현으로 더해도 더 찾지 못했다 — 고객은 같은 문제를 매번 다른 말로
 * 쓴다. 그래서 표현이 맞아 붙은 안내는 「확인 전」 후보로 보이고(맞아요 · 아니에요), 붙지 않은 안내는 목록에서 바로
 * 고른다. 그 답은 리뷰마다 남아 다음 평가의 정답이 된다.
 *
 * <p><b>초안을 바꾸지 않는다.</b> 고른 것은 「다시 준비하기」부터 쓰인다 — 판매자가 읽고 있는 문장이 저절로 바뀌지 않는다.
 * 채널로는 아무것도 가지 않는다.
 */
export function ReviewGuidance({
  accountId,
  actionRef,
  onChanged,
}: {
  accountId: string;
  actionRef: string;
  /** A choice was saved — the next draft follows it. */
  onChanged?: () => void;
}) {
  const [options, setOptions] = useState<ReviewGuidanceOptions | null>(null);
  const [failed, setFailed] = useState(false);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [picking, setPicking] = useState(false);
  const [showRejected, setShowRejected] = useState(false);
  const [changed, setChanged] = useState(false);

  useEffect(() => {
    let live = true;
    api
      .getReviewGuidance(accountId, actionRef)
      .then((next) => live && setOptions(next))
      .catch(() => live && setFailed(true));
    return () => {
      live = false;
    };
  }, [accountId, actionRef]);

  async function act(sourceId: string, run: () => Promise<ReviewGuidanceOptions>) {
    setBusy(sourceId);
    setError(null);
    try {
      setOptions(await run());
      setChanged(true);
      onChanged?.();
    } catch {
      setError("저장하지 못했어요 · 다시 시도");
    } finally {
      setBusy(null);
    }
  }
  const choose = (o: ReviewGuidanceOption, choice: "CONFIRMED" | "PICKED" | "REJECTED") =>
    act(o.sourceId, () => api.chooseReviewGuidance(accountId, actionRef, o.sourceId, choice));
  const clear = (o: ReviewGuidanceOption) =>
    act(o.sourceId, () => api.clearReviewGuidance(accountId, actionRef, o.sourceId));

  if (failed) {
    return <p className="text-sm text-muted">이 리뷰에 맞는 안내를 불러오지 못했어요.</p>;
  }
  // 등록된 안내가 하나도 없는 회사에는 고를 것도 물을 것도 없다 — 빈 상자를 그리지 않는다.
  if (!options || (options.attached.length === 0 && options.rejected.length === 0 && options.pickable.length === 0)) {
    return null;
  }

  return (
    <section
      aria-label="이 리뷰에 맞는 안내"
      className="flex flex-col gap-2 rounded-xl border border-line bg-canvas p-3"
      data-testid="review-guidance"
    >
      <div className="flex flex-wrap items-center gap-2">
        <p className="text-sm font-semibold text-ink">이 리뷰에 맞는 안내</p>
        {options.pickable.length > 0 ? (
          <Btn size="sm" variant="ghost" onClick={() => setPicking((v) => !v)} aria-expanded={picking}>
            {picking ? "닫기" : "안내 고르기"}
          </Btn>
        ) : null}
      </div>

      {options.attached.length === 0 ? (
        <p className="break-keep text-sm text-muted">아직 붙은 안내가 없어요. 맞는 안내가 있으면 골라 주세요.</p>
      ) : (
        <ul className="flex flex-col gap-2">
          {options.attached.map((o) => (
            <Row key={o.sourceId} option={o}>
              {o.state === "PHRASE" ? (
                <>
                  <Btn size="sm" variant="outline" disabled={busy !== null} onClick={() => void choose(o, "CONFIRMED")}>
                    맞아요
                  </Btn>
                  <Btn size="sm" variant="ghost" disabled={busy !== null} onClick={() => void choose(o, "REJECTED")}>
                    아니에요
                  </Btn>
                </>
              ) : o.state === "CONFIRMED" ? (
                <Btn size="sm" variant="ghost" disabled={busy !== null} onClick={() => void choose(o, "REJECTED")}>
                  아니에요
                </Btn>
              ) : (
                <Btn size="sm" variant="ghost" disabled={busy !== null} onClick={() => void clear(o)}>
                  빼기
                </Btn>
              )}
            </Row>
          ))}
        </ul>
      )}

      {picking ? (
        <ul className="flex flex-col gap-2 border-t border-line pt-2" aria-label="고를 수 있는 안내">
          {options.pickable.map((o) => (
            <Row key={o.sourceId} option={o}>
              <Btn size="sm" variant="outline" disabled={busy !== null} onClick={() => void choose(o, "PICKED")}>
                고르기
              </Btn>
            </Row>
          ))}
        </ul>
      ) : null}

      {options.rejected.length > 0 ? (
        <div className="flex flex-col gap-1">
          <button
            type="button"
            onClick={() => setShowRejected((v) => !v)}
            aria-expanded={showRejected}
            className="self-start rounded px-1 text-xs text-muted hover:text-ink focus:outline-none focus-visible:ring-2 focus-visible:ring-brand"
          >
            {showRejected ? "▾" : "▸"} 이 리뷰에서 뺀 안내 {options.rejected.length}
          </button>
          {showRejected ? (
            <ul className="flex flex-col gap-2">
              {options.rejected.map((o) => (
                <Row key={o.sourceId} option={o}>
                  <Btn size="sm" variant="ghost" disabled={busy !== null} onClick={() => void clear(o)}>
                    되돌리기
                  </Btn>
                </Row>
              ))}
            </ul>
          ) : null}
        </div>
      ) : null}

      {error ? (
        <p className="break-keep text-sm text-bad" role="alert">
          {error}
        </p>
      ) : null}
      {changed ? (
        <p className="break-keep text-xs text-muted" role="status">
          저장했어요. 「다시 준비하기」를 누르면 이 안내대로 다시 써요.
        </p>
      ) : null}
    </section>
  );
}

/** 안내 한 줄 — 제목, 어디 것인지, 어떻게 붙었는지, 그리고 한 줄 미리보기. */
function Row({ option, children }: { option: ReviewGuidanceOption; children: React.ReactNode }) {
  return (
    <li className="flex items-start gap-3">
      <span className="min-w-0 flex-1">
        <span className="block break-keep text-sm text-ink">
          {option.title}
          <span className="text-xs text-muted"> · {option.scope === "PRODUCT" ? "상품" : "회사 전체"}</span>
        </span>
        <span className="block text-xs text-muted">{stateLine(option)}</span>
        {/* line-clamp은 제 display로 접는다 — block을 같이 주면 접히지 않는다(B37). */}
        {option.snippet ? <span className="line-clamp-2 text-xs text-muted">{option.snippet}</span> : null}
      </span>
      <span className="flex shrink-0 items-center gap-1.5">{children}</span>
    </li>
  );
}

/** 어떻게 붙었는지 — 판매자가 확인하지 않은 것은 확인하지 않았다고 말한다. */
export function stateLine(option: ReviewGuidanceOption): string | null {
  if (option.state === "PHRASE") {
    return option.phrase ? `고객 표현 「${option.phrase}」로 찾음 · 확인 전` : "고객 표현으로 찾음 · 확인 전";
  }
  if (option.state === "CONFIRMED") return "맞다고 확인함";
  if (option.state === "PICKED") return "직접 고름";
  if (option.state === "REJECTED") return "이 리뷰에는 쓰지 않음";
  return null;
}
