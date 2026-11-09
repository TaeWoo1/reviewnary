package com.sellerops.inquiry.draft;

import com.sellerops.inquiry.decision.NeedDecision;
import com.sellerops.inquiry.decision.NeedStatus;
import com.sellerops.inquiry.sellercheck.SellerCheckOutcome;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>A question to the customer nobody asked for</b> (2026-11-06 thirteenth review). Asking back is not claiming a fact,
 * so it is allowed — but only what the judgement left for the customer to say: a need judged
 * {@code CONDITIONAL_ON_CUSTOMER} with what to ask, the 규격 line telling the drafter to ask which one, or the seller's
 * record saying the seller needs something from the customer first. A draft that asks anything when none of those holds is
 * asking something that was never judged necessary — R2 (2026-11-05) asked which colour the customer wanted when the seller
 * had recorded the exchange as received, two times in three.
 *
 * <p>Deterministic and narrow on purpose: it reads only sentences ending in a question mark, and only when the judgement
 * allows no question at all. When one is allowed, whether the draft asked that one and nothing else is the claim check's
 * (inquiry-claim-check/v13) — a sentence cannot be matched to a need by its words. A closing like 「궁금한 점은 말씀해
 * 주세요」 is not a question and is not read.
 */
public final class CustomerAskGuard {

    /** A sentence ending in a question mark — from the previous terminator or line break. */
    private static final Pattern QUESTION = Pattern.compile("[^.!?。？\\n]*[?？]");

    private CustomerAskGuard() {
    }

    /** Whether this judgement leaves anything for the customer to say. */
    public static boolean asksAllowed(NeedDecision decision, SellerCheckOutcome recorded, String specSentence) {
        if (recorded == SellerCheckOutcome.NEEDS_CUSTOMER_INFO) {
            return true;
        }
        if (specSentence != null && specSentence.contains("되물으세요")) {
            return true;
        }
        return decision != null && decision.needs().stream()
                .anyMatch(n -> n.status() == NeedStatus.CONDITIONAL_ON_CUSTOMER);
    }

    /**
     * The questions {@code body} puts to the customer when the judgement allows none, as written; empty when it allows
     * one, or when there is no need decision to read (the older path, whose 규격 ask has its own line).
     */
    public static List<String> unrequestedAsks(String body, NeedDecision decision, SellerCheckOutcome recorded,
                                               String specSentence) {
        if (body == null || body.isBlank() || decision == null || asksAllowed(decision, recorded, specSentence)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        Matcher m = QUESTION.matcher(body);
        while (m.find()) {
            String q = m.group().strip();
            if (q.length() > 1) {
                out.add(q);
            }
        }
        return List.copyOf(out);
    }
}
