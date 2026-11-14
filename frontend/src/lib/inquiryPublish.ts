// The pure decisions behind the inquiry reply-publish surface. No React, no I/O.
//
// This is the seller-facing side of the ONE marketplace WRITE this product has, so every rule that
// decides whether a send control exists at all lives here, in one readable place, rather than being
// spread across a component's JSX. The component renders what these say; it decides nothing.

import type { InquiryDetail, PublishCapabilityView, PublishOutcomeCategory, PublishStatusView } from "./types";

/**
 * Whether the "채널에 답변 등록" path may be offered for THIS inquiry.
 *
 * Three conditions, and all three are the backend's own answers rather than this screen's guesses:
 *
 *  1. **the deployment can send at all** — `executionEnabled`, which is `sellerops.inquiry.publish
 *     .execution-enabled` and is false by default. Off, no adapter bean exists and the core fails
 *     closed, so a send control would be a button that cannot work;
 *  2. **this channel has a reply adapter** — `replyAdapterChannelCodes`. Coupang's additionally
 *     requires its own connector flag, so "we can send" and "we can send HERE" are different facts
 *     and the capability read reports them separately;
 *  3. **the inquiry belongs to that channel** — an inquiry whose channel is not in the list is
 *     answered in the seller center, and saying otherwise would promise a path that 404s at dispatch.
 *
 * Fails CLOSED on a capability read that did not land: `null` means "we do not know", and offering a
 * marketplace write on a guess is the one thing this surface must never do.
 */
export function canPublishReply(
  detail: Pick<InquiryDetail, "channelCode" | "status">,
  capability: PublishCapabilityView | null,
  options: { allowSimulated?: boolean } = {},
): boolean {
  // Already answered on the marketplace, by anyone. The backend refuses this at the last gate before
  // the write (ALREADY_ANSWERED), so offering the button here would walk the seller through approval
  // only to fail — and if a stale AI draft is sitting there, it invites them to answer a customer
  // twice. Checked first because no capability makes a second answer correct.
  if (answeredElsewhere(detail)) return false;
  if (!capability) return false;
  // Real execution off and no mock asked for: nothing to read further (the pre-2026-11-09 order, kept so a capability of
  // an unexpected shape still answers 「no」 instead of throwing).
  if (capability.executionEnabled !== true && !options.allowSimulated) return false;
  const code = detail.channelCode;
  const adapters = Array.isArray(capability.replyAdapterChannelCodes) ? capability.replyAdapterChannelCodes : [];
  if (!code || !adapters.includes(code)) return false;
  if (capability.executionEnabled === true) return true;
  // A development stand-in (2026-11-09, demo §7.10 3A): the same approval API and checks, a channel that reaches no
  // customer. Offered only where the caller says it labels the press as a mock, so no surface can show it as a real send.
  return !!options.allowSimulated && isSimulatedPublish(detail, capability);
}

/**
 * Whether registering this inquiry would go to a development simulation rather than a channel. True only when real
 * execution is off and the server names this channel as simulated — never inferred from anything else.
 */
export function isSimulatedPublish(
  detail: Pick<InquiryDetail, "channelCode">,
  capability: PublishCapabilityView | null,
): boolean {
  if (!capability || capability.executionEnabled !== false) return false;
  const code = detail.channelCode;
  const simulated = Array.isArray(capability.simulatedChannelCodes) ? capability.simulatedChannelCodes : [];
  return !!code && simulated.includes(code);
}

/**
 * Whether the SOURCE says this customer has already been answered.
 *
 * <p>The work item can still be OPEN or PROPOSED when this is true: a seller answering in the Cafe24
 * admin UI leaves a comment that our routine sweep now reads, and the item only settles on the next
 * collection. The screen must not keep asking for work the customer no longer needs.
 */
export function answeredElsewhere(detail: Pick<InquiryDetail, "status">): boolean {
  return detail.status === "ANSWERED";
}

/**
 * Why the send path is not offered — the sentence the seller reads instead of a button.
 *
 * Each reason is a different thing for them to do, which is why they are not one message: "this
 * channel is answered in the seller center" is permanent and actionable, and "this deployment cannot
 * send yet" is neither. Returns null when the path IS available.
 */
