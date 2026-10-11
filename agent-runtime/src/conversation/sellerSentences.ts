/**
 * **What the seller reads.** The conversation lane's closed sentences, labels and the pure text
 * helpers that assemble them — lifted out of `ConversationService.ts` on 2026-10-12.
 *
 * <p>Why its own file: changing one seller-facing sentence used to mean editing a 4,710-line runtime
 * file, so the smallest possible product change carried the largest possible blast radius. Nothing
 * here reads state, calls the backend or decides anything — every function is pure, and every
 * constant is a literal. A wording change is a change in this file and nowhere else.
 *
 * <p>This file sits directly under `src/conversation/`, which is deliberate: the conversation write
 * fence walks that tree and would not see it from a subdirectory until 2026-10-12 — and the habit of
 * putting lane code where the fence can reach it is cheaper than remembering why. See
 * `docs/FINDINGS.md` §B10.
 */
import type { CapabilityAspect } from "../operator/capability/ProductSelfKnowledge";
import type { ChannelCapabilityVerdict } from "../operator/capability/ChannelCapability";
import { EXECUTION_REASON } from "../operator/capability/ChannelCapability";
import { INTERNAL_TOKEN } from "../operator/wording/sellerWording";
import type { InquiryActionability } from "./inquiryActionability";

/** The honest sentence for an object the backend could not bind to a marketplace record. */
export const NOT_EXECUTABLE_SENTENCE: Record<"INQUIRY" | "REVIEW", string> = {
  INQUIRY: "이 문의는 파일로 가져온 기록이라 채널로 보낼 수 없습니다.",
  REVIEW: "이 리뷰는 파일로 가져온 기록이라 채널로 보낼 수 없습니다.",
};
export const COPY_ONLY_SENTENCE = "초안을 복사해 직접 등록해 주세요.";
export const COUPANG_REVIEW_UNSUPPORTED_SENTENCE = "쿠팡에서는 판매자가 리뷰에 직접 답글을 남기는 기능을 지원하지 않습니다.";
/** What the thread says where a stopped turn would have answered. Never a claim about what was found. */
export const CANCELLED_MESSAGE = "요청을 중지했습니다. 이미 시작된 확인은 되돌리지 않습니다.";
export function won(amount: number): string {
  return `${Math.trunc(amount).toLocaleString("ko-KR")}원`;
}

export function dedupe(values: readonly string[]): string[] {
  return [...new Set(values)];
}
/**
 * Sentences that say the same limit twice in two shapes (the resolver's 「"이 상품"에 해당하는 상품을 찾지
 * 못했습니다」 and the scope gate's 「「이 상품」에 해당하는 상품을 찾지 못해, …」) are one fact: the first
 * form is kept (Response Hygiene v1 §6). Exact repeats collapse as before.
 */
export const NEAR_KEYS: ReadonlyArray<RegExp> = [/에 해당하는 상품을 찾지 못/, /저장된 과거 답변/, /등록된 회사 정보/];
export function dedupeNear(sentences: readonly string[]): string[] {
  const out: string[] = [];
  const seen = new Set<string>();
  const seenKey = new Set<number>();
  for (const entry of sentences) {
    // <b>Deduped sentence by sentence, not entry by entry.</b> A headline and a note can carry the same
    // fact in differently-sized strings — 「어떤 상품을 묻는지 확인하지 못했습니다. 상품명이나 SKU를 함께
    // 알려주세요.」 arrived once as one entry and once as two, and whole-string equality saw no repeat, so
    // the seller read the same two sentences twice with an unrelated line wedged between them.
    const kept: string[] = [];
    for (const one of entry.split(/(?<=[.!?])\s+/).map((x) => x.trim()).filter((x) => x.length > 0)) {
      if (seen.has(one)) continue;
      const key = NEAR_KEYS.findIndex((re) => re.test(one));
      if (key >= 0) {
        if (seenKey.has(key)) continue;
        seenKey.add(key);
      }
      seen.add(one);
      kept.push(one);
    }
    if (kept.length > 0) out.push(kept.join(" "));
  }
  return out;
}
/** The seller-facing refusal when the draft path itself failed. Deterministic; the backend's text never travels. */
export const DRAFT_FAILED_SENTENCE = "초안을 준비하는 중 문제가 생겨 이번에는 만들지 못했습니다. 잠시 후 다시 시도해 주세요.";
export const NO_DRAFT_TO_REVISE_SENTENCE = "말투를 바꿀 초안을 찾지 못했습니다. 먼저 「답변 준비해줘」로 초안을 만들어 주세요.";

export const STATUS_WORD: Record<string, string> = { UNANSWERED: "답변 필요", ANSWERED: "답변함" };

/** What an INSPECT says after naming the row — closed per actionability, never a backend message. */
export const INSPECT_SENTENCE: Record<InquiryActionability, string> = {
  DRAFTABLE: "「답변 준비해줘」라고 하시면 이 문의의 초안을 준비합니다.",
  ALREADY_ANSWERED: "이미 답변된 문의입니다.",
  AWAITING_SEND: "승인된 답변이 전송을 기다리고 있는 문의입니다.",
  NOT_WORKABLE: "지금은 답변 준비 대상이 아닌 문의입니다.",
};
/** 「쿠팡에서는 …」 — the honest sentence for a channel with no seller reply flow; the chips are the next moves. */
export function summaryLineFor(channelCode: string | null): string {
  return (channelCode ?? "").toUpperCase() === "COUPANG"
    ? COUPANG_REVIEW_UNSUPPORTED_SENTENCE
    : "이 채널에서는 판매자가 리뷰에 직접 답글을 남기는 기능을 지원하지 않습니다.";
}
export const PRODUCT_KNOWLEDGE_GAP_SENTENCE =
  "현재 확인된 제품 정보만으로는 이 질문에 정확히 답할 수 없습니다.";
