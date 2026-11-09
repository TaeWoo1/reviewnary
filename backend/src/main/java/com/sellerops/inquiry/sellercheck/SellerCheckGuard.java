package com.sellerops.inquiry.sellercheck;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A reply may call the action finished only when the seller recorded it finished (2026-11-02 seller-check review:
 * 「‘환불 접수’를 ‘환불 완료’로 바꾸거나, 진행 중이라는 이유로 완료 예정일을 만들어내지 않게」).
 *
 * <p>Deterministic and narrow: it reads the reply for a finished-action verb in the past or settled form — 환불했습니다,
 * 취소되었습니다, 발급을 마쳤습니다, 처리 완료되었습니다 — and refuses it unless the current record is {@code DONE}.
 * 「접수되었습니다」 is not a finished action and is not read. A promise of a later answer is the claim guard's
 * {@code SELLER_PROMISE}, not this.
 */
public final class SellerCheckGuard {

    private static final Pattern FINISHED = Pattern.compile(
            "(환불|취소|반품|교환|발급|재발송|발송|처리|입금|정산)\\s*(을|를|가|이|도)?\\s*"
                    + "(완료했|완료하였|완료되었|완료됐|완료되어|완료 되었|완료입니다|마쳤|마치었|"
                    + "했습니다|하였습니다|해 드렸|해드렸|되었습니다|됐습니다)");

    private SellerCheckGuard() {
    }

    /** The finished-action phrases the reply states that the record does not allow; empty when it may. */
    public static List<String> unrecordedCompletions(String body, SellerCheckOutcome recorded) {
        if (body == null || body.isBlank() || recorded == null || recorded == SellerCheckOutcome.DONE) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        Matcher m = FINISHED.matcher(body);
        while (m.find()) {
            if (!out.contains(m.group())) {
                out.add(m.group());
            }
        }
        return List.copyOf(out);
    }
}
