/**
 * 「이미 답변 완료」 — the channel's own statement, said before anyone decides anything.
 *
 * <p><b>It is not the triage decision and it does not make one.</b> The controls below are untouched:
 * a review answered on the channel may still be one this operator has never looked at, and writing
 * 「처리 완료」 here would record a decision nobody made. What it does say is that the road to a
 * SECOND public reply is closed — new draft, new approval and the guided run are all withheld by the
 * server (`canSave` / `canApprove` / `canStartSubmissionRun`) and refused by it — so this is a
 * sentence about a real closure rather than a warning the product then walks past.
 *
 * <p>Rendered for ANSWERED alone. 「아직 답변이 없습니다」 would be the channel's silence dressed as a
 * statement: UNKNOWN means no usable answer, which is a different fact from PENDING.
 */
export function ChannelAnsweredState({ state }: { state: string | null }) {
  if (state !== "ANSWERED") return null;
  return (
    /* <b>면이 아니라 사실이다</b> (UI audit, 2026-10-06). 회색 면을 두른 상자였고, 바로 아래 준비된
       답변이 이 화면에서 면을 가진 단 하나의 물건이라는 계약과 겨뤘다 — 두 개의 면은 어느 쪽이 지금
       다룰 것인지를 말하지 못한다. 문장 둘은 그대로이고, 가는 선 하나가 구역을 연다. */
    <section className="border-t border-line pt-3" data-testid="channel-answered-state">
      <p className="break-keep text-sm font-semibold text-ink">채널에 이미 답글이 달린 리뷰예요</p>
      {/* 「판매자센터에 답변이 있다고 채널이 알려왔습니다」 opened this paragraph and is gone — it is the
          line above it in other words, and the line above it is the heavier of the two. What is kept is
          the part a title cannot carry: which doors that closes, and where the seller may still write. */}
      <p className="break-keep text-sm leading-relaxed text-muted">
        {/* The channel tells us THAT it is answered, never with what — the text is not carried (2026-10-28: the
            walk-through looked for it), so the line says where it is. */}
        새 답글은 쓰거나 확정하지 않아요. 달린 답글은 판매자센터에서 볼 수 있고, 처리 상태는 아래에서 기록할 수 있어요.
      </p>
    </section>
  );
}
