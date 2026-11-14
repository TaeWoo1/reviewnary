import { describe, expect, it } from "vitest";
import { canPublishReply, isSimulatedPublish, publishUnavailableReason } from "./inquiryPublish";
import type { InquiryDetail, InquiryReplyCapabilityView, PublishCapabilityView } from "./types";

/**
 * What a seller is told when SellerOps will not press send for them.
 *
 * The sentence matters more than it looks. A seller told "네이버는 지원하지 않습니다" concludes their
 * channel cannot do this and stops asking — but NAVER publishes answer endpoints and the refusal is
 * entirely SellerOps'. The audited capability row is what keeps those two apart, and this is where it
 * reaches the screen.
 */
function capability(over: Partial<PublishCapabilityView> = {}): PublishCapabilityView {
  return { executionEnabled: true, replyAdapterChannelCodes: ["GMARKET"], ...over };
}

function audited(over: Partial<InquiryReplyCapabilityView>): InquiryReplyCapabilityView {
  return {
    channelCode: "NAVER",
    sourceSubtype: null,
    transport: "PLATFORM_SUPPORTED_NOT_IMPLEMENTED",
    reasonKo: "네이버는 상품 문의 답변 등록 API를 제공하지만, reviewnary가 아직 연결하지 않았어요.",
    evidence: "공식: PUT /v1/contents/qnas/{questionId}",
    ...over,
  };
}

function detail(
  over: Partial<InquiryDetail> = {},
): Pick<InquiryDetail, "channelCode" | "channelNameKo" | "replyCapability" | "status"> {
  return {
    channelCode: "NAVER",
    channelNameKo: "네이버",
    replyCapability: null,
    status: "UNANSWERED",
    ...over,
  };
}

describe("publishUnavailableReason", () => {
  it("says whose limitation it is when the audit answered", () => {
    const reason = publishUnavailableReason(
      detail({ replyCapability: audited({ sourceSubtype: "NAVER_PRODUCT_QNA" }) }),
      capability(),
    );
    expect(reason).toContain("reviewnary가 아직 연결하지 않았어요");
    // The transport name is an internal vocabulary; a seller reads what it means for them.
    expect(reason).not.toContain("PLATFORM_SUPPORTED_NOT_IMPLEMENTED");
  });

  it("keeps a channel-side limitation distinct from our own", () => {
    const reason = publishUnavailableReason(
      detail({
        replyCapability: audited({
          transport: "UNSUPPORTED",
          reasonKo: "톡톡 문의는 커머스 API에 답변 등록 경로가 없어요.",
        }),
      }),
      capability(),
    );
    expect(reason).toContain("답변 등록 경로가 없어요");
    expect(reason).not.toContain("아직 연결하지");
  });

  it("does not present an unaudited channel as unsupported", () => {
    const reason = publishUnavailableReason(
      detail({
        channelCode: "CAFE24",
        channelNameKo: "카페24",
        replyCapability: audited({
          channelCode: "CAFE24",
          transport: "NEEDS_VERIFICATION",
          reasonKo: "카페24 문의 답변 등록 경로는 아직 확인하지 않았어요. 지원하지 않는다는 뜻은 아니에요.",
        }),
      }),
      capability(),
    );
    expect(reason).toContain("지원하지 않는다는 뜻은 아니에요");
  });

  it("registration on here but not for this channel says so for this channel only", () => {
    const reason = publishUnavailableReason(detail(), capability());
    expect(reason).toContain("현재 이 환경에서는 네이버 답변 등록이 켜져 있지 않아요");
    expect(reason).not.toContain("대신 등록하지 않아요");
  });

  // Reversed 2026-11-09 (demo_seller_validation_v1 §7.11): this used to say the deployment's switch outranks the channel's
  // own answer. A channel and inquiry type with no registration path stays without one when the switch is turned on, so
  // its reason is the precise one; the switch is said when it is the only thing in the way.
  it("a channel with no registration path is said before the environment switch", () => {
    const reason = publishUnavailableReason(
      detail({ replyCapability: audited({}) }),
      capability({ executionEnabled: false }),
    );
    expect(reason).toContain("reviewnary가 아직 연결하지 않았어요");
    expect(reason).not.toContain("꺼져 있어요");
  });

  it("an environment with registration off says it is this environment, not the product", () => {
    const reason = publishUnavailableReason(
      detail({ replyCapability: audited({ transport: "DIRECT_API", reasonKo: "네이버 상품 문의는 공식 답변 등록 API로 보낼 수 있습니다." }) }),
      capability({ executionEnabled: false, replyAdapterChannelCodes: [] }),
    );
    expect(reason).toContain("현재 이 환경에서는 답변 등록 기능이 꺼져 있어요");
    // A DIRECT_API row says it CAN register — printed above 「판매자센터에 등록해 주세요」 it contradicted itself.
    expect(reason).not.toContain("보낼 수 있습니다");
    expect(reason).not.toContain("대신 등록하지 않아요");
  });

  it("a capability read that did not land is not reported as off or unsupported", () => {
    const reason = publishUnavailableReason(detail(), null) ?? "";
    expect(reason).toContain("확인하지 못했어요");
    expect(reason).not.toContain("꺼져");
    expect(reason).not.toContain("지원하지 않");
  });

  it("a surface that does not offer the development mock points to the screen that does", () => {
    const cafe24 = detail({ channelCode: "CAFE24", channelNameKo: "카페24" });
    const mock = capability({ executionEnabled: false, replyAdapterChannelCodes: ["CAFE24"], simulatedChannelCodes: ["CAFE24"] });
    expect(publishUnavailableReason(cafe24, mock)).toContain("개발용 모의 등록은 문의 화면에서 할 수 있어요");
    expect(publishUnavailableReason(cafe24, mock, { allowSimulated: true })).toBeNull();
  });
});