export const ASPECT_TITLE: Readonly<Record<CapabilityAspect, string>> = {
  PRODUCT_OVERVIEW: "제가 도와드릴 수 있는 일",
  SUPPORTED_CHANNELS: "연결할 수 있는 판매 채널",
  AFTER_CONNECT: "연결한 뒤에 하는 일",
  CHANNEL_ACTION: "채널별로 되는 것과 안 되는 것",
  HOW_TO_CONNECT: "시작하는 방법",
  // The deterministic fallback draws the overview card for these two — the grounded lane is what
  // answers them, and a card titled 「앞으로의 방향」 built from CURRENT truth would be a worse answer
  // than the honest overview.
  PRODUCT_DIFFERENCE: "제가 도와드릴 수 있는 일",
  FUTURE_DIRECTION: "제가 도와드릴 수 있는 일",
  COLLECTION_STATE: "제가 도와드릴 수 있는 일",
  DAILY_OPERATION: "제가 도와드릴 수 있는 일",
  TEAM_ACCESS: "제가 도와드릴 수 있는 일",
  SECURITY_AND_DATA: "제가 도와드릴 수 있는 일",
};
/** The audited transport's own sentence, or the closed reason said in the seller's words. Never a vendor message. */
export function executionReasonSentence(verdict: ChannelCapabilityVerdict, channelName: string, what: string): string {
  if (verdict.reasonKo) return verdict.reasonKo;
  switch (verdict.reason) {
    case EXECUTION_REASON.EXECUTION_DISABLED:
      return `${channelName} ${what} 전송이 이 배포에서는 아직 켜져 있지 않습니다.`;
    case "PLATFORM_SUPPORTED_NOT_IMPLEMENTED":
      return `${channelName}에는 ${what}을 보낼 수 있는 공식 경로가 있지만 reviewnary가 아직 지원하지 않습니다.`;
    case "UNSUPPORTED":
    case EXECUTION_REASON.CHANNEL_UNSUPPORTED:
      return `${channelName}에서는 이 ${what}을 보낼 방법이 없습니다.`;
    case "GUIDED_ACTION":
      return `${channelName}에서는 판매자님이 직접 등록하는 방식으로만 ${what}을 보낼 수 있습니다.`;
    default:
      return `${channelName} ${what} 전송 가능 여부를 아직 확인하지 못했습니다.`;
  }
}
/**
 * Why the guided step is not on offer, in this surface's register — from the SERVER's closed reason.
 *
 * The reason is decided in exactly one place (`ReviewReplyService`, the same rule the mint applies)
 * and said in two, because a chat sentence and a panel line are not the same register. Neither prints
 * the token, and an unknown reason gets the honest fallback rather than a guess.
 */
export function guidedUnavailableSentence(reason: string | null, channelName: string): string {
  switch (reason) {
    case "CHANNEL_ALREADY_ANSWERED":
      return "이 리뷰에는 채널에 이미 답변이 등록돼 있어, 답변이 두 번 달리지 않도록 안내를 시작하지 않습니다.";
    case "SOURCE_NOT_EXECUTABLE":
      return `이 리뷰는 ${channelName} 판매자센터 화면에서 찾아 드릴 수 없어, 안내를 시작할 수 없습니다.`;
    default:
      return `지금은 ${channelName} 판매자센터 안내를 시작할 수 없습니다.`;
  }
}
export const NUMBER = /\d[\d,]*/g;
/**
 * A finding's statement as the seller reads it (Response Hygiene v1 §3): the registered 회사 정보 is
 * referred to, not read back, unless the company itself was the question; a statement that carries an
 * internal token is not shown at all (the trace still has it).
 */
export function sellerSentence(statement: string, aboutCompany: boolean): string | null {
  if (statement.startsWith("회사 정보에는 이렇게 등록돼 있습니다")) {
    return aboutCompany ? statement : "등록된 회사 정보를 참고했습니다.";
  }
  if (INTERNAL_TOKEN.test(statement)) return null;
  return statement;
}

/** Same numbers (as a subset) and the same noun ⇒ the finding restates the headline. */
export function redundantWithHeadline(statement: string, headline: string): boolean {
  if (statement.trim() === headline.trim()) return true;
  const nums = statement.match(NUMBER) ?? [];
  if (nums.length === 0) return false;
  const headNums = new Set(headline.match(NUMBER) ?? []);
  if (!nums.every((n) => headNums.has(n))) return false;
  return ["문의", "리뷰", "주문", "매출", "상품"].some((noun) => statement.includes(noun) && headline.includes(noun));
}
export const FACT_LABEL: Partial<Record<string, string>> = {
  ISSUE_EVIDENCE: "리뷰 문제 근거", NEGATIVE_REVIEW: "부정 리뷰", REVIEW_ISSUE: "반복 리뷰 문제",
  INQUIRY: "미답변 문의", PRODUCT_SIGNAL: "신호", REVIEW_LIST: "리뷰", CUSTOMER_MEMORY: "과거 사례",
};
