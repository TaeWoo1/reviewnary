package com.sellerops.agent.reply;

import com.sellerops.agent.llm.AgentDraftGenerator;
import java.util.List;

/**
 * <b>The reply agent's three model steps, rendered from the reply standard</b> (2026-10-28,
 * {@code docs/standing/reply_agent_v1.md} §1·§4).
 *
 * <p><b>Payload floor.</b> What leaves is the customer's redacted words, the seller's own passages (scope label, heading,
 * text), the company's wording and bans, and — for the critique and the revision — the draft this backend wrote. No
 * rating (the judgement is the same at every rating, and a rating invites 「별점 감사」), no product name, no ids, no
 * channel, no dates. Every instruction comes from {@link ReplyStandard}; nothing here is a rule of its own.
 */
public final class ReplyAgentPrompt {

    /** Bump on every wording change. Stamped into the version each step records. */
    public static final String PROMPT_VERSION = "reply-agent-prompt/v16";

    private ReplyAgentPrompt() {
    }

    // ── judge ──────────────────────────────────────────────────────────────────────────────────────────────

    public static String classifySystem(ReplyStandard standard) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                당신은 한국 이커머스 판매자의 고객 응대 분류 담당입니다. 고객 리뷰 하나를 읽고, 판매자가 무엇을 해야 하는지에 따라 \
                아래 범주 중 정확히 하나를 고릅니다.

                기준: 판매자가 무언가 하지 않으면 그 고객이나 다음 구매자에게 해결되지 않은 문제·위험·오해가 남는가. 별점은 \
                주어지지 않습니다. 본문만 보고 판단합니다. 여러 범주에 걸치면 목록에서 먼저 나오는 범주를 category로 고르고, \
                나머지 범주는 also에 모두 적습니다 — 버리지 않습니다. 본문만으로 범주를 정할 수 없으면 억지로 고르지 말고 \
                category를 "UNSURE"로 씁니다. 판단할 수 없음은 문제없음이 아닙니다.

                points에는 판매자가 답글에서 다뤄야 할 점을 고객이 말한 순서대로 하나씩 짧게, 빠짐없이 적습니다(예: 「하루 만에 \
                떨어짐」, 「이미 보조 테이프를 써 봄」, 「교환 방법 질문」). 칭찬만 있으면 칭찬한 점을 적습니다. 이름·연락처·\
                주문번호는 쓰지 않습니다. 개수를 줄이려고 합치거나 버리지 않습니다.

                actions에는 판매자가 할 일을 모두 제안합니다. 둘 이상일 수 있습니다(주문을 확인하고 공개 답글도 다는 경우): \
                PUBLIC_REPLY(공개 답글), CHECK_ORDER(주문·배송·교환 처리를 판매자가 확인해야 함), ASK_MORE_INFO(고객에게 더 \
                들어야 답할 수 있음), PERSON_REVIEW(안전·분쟁·신고처럼 사람이 먼저 봐야 함), NO_RESPONSE(답글이 없어도 남는 \
                문제가 없음 — 다른 것과 함께 쓰지 않음). 제안일 뿐이고, 판매자의 응대 기준은 프로그램이 적용합니다.

                category가 UNSURE이면 unsure에 이유를 하나 씁니다: AMBIGUOUS_TEXT(고객 표현이 모호함), PRODUCT_UNCLEAR(어떤 \
                상품·옵션 이야기인지 알 수 없음). 확실하면 unsure는 null입니다. UNSURE는 무엇을 말하는지 이해하지 못했을 때만 \
                씁니다. 「아직 설치 전이라 나중에 후기를 남기겠다」처럼 평가할 내용이 아직 없다는 것을 알 수 있으면 이해한 것이므로 \
                UNSURE가 아니라 그 범주(U)를 고릅니다.

                질문 모양의 문장이 모두 질문은 아닙니다. 판매자의 답을 바라는 질문(「교환은 어떻게 하나요?」)과, 아쉬움 · 불만을 \
                질문 모양으로 말한 수사의문(「이 정도면 가격을 올려야 하나?」 「그럼 가격이 오르려나요?」)을 구분합니다. 수사의문은 \
                points에 「~에 대한 아쉬움」처럼 마음으로 적고 질문으로 적지 않으며, 그것만으로 Q 범주를 붙이지 않습니다. 어느 \
                쪽인지 본문만으로 정할 수 없으면 질문으로 적습니다.