/** A deployment that CAN send on Cafe24 — so the refusal below is about the answer, not the channel. */
function sendable(): PublishCapabilityView {
  return capability({ replyAdapterChannelCodes: ["GMARKET", "CAFE24"] });
}

describe("already answered on the channel", () => {
  it("is not publishable, whatever the channel can do", () => {
    // The seller answered in the Cafe24 admin UI; our comment lane read it and the work item has not
    // settled yet. No capability makes a second answer to the same customer correct.
    expect(canPublishReply(detail({ channelCode: "CAFE24", status: "ANSWERED" }), sendable())).toBe(
      false,
    );
  });

  it("says the customer was already answered, not that the channel is unsupported", () => {
    const reason = publishUnavailableReason(
      detail({ channelCode: "CAFE24", status: "ANSWERED" }),
      sendable(),
    );
    expect(reason).toContain("이미 답변된 문의예요");
    // Sending them to the seller center would be work that no longer exists.
    expect(reason).not.toContain("판매자센터에서 직접 답변");
    // Who registered it is not known from the status (2026-11-05 eleventh review): only what was confirmed.
    expect(reason).toBe("이미 답변된 문의예요. 채널에 답변이 등록된 것을 확인했어요.");
    expect(reason).not.toContain("판매자님이");
  });

  it("still publishes normally when the source has not been answered", () => {
    expect(canPublishReply(detail({ channelCode: "CAFE24", status: "UNANSWERED" }), sendable())).toBe(
      true,
    );
  });
});

/**
 * The development mock registration (2026-11-09, demo §7.10 3A). The fake channel's adapter is registered while real
 * execution is off; the server names it in `simulatedChannelCodes`. Only a surface that labels the press as a mock may
 * offer it, and nothing makes a real channel read as simulated.
 */
describe("simulated publish", () => {
  const fake = capability({ executionEnabled: false, replyAdapterChannelCodes: ["CAFE24"], simulatedChannelCodes: ["CAFE24"] });
  const cafe24 = detail({ channelCode: "CAFE24", channelNameKo: "카페24" });

  it("is offered only to a caller that labels it as a mock", () => {
    expect(canPublishReply(cafe24, fake)).toBe(false);
    expect(canPublishReply(cafe24, fake, { allowSimulated: true })).toBe(true);
    expect(isSimulatedPublish(cafe24, fake)).toBe(true);
    expect(publishUnavailableReason(cafe24, fake, { allowSimulated: true })).toBeNull();
  });

  it("needs the server to name the channel — an adapter with execution off is not enough", () => {
    const noName = capability({ executionEnabled: false, replyAdapterChannelCodes: ["CAFE24"] });
    expect(canPublishReply(cafe24, noName, { allowSimulated: true })).toBe(false);
    expect(isSimulatedPublish(cafe24, noName)).toBe(false);
  });

  it("never applies to another channel or beside real execution", () => {
    expect(canPublishReply(detail({ channelCode: "NAVER" }), fake, { allowSimulated: true })).toBe(false);
    const real = capability({ executionEnabled: true, replyAdapterChannelCodes: ["CAFE24"], simulatedChannelCodes: ["CAFE24"] });
    expect(isSimulatedPublish(cafe24, real)).toBe(false);
    expect(canPublishReply(cafe24, real)).toBe(true);
  });

  it("is not offered for an inquiry already answered on the channel", () => {
    expect(canPublishReply({ ...cafe24, status: "ANSWERED" }, fake, { allowSimulated: true })).toBe(false);
  });

  it("fails closed on an unread capability", () => {
    expect(canPublishReply(cafe24, null, { allowSimulated: true })).toBe(false);
    expect(isSimulatedPublish(cafe24, null)).toBe(false);
  });
});

it("a capability of an unexpected shape answers no instead of throwing (2026-11-09)", () => {
  const odd = { enabled: false, channels: [] } as unknown as PublishCapabilityView;
  const cafe24 = { channelCode: "CAFE24", channelNameKo: "카페24", replyCapability: null, status: "UNANSWERED" } as const;
  expect(canPublishReply(cafe24, odd, { allowSimulated: true })).toBe(false);
  expect(isSimulatedPublish(cafe24, odd)).toBe(false);
  expect(publishUnavailableReason(cafe24, odd, { allowSimulated: true })).not.toBeNull();
});
