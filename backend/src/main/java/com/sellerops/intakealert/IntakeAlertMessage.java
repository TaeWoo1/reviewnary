package com.sellerops.intakealert;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <p>"새로 확인된" — new to this service, which is when a row arrives here; the customer may have written it earlier
 * (a sync after a pause). The notice page shows both times; the message never claims the customer wrote it just now.
 *
 * The words of an alert — a fixed template with closed variables, so what the seller's phone shows never contains a
 * customer's sentence, name, number or order (night handoff: 「민감 고객/주문 원문 없이 짧은 유형·우선도」).
 *
 * <p>The proposed 알림톡 template (to be submitted to Kakao for review; <b>not approved</b>):
 * <pre>
 * [리뷰너리] #{제목}
 * 가게: #{가게}
 * #{내용}
 * 안내: #{안내}
 * 승인과 등록은 서비스에서만 할 수 있어요. 아래 버튼으로 확인해 주세요.
 * </pre>
 * with one web-link button 「확인하기」 to {@code /notice/<delivery id>}.
 */
public final class IntakeAlertMessage {

    public record Rendered(String text, Map<String, String> variables) {
    }

    private IntakeAlertMessage() {
    }

    static final Map<String, String> CHANNEL_KO = Map.of(
            "NAVER", "네이버", "NAVER_SMARTSTORE", "네이버", "COUPANG", "쿠팡", "CAFE24", "카페24");

    public static String channelKo(String code) {
        if (code == null) {
            return "채널 미상";
        }
        String up = code.toUpperCase(java.util.Locale.ROOT);
        for (Map.Entry<String, String> e : CHANNEL_KO.entrySet()) {
            if (up.startsWith(e.getKey())) {
                return e.getValue();
            }
        }
        return "기타 채널";
    }

    public static String reasonKo(IntakeAlertItem.Reason reason) {
        return switch (reason) {
            case SAFETY -> "안전 관련 제보";
            case LOW_RATING -> "별점 낮은 리뷰";
            case NEW_REVIEW -> "새 리뷰";
            case NEW_INQUIRY -> "새 문의";
        };
    }

    static String guidance(IntakeAlertItem.Reason reason) {
        return switch (reason) {
            case SAFETY -> "사실인지 원문에서 먼저 확인하고 응대 방향을 정해 주세요.";
            case LOW_RATING -> "불만 내용을 확인하고 답글 초안을 검토해 주세요.";
            case NEW_REVIEW -> "답글이 필요한지 확인해 주세요.";
            case NEW_INQUIRY -> "답변 초안을 확인하고 승인해 주세요.";
        };
    }

    /** One review or inquiry — the default. */
    public static Rendered single(String store, IntakeAlertItem item) {
        String kindKo = item.getSubjectKind() == IntakeAlertItem.Kind.REVIEW ? "리뷰" : "문의";
        String title = item.getReason() == IntakeAlertItem.Reason.SAFETY
                ? "안전 관련 " + kindKo + "가 확인됐어요"
                : "새로 확인된 " + kindKo + "가 있어요";
        String topic = item.getIssueLabel() == null || "기타".equals(item.getIssueLabel())
                ? "" : " · 주제 " + item.getIssueLabel();
        String body = channelKo(item.getChannelCode()) + " " + reasonKo(item.getReason()) + topic;
        return render(title, store, body, guidance(item.getReason()));
    }

    /** Several at once — only when the seller chose bundling (DIGEST) or a receiving window. Never chosen by volume. */
    public static Rendered digest(String store, List<IntakeAlertItem> items) {
        long reviews = items.stream().filter(i -> i.getSubjectKind() == IntakeAlertItem.Kind.REVIEW).count();
        long inquiries = items.size() - reviews;
        long low = items.stream().filter(i -> i.getReason() == IntakeAlertItem.Reason.LOW_RATING).count();
        StringBuilder body = new StringBuilder();
        if (reviews > 0) body.append("새 리뷰 ").append(reviews).append("건");
        if (inquiries > 0) body.append(body.isEmpty() ? "" : ", ").append("새 문의 ").append(inquiries).append("건");
        if (low > 0) body.append(" (별점 낮은 리뷰 ").append(low).append("건)");
        return render("확인할 일이 " + items.size() + "건 있어요", store, body.toString(),
                "목록에서 하나씩 열어 확인하고 승인해 주세요.");
    }

    private static Rendered render(String title, String store, String body, String guidance) {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("제목", title);
        vars.put("가게", clip(store == null || store.isBlank() ? "내 가게" : store, 40));
        vars.put("내용", body);
        vars.put("안내", guidance);
        String text = "[리뷰너리] " + vars.get("제목") + "\n가게: " + vars.get("가게") + "\n" + vars.get("내용")
                + "\n안내: " + vars.get("안내") + "\n승인과 등록은 서비스에서만 할 수 있어요. 아래 버튼으로 확인해 주세요.";
        return new Rendered(clip(text, 1000), vars);
    }

    private static String clip(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n);
    }
}
