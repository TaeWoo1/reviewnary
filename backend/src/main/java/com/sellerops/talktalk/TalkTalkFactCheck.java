package com.sellerops.talktalk;

import com.sellerops.knowledge.claim.ClaimAttribute;
import com.sellerops.knowledge.claim.ClaimCondition;
import com.sellerops.knowledge.claim.KnowledgeClaim;
import com.sellerops.knowledge.currency.KnowledgeCurrencyService;
import com.sellerops.knowledge.currency.KnowledgeState;
import com.sellerops.knowledge.schedule.KnowledgeSchedule;
import com.sellerops.knowledge.spine.KnowledgeAuthority;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * <b>Does the seller's own material say the cut-off differently, or name products it does not hold for?</b>
 *
 * <p>The guidance is compared with every document in use through the same computed state the inquiry lane's search,
 * judge, drafter and rechecks read ({@link KnowledgeCurrencyService#stateFor} with the guidance as an extra source at
 * the seller-policy rank). A disagreement is never settled here in the guidance's favour: any conflict that touches
 * it sends the message to a person.
 *
 * <p><b>Applicability.</b> The guidance is the company's general rule. When the material says some product leaves on
 * another rule — a product document stating its own cut-off or a production time, a company document stating a
 * production time, or an active 입고·출고·생산 schedule line for a product — {@code productExceptions} is true, and a
 * conversation that names a product is a person's: the general rule may not be this product's.
 * Without the currency service the check reports UNAVAILABLE, which also blocks.
 */
@Component
public class TalkTalkFactCheck {

    public record Result(boolean clear, String reason, String sentence, boolean productExceptions) {
    }

    private static final Set<ClaimAttribute> EXCEPTION_ATTRIBUTES =
            Set.of(ClaimAttribute.DISPATCH_CUTOFF, ClaimAttribute.PRODUCTION_DAYS);

    private final ObjectProvider<KnowledgeCurrencyService> currency;

    public TalkTalkFactCheck(ObjectProvider<KnowledgeCurrencyService> currency) {
        this.currency = currency;
    }

    public Result check(UUID orgId, TalkTalkGuidance g, LocalDate today) {
        KnowledgeCurrencyService svc = currency.getIfAvailable();
        if (svc == null) {
            return new Result(false, "FACT_CHECK_UNAVAILABLE", null, true);
        }
        if (g == null || g.getCutoffHour() == null) {
            return new Result(false, "CUTOFF_MISSING", null, true);
        }
        String sentence = TalkTalkGuidanceRules.ruleSentence(g);
        KnowledgeClaim claim = new KnowledgeClaim(ClaimAttribute.DISPATCH_CUTOFF, ClaimCondition.GENERAL, null,
                g.getCutoffHour(), g.getCutoffHour(), ClaimAttribute.Unit.HOUR, false, sentence);
        KnowledgeState.Source source = new KnowledgeState.Source(g.getId(), "ORG", null, "ORG",
                "톡톡 배송 기준 v" + g.getVersion(), KnowledgeAuthority.SELLER_POLICY.rank(), null, List.of(claim));
        KnowledgeState state;
        KnowledgeState overview;
        try {
            state = svc.stateFor(orgId, null, today, null, List.of(source));
            overview = svc.overview(orgId);
        } catch (RuntimeException e) {
            return new Result(false, "FACT_CHECK_UNAVAILABLE", null, true);
        }
        boolean exceptions = exceptions(overview);
        List<KnowledgeState.Conflict> touching = state.touching(List.of(g.getId()));
        if (!touching.isEmpty()) {
            return new Result(false, "KNOWLEDGE_CONFLICT", touching.get(0).sentenceKo(), exceptions);
        }
        String minute = minuteMismatch(overview, g);
        if (minute != null) {
            return new Result(false, "KNOWLEDGE_CONFLICT", minute, exceptions);
        }
        return new Result(true, null, null, exceptions);
    }

    private static final java.util.regex.Pattern MINUTE = java.util.regex.Pattern.compile(
            "(\\d{1,2})\\s*시\\s*(\\d{1,2})\\s*분|(\\d{1,2}):(\\d{2})|(\\d{1,2})\\s*시\\s*반");

    /**
     * The claim reader reads a cut-off in whole hours, so 「12시 30분」 and 「12시」 read alike (review item 7). A company
     * document whose cut-off is the guidance's hour but another minute is a disagreement the state cannot see; it is
     * found here from the clause itself. Returns the clause, or null.
     */
    static String minuteMismatch(KnowledgeState overview, TalkTalkGuidance g) {
        for (KnowledgeState.Source s : overview.sources()) {
            if (!"ORG".equals(s.kind())) {
                continue;
            }
            for (KnowledgeClaim c : s.claims()) {
                if (c.historical() || c.attribute() != ClaimAttribute.DISPATCH_CUTOFF || c.low() != g.getCutoffHour()) {
                    continue;
                }
                int minute = 0;
                java.util.regex.Matcher m = MINUTE.matcher(c.clause() == null ? "" : c.clause());
                if (m.find()) {
                    minute = m.group(2) != null ? Integer.parseInt(m.group(2))
                            : m.group(4) != null ? Integer.parseInt(m.group(4)) : 30;
                }
                if (minute != g.getCutoffMinute()) {
                    return "「" + s.title() + "」의 출고 마감 「" + c.clause() + "」이 배송 기준과 분 단위로 달라요.";
                }
            }
        }
        return null;
    }

    static boolean exceptions(KnowledgeState overview) {
        for (KnowledgeState.Source s : overview.sources()) {
            for (KnowledgeClaim c : s.claims()) {
                if (c.historical()) {
                    continue;
                }
                boolean product = "PRODUCT".equals(s.kind());
                if ((product && EXCEPTION_ATTRIBUTES.contains(c.attribute()))
                        || c.attribute() == ClaimAttribute.PRODUCTION_DAYS) {
                    return true;
                }
            }
        }
        for (KnowledgeSchedule line : overview.schedules()) {
            if (line.isActive() && line.getProductId() != null) {
                return true;
            }
        }
        return false;
    }
}
