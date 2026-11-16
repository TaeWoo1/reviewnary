package com.sellerops.talktalk;

import java.util.Map;

/** Every reason code the lane records, in the words a seller reads (해요체). Unknown codes show as themselves. */
public final class TalkTalkReasons {

    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("AUTO_QUEUED", "배송 기준 자동 안내를 보내려고 대기 중이에요"),
            Map.entry("DELEGATION_OFF", "자동 안내가 꺼져 있어요"),
            Map.entry("DELEGATION_STALE", "배송 기준이 바뀌어 자동 안내가 멈췄어요. 다시 켜야 해요"),
            Map.entry("DELEGATION_CHANGED", "자동 안내 설정이 바뀌어 보내지 않았어요"),
            Map.entry("NO_CONFIRMED_GUIDANCE", "확정된 배송 기준이 없어요"),
            Map.entry("GUIDANCE_CHANGED", "배송 기준이 바뀌었어요"),
            Map.entry("GUIDANCE_RETIRED", "참고한 배송 기준이 내려졌어요"),
            Map.entry("CUTOFF_MISSING", "출고 마감 시각이 정해지지 않았어요"),
            Map.entry("DISPATCH_DAYS_MISSING", "출고 요일이 정해지지 않았어요"),
            Map.entry("CALENDAR_UNCONFIRMED", "다음 출고일이 휴무일 확인 기한을 넘어요"),
            Map.entry("NO_DISPATCH_DAY", "가까운 출고일을 찾을 수 없어요"),
            Map.entry("KNOWLEDGE_CONFLICT", "가게 자료의 출고 마감과 배송 기준이 달라요"),
            Map.entry("FACT_CHECK_UNAVAILABLE", "가게 자료와 맞는지 확인하지 못했어요"),
            Map.entry("PRODUCT_EXCEPTION_POSSIBLE", "상품을 보고 문의했고, 기준이 다른 상품이 있어요"),
            Map.entry("PARTNER_OWNS_THREAD", "상담원이 맡고 있는 대화예요"),
            Map.entry("STANDBY", "상담원 응대 중에 온 메시지예요"),
            Map.entry("PARTNER_REPLIED", "파트너센터에서 상담원이 답했어요"),
            Map.entry("PASSED_TO_PARTNER", "상담원에게 넘긴 대화예요"),
            Map.entry("UNMATCHED_ECHO", "이 서비스가 보내지 않은 메시지가 대화에 있어요"),
            Map.entry("CUSTOMER_LEFT", "고객이 대화방을 나갔어요"),
            Map.entry("DUPLICATE_SUSPECT", "같은 내용을 다시 받았어요(중복일 수 있어요)"),
            Map.entry("RECENT_AUTO", "24시간 안에 자동 안내를 이미 보냈어요"),
            Map.entry("AUTO_IN_FLIGHT", "보내는 중이거나 결과를 모르는 자동 안내가 있어요"),
            Map.entry("NEWER_MESSAGE", "고객이 새 메시지를 보냈어요"),
            Map.entry("ALREADY_ANSWERED", "이미 답한 메시지예요"),
            Map.entry("STALE", "너무 오래 기다린 자동 안내라 보내지 않았어요"),
            Map.entry("TEXT_CHANGED", "시간이 지나 안내 문장이 달라졌어요"),
            Map.entry("TRIGGER_MISSING", "답할 메시지를 찾을 수 없어요"),
            Map.entry("CONNECTION_OFF", "톡톡 연동이 꺼져 있어요"),
            Map.entry("DISCONNECTED", "톡톡 연동을 해제했어요"),
            Map.entry("UNTRUSTED_SOURCE", "확인된 경로로 들어온 메시지가 아니에요"),
            Map.entry("MODE_CHANGED", "전송 방식이 바뀌어 보내지 않았어요"),
            Map.entry("LIVE_NOT_READY", "실제 전송 준비가 끝나지 않았어요"),
            Map.entry("KEY_UNAVAILABLE", "인증 키를 열 수 없어요"),
            Map.entry("NO_CONVERSATION", "대화를 찾을 수 없어요"),
            Map.entry("INTERRUPTED", "보내는 중에 멈췄어요. 톡톡에서 실제로 갔는지 확인해 주세요"),
            Map.entry("EMPTY", "내용이 없는 메시지예요"),
            Map.entry("NON_TEXT", "사진·카드 메시지예요. 이 서비스에는 내용이 저장되지 않아요 — 톡톡 파트너센터에서 확인해 주세요"),
            Map.entry("NOT_TYPED", "직접 입력한 문장이 아니에요(버튼·스티커·상품 문의 시작 등)"),
            Map.entry("TOO_LONG", "길어서 여러 요청이 섞였을 수 있어요"),
            Map.entry("ORDER_SPECIFIC", "주문 한 건의 실제 상황을 묻고 있어요"),
            Map.entry("ARRIVAL", "도착일을 묻고 있어요(기준으로 답할 수 없어요)"),
            Map.entry("STOCK", "재고·입고를 묻고 있어요"),
            Map.entry("CANCEL_REFUND_EXCHANGE", "취소·환불·반품·교환 요청이 있어요"),
            Map.entry("CHANGE_REQUEST", "주소·옵션 변경 요청이 있어요"),
            Map.entry("DEFECT", "불량·파손·누락 이야기가 있어요"),
            Map.entry("PRICE", "가격·배송비·결제 이야기가 있어요"),
            Map.entry("UNHANDLED_REQUEST", "배송 기준 밖의 요청이 함께 있어요"),
            Map.entry("NOT_SHIPPING_GUIDANCE", "배송 기준 질문이 아니에요"),
            Map.entry("SOURCE_RETIRED", "참고한 자료가 꺼졌어요"),
            Map.entry("SOURCE_CHANGED", "참고한 자료 내용이 바뀌었어요"),
            Map.entry("NO_ANSWER_BASIS", "참고할 자료가 없어요"),
            Map.entry("MODEL_NO_DRAFT", "AI가 답변을 쓰지 못했어요"),
            Map.entry("MODEL_OFF", "AI 답변이 꺼져 있어요"),
            Map.entry("HANDOVER_PENDING", "상담원 넘기기 결과가 아직 확정되지 않았어요"),
            Map.entry("CONNECTION_CHANGED", "톡톡 연동이 바뀌어(재연결·주소 재발급) 보내지 않았어요"),
            Map.entry("WEBHOOK_ROTATED", "받는 주소를 바꿔 보내지 않았어요"),
            Map.entry("PARTNER_CHANGED", "다른 톡톡 계정으로 연결돼 이 대화로는 보낼 수 없어요"),
            Map.entry("STALE_GENERATION", "이전 연동에서 받은 이벤트예요"),
            Map.entry("TARGET_REFUSED", "허용되지 않은 전송 주소예요"),
            Map.entry("KEY_UNUSABLE", "인증 키를 쓸 수 없어요"),
            Map.entry("LIVE_OFF", "서버에서 실제 전송이 꺼져 있어요"),
            Map.entry("CONNECT_FAILED", "톡톡에 연결하지 못했어요(보내지 않았어요)"),
            Map.entry("TIMEOUT", "응답이 없어 결과를 몰라요"),
            Map.entry("BODY_UNREADABLE", "응답을 읽을 수 없어 결과를 몰라요"),
            Map.entry("SUCCESS_NOT_BOOLEAN", "응답 형식이 달라 결과를 몰라요"),
            Map.entry("SUCCESS_WITHOUT_00", "응답 형식이 달라 결과를 몰라요"),
            Map.entry("RESULT_01", "톡톡이 인증 키를 거부했어요"),
            Map.entry("RESULT_02", "톡톡이 요청 형식을 거부했어요"),
            Map.entry("RESULT_99", "톡톡이 요청을 처리하지 못했어요"),
            Map.entry("PRODUCT_UNRESOLVED", "문의한 상품을 하나로 특정하지 못해 회사 공통 자료로만 답했어요"),
            Map.entry("POLICY_CONFLICT", "가게 자료와 다른 값(배송비·기한·출고 등)이 있어요"),
            Map.entry("UNSETTLED_VALUE", "가게 자료끼리 값이 달라 정해지지 않은 내용이 있어요"),
            Map.entry("EVIDENCE_UNREADABLE", "참고한 근거 기록을 읽을 수 없어요"),
            Map.entry("DRAFT_MISSING", "보낼 답변을 찾을 수 없어요"),
            Map.entry("COST_CONFLICT", "누가 비용을 내는지 가게 자료와 반대로 적혀 있어요"),
            Map.entry("FORBIDDEN_PHRASE", "가게가 쓰지 않기로 한 표현이 들어 있어요"),
            Map.entry("STYLE_CHECK_UNAVAILABLE", "금지 표현을 확인하지 못했어요"),
            Map.entry("SPECIFIC_DATE", "특정 날짜를 묻고 있어요(자동 안내는 지금과 다가오는 2주만 말해요)"));

    private TalkTalkReasons() {
    }

    public static String label(String code) {
        if (code == null) {
            return null;
        }
        String c = code;
        if (c.startsWith("QUESTION_")) {
            c = c.substring("QUESTION_".length());
        } else if (c.startsWith("CLAIM_")) {
            return "근거에 없는 약속·조치·숫자가 있어요 (" + c.substring(6) + ")";
        } else if (c.startsWith("RECHECK_REQUIRED:")) {
            return "보내기 직전 다시 확인해 보니: " + label(c.substring("RECHECK_REQUIRED:".length()));
        } else if (c.startsWith("AUTO_")) {
            return "자동 안내 결과: " + c.substring(5);
        }
        return LABELS.getOrDefault(c, c);
    }
}
