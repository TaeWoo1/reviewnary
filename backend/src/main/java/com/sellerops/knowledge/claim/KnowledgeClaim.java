package com.sellerops.knowledge.claim;

import java.text.NumberFormat;
import java.util.Locale;

/**
 * <b>One value the seller's material states, read as 「what it is about · under which condition · how much」</b>
 * (2026-11-15, {@code DESIGN_conflict.md} §4). Read by {@link ClaimReader} from a clause, never inferred.
 *
 * @param attribute  what the value is about — one of the closed list
 * @param condition  under which condition it holds (단순 변심 / 불량 / 지역), {@link ClaimCondition#GENERAL} when none is said
 * @param leg        for a shipping fee, whether it is one way or both ways; null when the text does not say
 * @param low        the value, or a range's lower end (원 · 일 · 시 on the 24-hour clock)
 * @param high       a range's upper end; equal to {@code low} for a single value
 * @param unit       the unit as stated — business days are their own unit and are not compared with days
 * @param historical the clause says what the value used to be (「그 전에는 5만원 이상 무료였습니다」): never compared, and
 *                   never a current fact
 * @param clause     the clause it was read from, as written
 * @param payer      for a shipping fee, who the text says pays it; null when it does not say (2026-11-15 review: 편도 ×2 =
 *                   왕복 is the same amount, never by itself the same payer)
 */
public record KnowledgeClaim(ClaimAttribute attribute, ClaimCondition condition, Leg leg, long low, long high,
                             ClaimAttribute.Unit unit, boolean historical, String clause, Payer payer) {

    /** Who pays a fee, as the text says it. */
    public enum Payer {
        CUSTOMER("고객 부담"), SELLER("판매자 부담");

        private final String labelKo;

        Payer(String labelKo) {
            this.labelKo = labelKo;
        }

        public String labelKo() {
            return labelKo;
        }
    }

    /** A value whose text names no payer. */
    public KnowledgeClaim(ClaimAttribute attribute, ClaimCondition condition, Leg leg, long low, long high,
                          ClaimAttribute.Unit unit, boolean historical, String clause) {
        this(attribute, condition, leg, low, high, unit, historical, clause, null);
    }

    /** The same value read under another condition — for comparing a reply that names none with a rule that names one. */
    public KnowledgeClaim under(ClaimCondition other) {
        return new KnowledgeClaim(attribute, other, leg, low, high, unit, historical, clause, payer);
    }

    public enum Leg {
        ONE_WAY("편도"), ROUND_TRIP("왕복");

        private final String labelKo;

        Leg(String labelKo) {
            this.labelKo = labelKo;
        }

        public String labelKo() {
            return labelKo;
        }
    }

    /** The value as a customer would read it: 「7일」, 「1~2일」, 「왕복 6,000원」, 「오후 3시」. */
    public String valueKo() {
        String n = low == high ? number(low) : number(low) + "~" + number(high);
        return switch (unit) {
            case DAY -> n + "일";
            case BUSINESS_DAY -> "영업일 " + n + "일";
            case WON -> (leg == null ? "" : leg.labelKo() + " ") + n + "원";
            case HOUR -> low >= 12 ? "오후 " + (low == 12 ? 12 : low - 12) + "시" : "오전 " + low + "시";
        };
    }

    /** The value with its payer when the text names one: 「왕복 6,000원(고객 부담)」. */
    public String valueWithPayerKo() {
        return payer == null ? valueKo() : valueKo() + "(" + payer.labelKo() + ")";
    }

    /** The attribute with its condition, as the seller reads it: 「단순 변심 반품 배송비」. */
    public String subjectKo() {
        return condition == ClaimCondition.GENERAL ? attribute.labelKo()
                : condition.labelKo() + " " + attribute.labelKo();
    }

    /** Which comparison this value belongs to: the same attribute under the same condition in the same unit. */
    public String slot() {
        return attribute.name() + "|" + condition.name() + "|" + unit.name();
    }

    private static String number(long v) {
        return NumberFormat.getIntegerInstance(Locale.KOREA).format(v);
    }
}
