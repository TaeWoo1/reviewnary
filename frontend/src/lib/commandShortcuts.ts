import { api } from "./apiClient";
import { INTENT_HEADING, matchCommandIntent } from "./commandIntents";
import { INQUIRY_NEEDS_REPLY_PATH } from "./todayInbox";
import type { ConversationState } from "./conversation/ConversationProvider";
import type { InquiryListArtifact } from "./conversation/types";

/**
 * <b>The exact shortcuts, answered locally — wherever the box is</b> (moved out of `AgentHome`, 2026-10-28).
 *
 * <p>A sentence that is exactly one of the {@link matchCommandIntent} labels answers with an object the app already
 * has, and never reaches the runtime. This lived inside the Home's box; when the box moved into the AI 담당자 panel
 * (owner, 2026-10-28), the panel's box would otherwise have sent 「미답변 문의 보여줘」 to the planner — the same
 * sentence, a different product. Both boxes now call this one function.
 *
 * @returns true when the sentence was a shortcut and has been answered (or is being answered); false to send it on.
 */
export function answerCommandIntent(
  text: string,
  conversation: Pick<ConversationState, "addLocalTurn">,
): boolean {
  const key = matchCommandIntent(text);
  if (!key) return false;
  if (key === "TODAY") {
    conversation.addLocalTurn(text, { message: "오늘 할 일은 할 일 화면 맨 위에 정리해 두었어요.", artifacts: [] });
    return true;
  }
  // 「답변이 필요한 문의」 is ONE question, so the heading, the count, the rows and the destination all come from ONE
  // read. It used to title the answer with the freshness-qualified KPI and fill it with the OPEN work queue — two
  // different sets under one sentence, so the number never described the rows beneath it and neither described the
  // screen the 전체 보기 opened.
  void api
    .getInquiryRowsStrict({ status: "UNANSWERED", order: "OLDEST", limit: 5 })
    .then((page) => {
      const artifact: InquiryListArtifact = {
        artifactId: "local-inquiries",
        type: "INQUIRY_LIST",
        title: `${INTENT_HEADING.UNANSWERED_INQUIRIES} ${page.totalCount}건`,
        totalCount: page.totalCount,
        groups: [
          {
            key: "UNANSWERED",
            label: "답변 필요",
            items: page.items.map((item) => ({
              workItemId: item.workItemId,
              inquiryId: item.inquiryId,
              channelCode: item.channelCode,
              channelNameKo: item.channelNameKo,
              receivedAt: item.receivedAt,
              phase: item.phase,
              status: item.status,
              title: item.title,
              productId: item.productId,
              productName: item.productName,
              answerBasis: null,
              to: `/inquiries/${item.inquiryId}`,
            })),
          },
        ],
        more: { label: "문의 화면에서 전체 보기", to: INQUIRY_NEEDS_REPLY_PATH },
      };
      conversation.addLocalTurn(text, { message: "바로 보여드려요.", artifacts: [artifact] });
    })
    .catch(() => conversation.addLocalTurn(text, { message: "문의를 읽지 못했어요. 문의 화면에서 확인해 주세요.", artifacts: [] }));
  return true;
}