export function publishUnavailableReason(
  detail: Pick<InquiryDetail, "channelCode" | "channelNameKo" | "replyCapability" | "status">,
  capability: PublishCapabilityView | null,
  options: { allowSimulated?: boolean } = {},
): string | null {
  if (canPublishReply(detail, capability, options)) return null;
  // First, because it is about the customer rather than about us. Telling someone "이 채널은
  // 판매자센터에서 답변해 주세요" about a question that has already been answered sends them to do
  // work that no longer exists.
  if (answeredElsewhere(detail)) {
    // Who registered it is not known from the status alone — the service's own registration reads back the same way as a
    // seller's hand copy (2026-11-05 eleventh review: 「판매자님이 직접 답변하신」 on an answer the service registered
    // told the history wrong). Say only what was confirmed.
    return "이미 답변된 문의예요. 채널에 답변이 등록된 것을 확인했어요.";
  }
  const channel = detail.channelNameKo ?? "이 채널";
  // Four different facts, four sentences (2026-11-09, demo_seller_validation_v1 §7.11). None of them may read as
  // 「reviewnary cannot register answers」 in general: a channel that has no path, an environment where the switch is
  // off, a read that did not land and a development mock are each true of one thing only.
  //
  // 1. This channel and inquiry type have no registration path — true whatever the environment, so said first. Only a
  //    non-DIRECT_API row carries a reason that is a limitation; a DIRECT_API row says it CAN register, and printing it
  //    above 「판매자센터에 등록해 주세요」 contradicted itself.
  const audited = detail.replyCapability;
  if (audited && audited.transport !== "DIRECT_API" && audited.reasonKo) {
    return `${audited.reasonKo} [초안 복사]로 옮겨 판매자센터에 등록해 주세요.`;
  }
  // 2. The capability read did not land: say that, not 「꺼져 있어요」 or 「지원하지 않아요」.
  if (!capability) {
    return "답변 등록이 가능한지 확인하지 못했어요. 새로고침하면 다시 확인해요. 그동안 [초안 복사]로 옮겨 판매자센터에 등록할 수도 있어요.";
  }
  // 3. A development mock this surface does not offer (the chat card): point to the screen that labels it as one.
  if (!options.allowSimulated && isSimulatedPublish(detail, capability)) {
    return "개발용 모의 등록은 문의 화면에서 할 수 있어요. 실제 채널과 고객에게는 나가지 않아요.";
  }
  // 4. This environment's switch is off — a deployment fact, not the channel's.
  if (!capability.executionEnabled) {
    return "현재 이 환경에서는 답변 등록 기능이 꺼져 있어요. [초안 복사]로 옮겨 판매자센터에 등록해 주세요.";
  }
  // 5. Registration is on here, but not for this channel (its own connection or flag is not set up).
  return `현재 이 환경에서는 ${channel} 답변 등록이 켜져 있지 않아요. [초안 복사]로 옮겨 판매자센터에 등록해 주세요.`;
}

/** Whether a draft version may still be edited — once an approval is bound, the content is frozen. */
export function canEditDraft(status: PublishStatusView | null): boolean {
  return status === null;
}

/**
 * The seller-facing sentence for a publish outcome.
 *
 * `CHECKING_REQUIRED` is deliberately NOT "실패": it is the DELIVERY_UNKNOWN case — the request left
 * and nobody observed the answer — and the product's rule there is verify, never resend. Telling the
 * seller it failed is how a duplicate reply gets sent by hand.
 */
export function publishCategoryLabel(category: PublishOutcomeCategory | string): string {
  switch (category) {
    case "PENDING":
      return "승인은 끝났고 아직 등록되지 않았어요.";
    case "PUBLISHING":
      return "등록 중이에요. 잠시 후 상태를 확인해 주세요.";
    case "COMPLETED":
      return "답변이 등록됐어요.";
    case "CHECKING_REQUIRED":
      return "등록 여부를 확인하는 중이에요. 다시 보내지 않고 채널 상태만 다시 조회해요.";
    case "RETRYABLE_FAILURE":
      return "일시적인 문제로 등록되지 않았어요. 다시 시도할 수 있어요.";
    case "PERMANENT_FAILURE":
      return "등록할 수 없어요. 판매자센터에서 직접 답변해 주세요.";
    default:
      // A token this build does not know — a newer backend, or a vocabulary that drifted again.
      //
      // This arm exists because its ABSENCE is what made the original defect invisible: with no
      // `default` TypeScript treated the switch as exhaustive over a union that was wrong, and the
      // function returned `undefined` into a `<p>`. An empty sentence is the one outcome this screen
      // must never produce, because the seller reads it as "nothing happened" about a reply that may
      // well have reached their customer.
      //
      // So it says the only thing that is certainly true — this screen cannot read the state — and
      // points at the surface that can. It claims neither success nor failure, and prints no token.
      return "등록 상태를 이 화면에서 확인하지 못했어요. 판매자센터에서 확인해 주세요.";
  }
}

/** Whether `category` is a token this build actually understands. */
function known(category: string): boolean {
  return (
    category === "PENDING" ||
    category === "PUBLISHING" ||
    category === "COMPLETED" ||
    category === "CHECKING_REQUIRED" ||
    category === "RETRYABLE_FAILURE" ||
    category === "PERMANENT_FAILURE"
  );
}

/**
 * Whether "다시 시도" should be offered. Never for a permanent failure, and never for a completed one.
 *
 * Takes anything that carries a category, because the same question is asked of two records: the
 * status returned by the press, and — after a reload lost that — the delivery the detail read carries.
 * Both are the same backend token, so the rule must not fork with the shape holding it.
 */
