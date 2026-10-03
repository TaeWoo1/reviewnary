import { InquiryReply } from "./InquiryReply";
import { useInquiryReply } from "./useInquiryReply";

/**
 * 한 문의의 답변 작업 — 작업대를 스스로 만들고 스스로 그린다.
 *
 * <p>문의 canonical(2026-10-03) 이후 이 화면의 상태는 {@link useInquiryReply}에, 그림은
 * {@link InquiryReply}에 있다. 이 컴포넌트가 남은 이유는 하나다: <b>작업대를 쥘 이유가 없는 호출부</b>가
 * 아직 있고(확인할 일의 pane), 그쪽에서는 예전과 똑같이 work item id 하나만 넘기면 된다.
 *
 * <p>pane 바닥에 행동을 고정하는 화면 — 문의 — 은 훅을 직접 쥐고 {@code InquiryReply}에
 * {@code docked}를 넘긴다. 작업대가 하나이기 때문에 dock의 버튼과 본문의 초안이 같은 상태를 본다.
 */
export function InquiryResponsePanel({ workItemId }: { workItemId: string }) {
  const workspace = useInquiryReply(workItemId);
  return <InquiryReply workspace={workspace} />;
}
