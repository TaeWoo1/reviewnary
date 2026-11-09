package com.sellerops.review.response;

import com.sellerops.common.ApiException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read and save one org's 리뷰 응대 기준. The org always comes from the caller's token, never from the request. */
@Service
public class ReviewResponseRuleService {

    private final ReviewResponseRuleRepository rules;

    public ReviewResponseRuleService(ReviewResponseRuleRepository rules) {
        this.rules = rules;
    }

    /** What the screen shows and saves: the three modes, and whether they are the seller's own or the defaults. */
    public record View(String low, String mid, String high, boolean customized) {
    }

    public record Request(String low, String mid, String high) {
    }

    @Transactional(readOnly = true)
    public ReviewResponseRules.Modes modesFor(UUID orgId) {
        return ReviewResponseRules.Modes.of(rules.findById(orgId).orElse(null));
    }

    @Transactional(readOnly = true)
    public View view(UUID orgId) {
        ReviewResponseRule row = rules.findById(orgId).orElse(null);
        ReviewResponseRules.Modes modes = ReviewResponseRules.Modes.of(row);
        return new View(modes.low().name(), modes.mid().name(), modes.high().name(), row != null);
    }

    @Transactional
    public View save(UUID orgId, Request request, UUID actorUserId) {
        if (request == null) {
            throw ApiException.badRequest("응대 기준을 보내 주세요.");
        }
        ReviewResponseRule row = rules.findById(orgId).orElseGet(() -> {
            ReviewResponseRule fresh = new ReviewResponseRule();
            fresh.setOrgId(orgId);
            return fresh;
        });
        row.setLowMode(parse(request.low()));
        row.setMidMode(parse(request.mid()));
        row.setHighMode(parse(request.high()));
        row.setUpdatedBy(actorUserId);
        row.setUpdatedAt(Instant.now());
        rules.save(row);
        return view(orgId);
    }

    private static ReviewResponseMode parse(String raw) {
        try {
            return ReviewResponseMode.valueOf(raw == null ? "" : raw.strip());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("응대 기준은 AHEAD · ISSUES_ONLY · ON_REQUEST 중 하나예요.");
        }
    }
}