export function canResumePublish(status: { category: PublishOutcomeCategory | string } | null): boolean {
  if (status === null) return false;
  // `PENDING` is bound-but-never-dispatched, and `RETRYABLE_FAILURE` is dispatched-and-nothing-left:
  // in both the customer has seen nothing, so continuing is the seller's way forward rather than a
  // second reply. An UNKNOWN token is deliberately NOT here — resuming can put a message in front of
  // a customer, and this build cannot tell whether one is already there.
  return (
    status.category === "RETRYABLE_FAILURE" ||
    status.category === "PENDING" ||
    status.category === "PUBLISHING"
  );
}

/**
 * Whether "상태 다시 확인" should be offered — the verify-only path, which never resends.
 *
 * Offered for an unknown token too, and that asymmetry with {@link canResumePublish} is the point:
 * re-querying is read-only, so it is the one control that is safe in a state we cannot name, and it
 * is what turns the unknown sentence above from a dead end into a next step.
 */
export function canVerifyPublish(status: { category: PublishOutcomeCategory | string } | null): boolean {
  if (status === null) return false;
  return (
    status.category === "CHECKING_REQUIRED" ||
    status.category === "PUBLISHING" ||
    !known(status.category)
  );
}

/**
 * What a re-query proved, in the seller's words — or null when none has run.
 *
 * <p>`verified === null` is a third thing and stays silent: nothing has been checked yet, which the
 * category sentence above is already saying. `observedSignal` is the adapter's own token and is NOT
 * printed — 「ANSWER_POSTED_STATUS_UNRESOLVED」 is a sentence for an audit trail, not for a person.
 */
export function deliveryVerificationLabel(delivery: { verified: boolean | null } | null): string | null {
  if (!delivery || delivery.verified === null) return null;
  return delivery.verified
    ? "채널에서 답변이 등록된 것을 확인했어요."
    : "채널에서 등록 여부를 아직 확인하지 못했어요.";
}

/** The backend's code for a confirm refused for the answer's length (`InquiryPublishService.REPLY_TOO_LONG`). */
export const REPLY_TOO_LONG = "REPLY_TOO_LONG";

/**
 * Classify a confirm-publish failure into what the seller should do.
 *
 * 409 is the one that matters and the one a generic "다시 시도해 주세요" would hide: the draft moved
 * after they read it, so the approval they just gave describes content that is no longer there. The
 * only correct response is to re-read the draft and approve the version actually on screen.
 */
export function classifyPublishError(
  status: number | undefined,
  body?: unknown,
): { message: string; shouldRefresh: boolean } {
  // Too long for the channel (2026-11-02) is a 409 too, but nothing moved: the draft is the one on screen and is kept.
  // Reading it as "changed" sent the seller to re-read a draft that was never going to be sent. The backend's sentence
  // carries the current length, the allowed length and what to do, so it is shown as it is.
  const named = (body ?? null) as { code?: unknown; message?: unknown } | null;
  if (status === 409 && named?.code === REPLY_TOO_LONG) {
    const said = typeof named.message === "string" ? named.message.trim() : "";
    return {
      message: said || "답변이 이 채널에 보낼 수 있는 길이를 넘어요. 줄여서 저장한 뒤 다시 보내 주세요.",
      shouldRefresh: false,
    };
  }
  if (status === 409) {
    return {
      message: "초안이 그 사이 변경됐어요. 내용을 다시 확인한 뒤 등록해 주세요.",
      shouldRefresh: true,
    };
  }
  if (status === 404) {
    return { message: "이 문의를 찾을 수 없어요. 목록에서 다시 열어 주세요.", shouldRefresh: true };
  }
  if (status === 503) {
    return { message: "지금은 답변을 등록할 수 없어요. 판매자센터에서 직접 등록해 주세요.", shouldRefresh: false };
  }
  return { message: "답변을 등록하지 못했어요. 잠시 후 다시 시도해 주세요.", shouldRefresh: false };
}

/**
 * A refused 초안 저장 says the press that failed — saving, not registering (휴대폰 실기기 점검, 2026-11-14: a refused save read
 * 「답변을 등록하지 못했어요」, about a registration nobody had asked for). And the text the seller typed stays in the box:
 * nothing here asks for a reload, because a reload seeds the editor from the saved version and would erase it.
 *
 * <p>409 is the server's stale base (`InquiryDetailController` PUT draft): the draft moved somewhere else since this
 * screen read it, so the same save would be refused again. The seller is told that, and that their words are still
 * there to keep before they reopen it.
 */
export function classifySaveDraftError(status: number | undefined): { message: string } {
  if (status === 409) {
    return {
      message: "초안을 저장하지 못했어요. 그 사이 다른 곳에서 이 초안이 바뀌었어요. 입력한 내용은 그대로 두었어요. 복사해 둔 뒤 새로 고쳐 다시 저장해 주세요.",
    };
  }
  if (status === 404) {
    return { message: "초안을 저장하지 못했어요. 이 문의를 찾을 수 없어요. 입력한 내용은 그대로 두었어요." };
  }
  return { message: "초안을 저장하지 못했어요. 입력한 내용은 그대로 두었어요. 잠시 후 다시 저장해 주세요." };
}
