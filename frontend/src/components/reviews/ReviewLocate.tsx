import { useEffect, useRef } from "react";
import { Btn } from "../ui/Btn";
import { Disclosure } from "../ui/Disclosure";
import { josa } from "./recordParts";
import { locateMessage, locateUnavailableText } from "../../lib/actionWindow/locate/locateCopy";
import { useReviewLocate } from "../../lib/actionWindow/locate/useReviewLocate";
import type { ReviewChannelCapabilityView, TriageBehaviorEvent } from "../../lib/types";

/**
 * <b>원문은 어디서 보나 — 한 상품평에 대해 판매자가 SellerOps에게 시킬 수 있는 유일한 일.</b>
 *
 * <p>쿠팡은 상품평마다 주소를 공개하지 않는다. 그래서 이것은 하이퍼링크가 아니고 될 수도 없다 — 판매자가
 * 열어 둔 화면에서 그 줄을 다시 찾아 테두리를 그린다. 뒤에 Action Window run이 있고, 그 run은 할 말이 있다.
 *
 * <p><b>왜 자기 파일로 나왔나</b> (리뷰 canonical mockup, 2026-10-05). 이 블록은 리뷰 기록의 440px pane 안에
 * 있었고, 그 pane은 없어졌다. 같은 능력이 같은 말로 리뷰 처리 화면에 서야 하므로 — 두 벌로 갈라지지 않게 —
 * 한 군데에 두고 양쪽이 부른다.
 *
 * <p>계약 §1: locate 화면이 있는 채널에서만 그린다. 네이버·카페24 계정에는 「쿠팡에서 보기」가 아예 없고,
 * 화면은 그 사실을 한 번 말한다 — 눌러도 서버가 거절할 단추를 두는 대신.
 */
export function ReviewLocate({
  reviewId,
  accountId,
  word,
  capability,
  pilotOn,
  raised,
  recordBehavior,
  className,
}: {
  reviewId: string;
  /** The account the run is minted against. Empty when the review has none — then nothing is drawn. */
  accountId: string | null;
  word: string;
  capability: ReviewChannelCapabilityView | null;
  pilotOn: boolean;
  /** Rows something raised — the pilot's mark or a rules 확인 필요. Only those are worth a trace. */
  raised: boolean;
  recordBehavior: (events: TriageBehaviorEvent[]) => void;
  className?: string;
}) {
  const locate = useReviewLocate(accountId ?? "");
  const mine = locate.reviewId === reviewId;
  const run = mine ? locate.view : null;
  const running = mine && locate.starting;
  const unavailable = mine ? locate.unavailable : null;
  const message = locateMessage(run, running);
  const canLocate = capability?.originalLocate === "LOCATE_RUN" && !!accountId;

  // MARKETPLACE_LOCATED — contract §2.1: the run REPORTED the row found (COMPLETED), once per run, not on
  // the press. The press is ORIGINAL_OPENED; the two are different facts and are recorded as two.
  const locatedRunRef = useRef<string | null>(null);
  useEffect(() => {
    if (!pilotOn || !raised || run === null || run.status !== "COMPLETED") return;
    const key = `${reviewId}:${run.runId}`;
    if (locatedRunRef.current === key) return;
    locatedRunRef.current = key;
    recordBehavior([{ reviewId, kind: "MARKETPLACE_LOCATED" }]);
  }, [pilotOn, raised, run, reviewId, recordBehavior]);

  // Offered only when the RUNTIME says it is allowed. A recheck the run would refuse is a button that does
  // nothing, and on a block whose whole job is to be honest about what was found that is the wrong button.
  const canRecheck = run?.allowedCommands.includes("REQUEST_STEP_RECHECK") ?? false;
  const canRaise = run?.allowedCommands.includes("FIND_CURRENT_STEP") ?? false;

  if (!canLocate) {
    // Folded, because it answers 「원문은 어디서 보나」 — and a seller who has not asked it reads a
    // 56-character apology for a control that is not there. The label is the question.
    return (
      <Disclosure className={className} label={`${word} 원문 보기`}>
        <p className="pt-1 text-sm leading-relaxed text-muted">
          이 채널의 {josa(word, "은", "는")} reviewnary에서 원문 화면으로 바로 이동할 수 없습니다. 판매자센터에서 직접 확인해 주세요.
        </p>
      </Disclosure>
    );
  }

  return (
    <div className={`space-y-2 ${className ?? ""}`}>
      <Btn
        size="sm"
        variant="outline"
        disabled={running}
        onClick={() => {
          // Silver: the seller asked for the original. Recorded only for rows something raised.
          if (pilotOn && raised) recordBehavior([{ reviewId, kind: "ORIGINAL_OPENED" }]);
          void locate.locate(reviewId);
        }}
      >
        쿠팡에서 보기
      </Btn>
      <p className="text-sm leading-relaxed text-muted">
        쿠팡 윙의 상품평 목록 화면을 띄워 두시면, 이 상품평이 있는 줄에 테두리를 그려 드립니다. 쿠팡 화면에서는
        아무것도 눌리거나 입력되지 않습니다.
      </p>
      {unavailable ? <p className="text-sm leading-relaxed text-ink">{locateUnavailableText(unavailable)}</p> : null}
      {message ? (
        <div className="space-y-2">
          <p className={`text-sm leading-relaxed ${message.tone === "done" || message.tone === "failed" ? "text-ink" : "text-muted"}`} role="status">
            {message.text}
          </p>
          {canRecheck || canRaise ? (
            <div className="flex flex-wrap gap-2">
              {canRecheck ? (
                <Btn size="sm" variant="outline" onClick={() => locate.send("REQUEST_STEP_RECHECK")}>
                  다시 확인
                </Btn>
              ) : null}
              {canRaise ? (
                <Btn size="sm" variant="ghost" onClick={() => locate.send("FIND_CURRENT_STEP")}>
                  쿠팡 창 앞으로
                </Btn>
              ) : null}
            </div>
          ) : null}
        </div>
      ) : null}
    </div>
  );
}
