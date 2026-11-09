import { useState } from "react";
import type { SellerCheckAnswered } from "../../lib/types";
import { formatAt } from "./SellerCheckPanel";
import { Btn } from "../ui/Btn";

/**
 * 「승인 거두고 고치기」 (2026-11-05 tenth review) — under the hold notice of an approved answer held at registration for
 * something a person must fix (a policy conflict, a forbidden phrase, a changed figure). The same conditions as taking
 * back an answer the record outran: nothing was sent, the channel was read after the approval and showed no answer, and
 * the seller confirms they did not post it by hand. Taking it back resolves nothing — the fixed answer is checked again.
 */
export function HeldTakeBack({
  answered,
  busy,
  onWithdraw,
}: {
  answered: SellerCheckAnswered;
  busy: boolean;
  onWithdraw: () => Promise<boolean>;
}) {
  const [sure, setSure] = useState(false);
  const read = answered.channelReadAt ? formatAt(answered.channelReadAt) : null;
  if (answered.because !== "HOLD") return null;
  if (answered.reopen === "UNCONFIRMED") {
    return (
      <p className="mt-2 break-keep text-sm text-muted" data-testid="held-take-back-unconfirmed">
        이 답변이 채널에 등록됐는지 아직 몰라요. 승인한 뒤로 채널에서 이 문의를 읽지 못했어요
        {read ? ` (마지막 확인: ${read}, 승인 전)` : " (확인 기록 없음)"}. 다음 수집에서 등록된 답변이 없는 것이 확인되면 승인을
        거두고 고칠 수 있어요.
      </p>
    );
  }
  if (answered.reopen === "UNSETTLED") {
    return (
      <p className="mt-2 break-keep text-sm text-muted" data-testid="held-take-back-unsettled">
        이 답변이 등록됐는지 아직 확인하지 못했어요. 등록되지 않은 것이 확인되기 전에는 승인을 거둘 수 없어요.
      </p>
    );
  }
  if (answered.reopen !== "OFFERED") return null;
  return (
    <div className="mt-3" data-testid="held-take-back">
      <p className="break-keep text-base leading-relaxed text-ink">
        승인을 거두면 이 답변을 고칠 수 있어요. 고친 답변은 같은 검사를 다시 받고, 맞지 않는 곳이 남아 있으면 계속 막혀요.
      </p>
      <p className="mt-1 break-keep text-sm text-muted">
        서비스는 이 답변을 등록하지 않았어요 · 승인한 뒤 {read}에 채널에서 확인했을 때 등록된 답변이 없었어요. 그 뒤에 직접 붙여 넣은
        답변은 서비스가 알 수 없어요.
      </p>
      <label className="mt-2 flex items-start gap-2 text-sm text-ink">
        <input
          type="checkbox"
          className="mt-1"
          checked={sure}
          disabled={busy}
          onChange={(e) => setSure(e.target.checked)}
          data-testid="held-take-back-sure"
        />
        <span className="break-keep">판매자센터에 이 답변을 직접 붙여 넣어 등록하지 않았어요.</span>
      </label>
      <div className="mt-2">
        <Btn
          disabled={busy || !sure}
          onClick={() => {
            void onWithdraw().then((ok) => {
              if (ok) setSure(false);
            });
          }}
        >
          {busy ? "승인을 거두는 중…" : "승인 거두고 고치기"}
        </Btn>
      </div>
      <p className="mt-2 break-keep text-sm text-muted">거둔 승인은 기록으로 남아요. 고친 답변은 다시 승인해야 등록돼요.</p>
    </div>
  );
}
