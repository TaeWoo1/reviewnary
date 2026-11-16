package com.sellerops.talktalk;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * What the lane's screens read. No send key, no TalkTalk user value, no full partner id, no webhook token (except the
 * one-time {@link ConnectResponse}), no raw event body.
 */
public final class TalkTalkViews {

    private TalkTalkViews() {
    }

    public record Reason(String code, String label) {
        static Reason of(String code) {
            return code == null ? null : new Reason(code, TalkTalkReasons.label(code));
        }
    }

    public record ReadinessItemView(String code, boolean met, String label) {
    }

    public record ReadinessView(boolean ready, List<ReadinessItemView> items, String residualRisk) {
    }

    public record ConnectionView(boolean connected, String status, String mode, boolean fake, String modeLabel,
                                 String partner, Instant lastEventAt, Instant connectedAt) {
    }

    public record PreviewSample(String label, String text, Reason refusal) {
    }

    public record ClosedDateView(LocalDate date, String note) {
    }

    public record GuidanceView(UUID id, int version, String status, Integer cutoffHour, int cutoffMinute,
                               List<String> dispatchDays, List<ClosedDateView> closedDates, LocalDate confirmedThrough,
                               String fingerprint, String previewFingerprint, List<PreviewSample> previews,
                               Instant confirmedAt) {
    }

    public record DelegationView(boolean active, Reason reason, Integer guidanceVersion, Instant changedAt,
                                 List<String> answeredAutomatically, List<String> handedToPerson) {
    }

    public record StatusView(ConnectionView connection, ReadinessView readiness, GuidanceView currentGuidance,
                             GuidanceView draftGuidance, DelegationView delegation, String laneNotice) {
    }

    public record ConnectResponse(StatusView status, String webhookUrl, String webhookNotice) {
        @Override
        public String toString() {
            return "ConnectResponse(webhookUrl=<shown once>)";
        }
    }

    public record ConversationRow(UUID id, String alias, String owner, String ownerLabel, boolean needsHuman,
                                  Reason humanReason, Reason automation, Instant lastInboundAt, String lastPreview,
                                  String lastOutboundStatus) {
    }

    /** {@code nonTextNote}: for a photo or card message, what it was and where to see it — never shown as empty. */
    public record MessageView(UUID id, String author, String authorLabel, String text, Instant at,
                              boolean duplicateSuspect, boolean standby, boolean purged, String outboundStatus,
                              String outboundStatusLabel, String nonTextNote) {
    }

    public static String nonTextNote(String inputType) {
        if (TalkTalkEventParser.NON_TEXT_IMAGE.equals(inputType)) {
            return "사진을 보냈어요. 이 서비스에는 사진이 저장되지 않아요 — 톡톡 파트너센터에서 확인해 주세요.";
        }
        if (TalkTalkEventParser.NON_TEXT_COMPOSITE.equals(inputType)) {
            return "카드형 메시지를 보냈어요. 이 서비스에는 내용이 저장되지 않아요 — 톡톡 파트너센터에서 확인해 주세요.";
        }
        return null;
    }

    public record EvidenceView(String kind, String title) {
    }

    public record DraftView(int version, String authorKind, String text, String fingerprint, String basis,
                            List<Reason> holds, List<EvidenceView> evidence, Instant createdAt, boolean forLatest) {
    }

    public record OutboundView(UUID id, String kind, String kindLabel, String status, String statusLabel,
                               Reason failure, String resultCode, String mode, boolean echoSeen, Instant createdAt,
                               Instant finishedAt, boolean byService) {
    }

    public record ConversationDetail(ConversationRow conversation, String productName, Instant leftAt, String mode,
                                     boolean fake, List<MessageView> messages, DraftView draft,
                                     List<OutboundView> outbound) {
    }

    /**
     * What the home and the menu show about TalkTalk (review item 8). Counted per conversation, never per message: a
     * customer who writes five lines is one conversation to answer. The home's own inquiry and review numbers do not
     * include TalkTalk, and {@code notice} says so.
     */
    public record SummaryView(boolean connected, boolean fake, long needsHumanConversations, long unknownSends,
                              long queuedSends, String notice) {
    }

    public static String statusLabel(TalkTalkOutbound.Status s) {
        if (s == null) {
            return null;
        }
        return switch (s) {
            case QUEUED -> "보내기 대기";
            case SENDING -> "보내는 중";
            case FAKE_RECORDED -> "모의 기록 · 실제로 가지 않았어요";
            case API_ACCEPTED -> "톡톡 접수 · 고객이 받았는지는 알 수 없어요";
            case FAILED -> "보내지 못했어요";
            case UNKNOWN -> "결과를 몰라요 · 톡톡에서 확인해 주세요";
            case CANCELLED -> "보내지 않았어요";
            case BLOCKED -> "막혔어요";
        };
    }

    public static String kindLabel(TalkTalkOutbound.Kind k) {
        return switch (k) {
            case MANUAL_REPLY -> "직접 보낸 답변";
            case AUTO_GUIDANCE -> "배송 기준 자동 안내";
            case HANDOVER_PASS -> "상담원에게 넘기기";
            case HANDOVER_TAKE -> "챗봇으로 가져오기";
        };
    }

    public static String authorLabel(TalkTalkMessage.Author a) {
        return switch (a) {
            case CUSTOMER -> "고객";
            case SERVICE -> "리뷰너리";
            case PARTNER_CENTER -> "파트너센터 상담원";
            case BOT_ECHO -> "다른 곳에서 보낸 메시지";
        };
    }

    public static String ownerLabel(TalkTalkConversation.Owner o) {
        return o == TalkTalkConversation.Owner.PARTNER ? "상담원이 맡음" : "챗봇(리뷰너리)이 맡음";
    }

    /** What the delegated automation answers and what it never does — shown beside the switch (review item 2). */
    public static final List<String> ANSWERED_AUTOMATICALLY = List.of(
            "출고 마감 시각·출고 요일·휴무일을 묻는 일반 질문 (예: 「오늘 주문하면 오늘 출고되나요?」 「금요일에 쉬시나요?」)",
            "확정한 배송 기준으로 코드가 만든 정해진 문장만 보내요. AI가 쓰지 않아요.");

    public static final List<String> HANDED_TO_PERSON = List.of(
            "특정 주문의 실제 출고·도착 여부, 송장, 도착일 질문",
            "재고·입고·주문 제작·예약 상품, 기준이 다른 상품을 보고 한 문의",
            "취소·환불·교환·반품, 주소·옵션 변경, 불량·파손, 가격·배송비",
            "배송 기준 밖의 요청이 한 문장에라도 섞인 메시지",
            "상담원이 맡은 대화, 같은 내용 재수신, 24시간 안에 이미 자동 안내한 대화",
            "기준이 바뀌거나 가게 자료와 출고 마감이 다를 때, 휴무일 확인 기한을 넘을 때");
}
