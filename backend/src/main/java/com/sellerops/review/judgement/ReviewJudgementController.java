package com.sellerops.review.judgement;

import com.sellerops.auth.AuthPrincipal;
import com.sellerops.operationscase.OperationsCaseRules;
import com.sellerops.review.Review;
import com.sellerops.review.ReviewRepository;
import com.sellerops.review.triage.ReviewTriageRules;
import com.sellerops.review.triage.pilot.AiTriagePilotService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * <b>The one judgement beside the readers it is to replace</b> (2026-10-28, {@code docs/standing/reply_agent_v1.md}
 * §3-4). Read-only and model-free: the latest {@link ReviewJudgement} of each review next to what the screen tier, the
 * case rule and the drafting-ahead AI say of the same review today, so where they disagree is counted before any screen
 * switches. No review text leaves — closed words, the rating, and the judge's points.
 *
 * <p><b>Agreement is not accuracy.</b> Two readers that agree can both be wrong; this finds where they differ, so a
 * person can look there. Accuracy is measured against reviews the seller labelled (the evaluation's gold cases), never
 * against an older reader. Model-free: every value here is one already stored — a reader with nothing stored is
 * 미평가, not a call.
 */
@RestController
@RequestMapping("/api/review-judgement")
public class ReviewJudgementController {

    private final ReviewJudgementRepository judgements;
    private final ReviewRepository reviews;
    private AiTriagePilotService pilot;

    public ReviewJudgementController(ReviewJudgementRepository judgements, ReviewRepository reviews) {
        this.judgements = judgements;
        this.reviews = reviews;
    }

    @Autowired(required = false)
    public void setPilot(AiTriagePilotService pilot) {
        this.pilot = pilot;
    }

    /** One review: the AI judgement, and the three readers' answers today. */
    public record Compared(UUID reviewId, Integer rating, String category, String alsoCategories, String actions,
                           String unsureReason, String safetySignal, String handoffReason, List<String> points,
                           String screenTier, boolean caseRuleNeedsLook, Boolean draftingAheadNeedsResponse,
                           boolean agreesWithScreenTier, Boolean agreesWithDraftingAhead) {
    }

    @GetMapping("/compare")
    public List<Compared> compare(@AuthenticationPrincipal AuthPrincipal principal) {
        List<Compared> out = new ArrayList<>();
        for (ReviewJudgement j : judgements.findTop200ByOrgIdOrderByJudgedAtDesc(principal.orgId())) {
            Review r = reviews.findByIdAndOrgId(j.getReviewId(), principal.orgId()).orElse(null);
            if (r == null) {
                continue;
            }
            String tier = ReviewTriageRules.tier(r.getRating(), r.getBody()).name();
            boolean caseLook = OperationsCaseRules.forReview(r.getRating(), r.getBody(), r.getReplyState())
                    .needsInvestigation();
            // Stored only: a comparison never classifies (second review, 2026-10-28) — no verdict yet is 미평가 (null).
            Boolean ahead = pilot == null ? null : pilot.storedVerdict(principal.orgId(), r).orElse(null);
            // The stored decision, the one drafting ahead reads — never a second computation of it.
            boolean aiNeeds = j.isNeedsResponse();
            // Agreement on the one question all of them answer — does this review need the seller? — per reader, and
            // 미평가 (null) where a reader stored nothing.
            boolean withTier = aiNeeds == !"FYI".equals(tier);
            Boolean withAhead = ahead == null ? null : aiNeeds == ahead;
            out.add(new Compared(r.getId(), r.getRating(), j.getCategory(), j.getAlsoCategories(), j.getActions(),
                    j.getUnsureReason(), j.getSafetySignal(), j.getHandoffReason(),
                    j.getPoints() == null || j.getPoints().isBlank() ? List.of() : List.of(j.getPoints().split("\n")),
                    tier, caseLook, ahead, withTier, withAhead));
        }
        return List.copyOf(out);
    }
}