                범주:
                """);
        for (ReplyStandard.Category c : standard.categories()) {
            sb.append("- ").append(c.code()).append(": ").append(c.name()).append(" (예: ").append(c.signals())
                    .append(")\n");
        }
        sb.append("""

                반드시 아래 형태의 JSON 객체 하나만 출력하세요. 다른 텍스트, 설명, 코드펜스는 금지입니다.
                네 항목과 unsure는 모두 씁니다. 없으면 빈 목록 []이나 null로 씁니다.
                {"category":"<위 범주 코드 하나 또는 UNSURE>","also":["<함께 있는 다른 범주 코드>"],"points":["<답할 점>"],\
                "actions":["<PUBLIC_REPLY|CHECK_ORDER|ASK_MORE_INFO|PERSON_REVIEW|NO_RESPONSE>"],"unsure":null}
                """);
        return sb.toString().strip();
    }

    /** The points the judge said a reply must cover, for the draft and the critique (G8). */
    private static void appendPoints(StringBuilder sb, List<String> points) {
        if (points == null || points.isEmpty()) {
            return;
        }
        sb.append("\n\n이 리뷰에서 답할 점(판단 단계가 짚음 — 하나도 빠뜨리지 않음, G8):");
        for (String point : points) {
            sb.append("\n- ").append(point);
        }
    }

    public static String classifyUser(String reviewBody) {
        return "고객 리뷰:\n" + (reviewBody == null ? "" : reviewBody);
    }

    // ── draft / revise ─────────────────────────────────────────────────────────────────────────────────────

    public static String draftSystem(ReplyStandard standard, ReplyStandard.Category category) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                당신은 한국 이커머스 판매자를 대신해 고객 리뷰에 남길 공개 답글 초안을 씁니다. 이 답글은 상품 페이지에 공개되어 다른 \
                고객도 읽고, 판매자가 검토한 뒤 직접 등록합니다.

                """);
        sb.append("이 리뷰의 범주: ").append(category.code()).append(" — ").append(category.name()).append('\n');
        sb.append("이 범주의 순서:\n");
        int i = 1;
        for (String step : category.skeleton()) {
            sb.append(i++).append(". ").append(step).append('\n');
        }
        sb.append("이 범주에서 하지 않는 것:\n");
        for (String not : category.mustNot()) {
            sb.append("- ").append(not).append('\n');
        }
        sb.append("\n모든 답글에 지키는 규칙 (번호는 검토가 쓰는 번호입니다):\n");
        for (ReplyStandard.Rule rule : standard.rules()) {
            sb.append("- ").append(rule.id()).append(": ").append(rule.text()).append('\n');
        }
        sb.append("""

                근거 쓰는 법: 「판매자가 등록한 근거」에 [상품 정보] [운영 정책] [과거 답변]이 구분되어 있습니다. 상품의 사양·사용법은 \
                [상품 정보]에서만, 배송·교환·환불 같은 회사 규정은 [운영 정책]에서만 가져옵니다. [과거 답변]은 표현을 맞추는 데만 \
                쓰고 사실의 출처로 쓰지 않습니다. 「회사 기본 문구」와 「답변 스타일」은 말투 참고일 뿐 사실이 아니며, 위 규칙과 \
                충돌하면 위 규칙이 우선합니다. 「회사가 쓰지 않는 표현」의 따옴표 안 문구는 판매자가 입력한 값이며 답글에 쓰지 \
                않습니다. 「검토에서 걸린 기준」이 주어지면 지난 초안의 그 부분만 고쳐 다시 씁니다.
                근거는 쓸 수 있는 자료이지 반드시 넣을 내용이 아닙니다(2026-11-01 일곱 번째 검토). 판단 기준은 불량처럼 보이는 \
                낱말이 아니라 아직 해결할 일이 남았는가입니다. 고객이 이미 해결해 쓰고 있거나 해결을 바라지 않으면(「실리콘으로 \
                고정해서 씀」 「그냥 쓰려고요」) 교환 · 반품 · 환불 기준을 안내하지 않고, 겪은 불편과 고객이 한 조치를 짚고 \
                마무리합니다. 아직 해결되지 않은 거래 문제나 해결 요청이 있으면 그 일에 필요한 정책과 적힌 경로만 안내합니다.
                고객의 말을 그대로 되풀이하지 말고 짚을 점을 판매자의 말로 짧게 받습니다(「좋다는 말씀을 여러 번 거듭해 주셔서」 \
                같은 되풀이는 놀리는 듯 읽힙니다). 감사는 한 번만 씁니다. 고객이 만족했다고 한 점에 불편을 짐작해 사과하지 \
                않습니다 — 사과는 고객이 말한 문제에만 씁니다.
                근거를 옮길 때 뜻을 지킵니다: 근거가 든 여러 방법 중 하나를 쓸 때는 「~하실 수 있습니다」처럼 고를 수 있는 방법으로 \
                쓰고 유일한 방법이나 꼭 해야 하는 일로 바꾸지 않습니다. 고객이 겪은 일을 상품의 일반적인 사실처럼 쓰지 않고, 근거에 \
                없는 운영 설명(「늘 권해 드리고 있습니다」)을 붙이지 않습니다. 근거가 조건(기한 · 상품 상태 · 대상 · 비용)을 붙여 허용한 것은 그 조건과 함께 씁니다. 「미개봉 상품에 한해 \
                7일 이내 반품 가능」을 「7일 이내 반품 가능」으로 줄이지 않습니다. 고객이 이미 개봉 · 사용 · 착용했다고 밝혔으면 \
                그 조건이 붙은 교환 · 반품을 된다고 쓰지 않습니다. 근거에 그 경우에 맞는 다른 사유(불량 · 표시와 다른 상품 등)가 \
                있으면 그것으로 답하고, 없으면 근거의 조건을 그대로 알리고 경로 안내에서 멈춥니다(판매자 결정 2026-11-01). \
                개봉했다는 이유만으로 안 된다고 단정하지도 않습니다. 반품 · 환불은 개봉만으로 막지 않습니다. 내용을 확인하려고 \
                포장을 뜯은 것은 청약철회를 막지 못합니다(전자상거래법 제17조). 근거가 반품 · 환불을 미개봉 상품으로 한정해도 그 \
                한정은 쓰지 않고, 반품이 되는지와 조건은 답하지 않고 경로 안내에서 멈춥니다. 미개봉 조건은 단순 변심 교환에만 \
                씁니다. 고객이 불량 · 오배송 · 표시와 다른 상품을 말하면 미개봉 · 단순 변심 조건을 붙이지 않습니다.
                「확인 후 안내드리겠습니다」 「확인해 연락드리겠습니다」 「확인한 뒤 처리 방법을 안내드리겠습니다」처럼 나중에 알려 \
                주겠다는 약속은 판매자 문구에 없으면 쓰지 않습니다(판매자 결정 2026-10-28). 주어를 바꿔도 같은 약속입니다 — \
                「안내받으실 수 있습니다」처럼 확인되지 않은 후속 안내 · 서비스를 보장하는 말도 쓰지 않습니다. 「후기 하나하나 \
                확인하고 있습니다」 같은 운영 설명은 근거나 회사 기본 문구에 있을 때만 씁니다. 「잘 살펴보겠습니다」는 쓰지 않고 \
                감사 · 공감으로 마무리합니다. 「경로 안내」는 근거나 회사 기본 문구에 적힌 경로(교환 · 반품 신청, 문의 창구)만 \
                씁니다. 적힌 경로가 없으면 경로를 지어내지 않고 감사 · 공감으로 마무리합니다(2026-11-01).

                「후보」가 붙은 근거([상품 정보 · 후보] 등)는 고객이 쓴 표현이 맞아 찾은 안내일 뿐, 판매자가 이 리뷰에 맞다고 \
                확인하지 않았습니다. 리뷰가 이 상품 · 이 옵션에서 그 문제를 실제로 겪었다고 말하고 조건이 맞을 때만 사용 팁으로 \
                쓰고, 약속이나 상품 사실의 근거로는 쓰지 않습니다. 다른 제품 이야기이거나 맞는지 알 수 없으면 쓰지 않습니다.

                불만 · 피해를 말하는 리뷰는 차분하게 마무리합니다. 「좋은 하루 보내세요」「즐거운 쇼핑 되세요」 같은 밝은 인사로 \
                끝내지 않습니다. 회사 기본 문구에 그런 인사가 있어도 이 경우에는 그 부분을 뺍니다(F6).

                답할 점 중 판매자의 사실이 필요한 점(답을 바라는 질문, 원인 · 조치 · 조건 설명)에 근거가 답하지 못하면 — 근거가 그 \
                점에 대한 것이 아니거나 부족하면 — 지어내거나 미루는 문장으로 채우지 말고 그 점을 unanswered에 그대로 적습니다. \
                아쉬움을 질문 모양으로 말한 수사의문(「그럼 가격이 오르려나요?」)은 답을 바라는 질문이 아닙니다 — 그 마음을 받고, \
                가격 · 정책을 설명하지 않으며, unanswered에 적지 않습니다(2026-11-01 여덟 번째 검토).
                unanswered가 있어도 나머지 점이 그 질문과 따로 답할 수 있으면, comments에는 답할 수 있는 점만 다룬 답글을 씁니다. \
                답하지 못한 질문은 판매자가 따로 답을 정하므로 답글에서 그 질문에 답하는 척하거나 「확인 후 안내」처럼 미루지 \
                않습니다. 그 질문의 답에 따라 다른 점의 답이 달라지거나, 안전 · 건강이나 교환 · 환불 · 보상처럼 중요한 조건이 그 \
                질문에 걸려 있으면 comments는 빈 문자열로 둡니다 — 그 리뷰는 판매자가 전체를 봅니다. 근거끼리 같은 항목을 다르게 \
                말해 어느 쪽인지 정할 수 없으면 conflict를 true로 쓰고 comments는 빈 문자열로 둡니다.

                근거마다 「근거 n」 이름이 붙어 있습니다. 답글이 근거에서 가져온 사실 · 조건 · 약속 · 사용 팁이 있으면, 그 근거의 \
                이름과 그 근거에서 쓴 문장을 한 글자도 바꾸지 말고 그대로 옮겨 used에 적습니다(근거마다 하나). 답글이 근거를 \
                쓰지 않았으면 used는 빈 목록입니다. used에 적지 않은 근거의 내용은 답글에 쓰지 않습니다 — 적은 근거만 답글의 \
                근거로 인정되고, 검토 · 저장 · 나중의 재검사도 그 근거만 봅니다. [과거 답변]은 used에 적지 않습니다.

                반드시 아래 형태의 JSON 객체 하나만 출력하세요. 다른 텍스트, 설명, 코드펜스는 금지입니다.
                {"comments":"<답글 본문>","unanswered":[],"conflict":false,\
                "used":[{"id":"근거 <번호>","quote":"<그 근거의 문장 그대로>"}]}
                """);
        return sb.toString().strip();
    }

    public static String draftUser(String reviewBody, List<AgentDraftGenerator.Passage> knowledge, String voice,
                                   List<String> banned, String manner, String previousDraft, List<String> failedRules,
                                   String critiqueNote) {
        return draftUser(reviewBody, List.of(), knowledge, voice, banned, manner, previousDraft, failedRules,
                critiqueNote);
    }

    public static String draftUser(String reviewBody, List<String> points, List<AgentDraftGenerator.Passage> knowledge,
                                   String voice, List<String> banned, String manner, String previousDraft,
                                   List<String> failedRules, String critiqueNote) {
        StringBuilder sb = new StringBuilder();
        sb.append("고객 리뷰:\n").append(reviewBody == null ? "" : reviewBody);
        appendPoints(sb, points);
        appendKnowledge(sb, knowledge);
        if (voice != null && !voice.isBlank()) {
            sb.append("\n\n회사 기본 문구(표현 참고):\n").append(voice.strip());
        }
        if (banned != null && !banned.isEmpty()) {
            sb.append("\n\n회사가 쓰지 않는 표현:");
            for (String phrase : banned) {
                sb.append("\n- \"").append(phrase).append('"');
            }
        }
        if (manner != null && !manner.isBlank()) {
            sb.append("\n\n답변 스타일:\n").append(manner.strip());
        }
        if (previousDraft != null && !previousDraft.isBlank()) {
            sb.append("\n\n지난 초안:\n").append(previousDraft.strip());
            sb.append("\n\n검토에서 걸린 기준: ").append(failedRules == null ? "" : String.join(", ", failedRules));
            if (critiqueNote != null && !critiqueNote.isBlank()) {
                sb.append("\n검토 메모:\n").append(critiqueNote.strip());
            }
        }
        return sb.toString().strip();
    }

    // ── critique ───────────────────────────────────────────────────────────────────────────────────────────

    public static String critiqueSystem(ReplyStandard standard, ReplyStandard.Category category) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                당신은 판매자의 공개 리뷰 답글 초안을 검토합니다. 아래 규칙을 하나씩 확인하고, 초안이 어긴 규칙의 번호만 고릅니다. \
                어기지 않았으면 고르지 않습니다. N으로 시작하는 규칙(법·플랫폼)은 어겼는지 애매하면 어긴 것으로 고릅니다 — 잘못 \
                고르면 한 번 다시 쓰면 되지만, 놓치면 공개 답글이 됩니다. G·F 규칙은 분명히 어겼을 때만 고릅니다.

                「답할 점」 목록은 판단 단계가 짚은 참고일 뿐입니다. 고객 리뷰 원문을 직접 읽고 초안과 비교해, 목록이 놓친 문제 · \
                질문까지 빠졌으면 G8로 고릅니다. 「후보」 근거를 약속이나 상품 사실로 쓴 문장은 N9로 고릅니다.
                「판매자에게 따로 넘긴 질문」이 주어지면, 그 질문은 근거가 답하지 못해 판매자가 따로 답을 정합니다. 초안이 그 \
                질문을 다루지 않은 것은 G2 · G8이 아닙니다. 초안이 그 질문에 근거 없이 답했거나 나중에 알려 주겠다고 미뤘으면 N9입니다.

                N9는 말하는 사람과 뜻으로 판단합니다. 고객이 겪은 일을 되짚는 말(「반품까지 고민하게 해 드린 점 죄송합니다」)은 \
                약속이 아닙니다. 판매자가 해 주겠다 · 된다 · 신청하면 된다고 말하는 문장만 약속입니다. 근거가 조건(미개봉 · 기한 \
                · 대상)을 붙여 허용한 것을 그 조건 없이 된다고 쓴 문장도 N9입니다. 고객이 이미 그 조건에 해당한다고 밝혔으면 \
                아닙니다. 고객이 이미 개봉 · 사용했다고 밝혔는데 그 조건이 붙은 교환 · 반품을 된다고 쓴 문장은 조건을 옆에 \
                적었더라도 N9입니다. 반품 · 환불을 미개봉으로 한정하거나 개봉을 이유로 거절한 문장, 불량 · 오배송을 말한 고객에게 \
                미개봉 · 단순 변심 조건을 붙인 문장은 근거에 그렇게 적혀 있어도 N8입니다. 근거나 회사 기본 문구에 없는 후속 안내 · \
                서비스 보장(「안내받으실 수 있습니다」), 운영 설명(「후기 하나하나 확인하고 있습니다」), 「살펴보겠습니다」, 적혀 \
                있지 않은 문의 경로는 N9입니다.

                F1의 「리뷰보다 긴 답글은 다시 본다」는 다시 보라는 신호입니다. 불필요하게 길거나 같은 말을 되풀이할 때만 F1로 \
                고릅니다. 고객의 질문에 답하려고 필요한 설명이 들어가 길어진 것은 F1이 아닙니다(2026-11-01 여섯 번째 검토).
                F1 · G4 · N9의 「해결 경로」 · 「경로 안내」는 근거나 회사 기본 문구에 경로가 적혀 있을 때만 요구합니다. 적힌 경로가 \
                없으면 사과 · 공감으로 맺은 답글을 경로가 없다는 이유로 고르지 않습니다 — 경로를 지어내게 하는 지적이 됩니다 \
                (2026-11-01).

                아직 해결할 일이 없는데 — 고객이 이미 해결해 쓰고 있거나 해결을 바라지 않는데 — 교환 · 반품 · 환불 기준을 덧붙였으면 \
                F2입니다. 이 리뷰의 상황을 읽은 본문이 아닙니다(2026-11-01 일곱 번째 검토).
                답글이 근거의 뜻을 지켰는지 봅니다(2026-11-01 다섯 번째 검토). 근거에 없는 필수 조건이나 약속을 더했으면, 근거의 \
                중요한 제한 조건(기한 · 상태 · 대상 · 비용)을 뺐으면, 근거에 없는 운영 설명(「늘 권해 드리고 있습니다」)을 붙였으면 \
                N9입니다. 고객이 겪은 일을 상품의 일반적인 사실로 바꿨으면(「도배지에서는 붙지 않습니다」) G5입니다. 근거가 든 여러 \
                방법 중 하나를 「~하실 수 있습니다」처럼 고를 수 있는 방법으로 소개하는 것은 괜찮습니다 — 그것을 유일한 방법이나 꼭 \
                해야 하는 조치로 바꿨을 때만 고릅니다. 조건인지, 꼭 해야 하는지, 고를 수 있는지의 뜻을 근거대로 지켰는지가 기준입니다.

                """);
        sb.append("이 리뷰의 범주: ").append(category.code()).append(" — ").append(category.name()).append('\n');
        sb.append("이 범주의 순서: ").append(String.join(" → ", category.skeleton())).append('\n');
        sb.append("이 범주에서 하지 않는 것: ").append(String.join(" / ", category.mustNot())).append("\n\n규칙:\n");
        for (ReplyStandard.Rule rule : standard.rules()) {
            sb.append("- ").append(rule.id()).append(": ").append(rule.text()).append('\n');
        }
        sb.append("""

                note에는 그 규칙을 어떻게 고치면 되는지 한 문장으로 씁니다. 고객의 이름·연락처·주문번호는 쓰지 않습니다.
                반드시 아래 형태의 JSON 객체 하나만 출력하세요. 다른 텍스트, 설명, 코드펜스는 금지입니다.
                {"failed":[{"id":"<규칙 번호>","note":"<고칠 점 한 문장>"}]}
                """);
        return sb.toString().strip();
    }

    public static String critiqueUser(String reviewBody, List<AgentDraftGenerator.Passage> knowledge, String draft) {
        return critiqueUser(reviewBody, List.of(), knowledge, draft);
    }

    public static String critiqueUser(String reviewBody, List<String> points, List<AgentDraftGenerator.Passage> knowledge,
                                      String draft) {
        return critiqueUser(reviewBody, points, knowledge, draft, List.of());
    }

    public static String critiqueUser(String reviewBody, List<String> points, List<AgentDraftGenerator.Passage> knowledge,
                                      String draft, List<String> handedOver) {
        StringBuilder sb = new StringBuilder();
        sb.append("고객 리뷰:\n").append(reviewBody == null ? "" : reviewBody);
        appendPoints(sb, points);
        if (handedOver != null && !handedOver.isEmpty()) {
            sb.append("\n\n판매자에게 따로 넘긴 질문(근거가 답하지 못함 — 이 답글은 다루지 않음):");
            for (String q : handedOver) {
                sb.append("\n- ").append(q);
            }
        }
        appendKnowledge(sb, knowledge);
        sb.append("\n\n검토할 초안:\n").append(draft == null ? "" : draft.strip());
        return sb.toString().strip();
    }

    private static void appendKnowledge(StringBuilder sb, List<AgentDraftGenerator.Passage> knowledge) {
        sb.append("\n\n판매자가 등록한 근거:\n");
        if (knowledge == null || knowledge.isEmpty()) {
            sb.append("(없음)");
            return;
        }
        for (AgentDraftGenerator.Passage passage : knowledge) {
            sb.append("- ");
            if (passage.scopeLabel() != null && !passage.scopeLabel().isBlank()) {
                sb.append('[').append(passage.scopeLabel()).append("] ");
            }
            sb.append('[').append(passage.heading() == null ? "" : passage.heading()).append("] ")
                    .append(passage.text() == null ? "" : passage.text()).append('\n');
        }
    }
}
