package com.sellerops.intakealert;

import com.sellerops.channel.Channel;
import com.sellerops.channel.ChannelRepository;
import com.sellerops.inquiry.Inquiry;
import com.sellerops.inquiry.InquiryRepository;
import com.sellerops.itemanalysis.ItemAnalysis;
import com.sellerops.itemanalysis.ItemAnalysisRepository;
import com.sellerops.operationscase.OperationsCaseRules;
import com.sellerops.review.Review;
import com.sellerops.review.ReviewReplyState;
import com.sellerops.review.ReviewRepository;
import com.sellerops.review.triage.ReviewSafetySignal;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Reads the original rows an alert is about — <b>always inside the alert's org</b> (a row of another org reads as absent)
 * — and turns each into closed facts only: kind, channel code, reason, topic label, whether it is already handled, when
 * it arrived and when the customer wrote it. The customer's text is read here to classify a safety report
 * ({@link ReviewSafetySignal}, the classifier the home screen uses) and is never carried further.
 */
@Component
public class IntakeAlertSubjects {

    /** Rating at or below this is a low-rating review (the home's ★1–2 risk tier). */
    static final int LOW_RATING_MAX = 2;

    public record Fact(IntakeAlertItem.Kind kind, UUID id, String channelCode, IntakeAlertItem.Reason reason,
                       String issueLabel, boolean handled, Instant ingestedAt, Instant receivedAt) {

        public boolean urgent() {
            return reason == IntakeAlertItem.Reason.SAFETY;
        }
    }

    private final ReviewRepository reviews;
    private final InquiryRepository inquiries;
    private final ChannelRepository channels;
    private final ItemAnalysisRepository analyses;

    public IntakeAlertSubjects(ReviewRepository reviews, InquiryRepository inquiries, ChannelRepository channels,
                               ItemAnalysisRepository analyses) {
        this.reviews = reviews;
        this.inquiries = inquiries;
        this.channels = channels;
        this.analyses = analyses;
    }

    public Map<UUID, Fact> reviews(UUID orgId, Collection<UUID> ids) {
        Map<UUID, Fact> out = new HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        Map<UUID, String> labels = labels(orgId, "REVIEW", ids);
        Map<UUID, String> codes = new HashMap<>();
        for (Review r : reviews.findAllById(ids)) {
            if (!orgId.equals(r.getOrgId())) {
                continue;
            }
            out.put(r.getId(), new Fact(IntakeAlertItem.Kind.REVIEW, r.getId(), code(codes, r.getChannelId()),
                    reviewReason(r), labels.get(r.getId()), reviewHandled(r), r.getCreatedAt(), r.getReceivedAt()));
        }
        return out;
    }

    public Map<UUID, Fact> inquiries(UUID orgId, Collection<UUID> ids) {
        Map<UUID, Fact> out = new HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        Map<UUID, String> labels = labels(orgId, "INQUIRY", ids);
        Map<UUID, String> codes = new HashMap<>();
        for (Inquiry q : inquiries.findAllById(ids)) {
            if (!orgId.equals(q.getOrgId())) {
                continue;
            }
            IntakeAlertItem.Reason reason = safetyReport(q.getBody()) ? IntakeAlertItem.Reason.SAFETY
                    : IntakeAlertItem.Reason.NEW_INQUIRY;
            out.put(q.getId(), new Fact(IntakeAlertItem.Kind.INQUIRY, q.getId(), code(codes, q.getChannelId()), reason,
                    labels.get(q.getId()), inquiryHandled(q), q.getCreatedAt(), q.getReceivedAt()));
        }
        return out;
    }

    public Map<UUID, Fact> of(UUID orgId, IntakeAlertItem.Kind kind, Collection<UUID> ids) {
        return kind == IntakeAlertItem.Kind.REVIEW ? reviews(orgId, ids) : inquiries(orgId, ids);
    }

    static IntakeAlertItem.Reason reviewReason(Review r) {
        if (safetyReport(r.getBody())) {
            return IntakeAlertItem.Reason.SAFETY;
        }
        if (r.getRating() != null && r.getRating() <= LOW_RATING_MAX) {
            return IntakeAlertItem.Reason.LOW_RATING;
        }
        return IntakeAlertItem.Reason.NEW_REVIEW;
    }

    /** A concrete report (OCCURRENCE), not a worry (「다칠까 걱정」) — owner Q39: a concrete risk, even one. */
    static boolean safetyReport(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        return ReviewSafetySignal.find(body).filter(f -> f.reading() == ReviewSafetySignal.Reading.OCCURRENCE)
                .isPresent();
    }

    /** The channel already says the seller answered (or the seller's reply was observed). */
    static boolean reviewHandled(Review r) {
        return r.getReplyState() == ReviewReplyState.ANSWERED || r.getRepliedAt() != null || r.getSellerReplyAt() != null;
    }

    /** The automatic check would not work on it: answered, not operational, or a thread copy. */
    static boolean inquiryHandled(Inquiry q) {
        return !OperationsCaseRules.forInquiry(q.getStatus(), q.getOperationalState(), q.getThreadRole())
                .needsInvestigation();
    }

    private Map<UUID, String> labels(UUID orgId, String type, Collection<UUID> ids) {
        Map<UUID, String> out = new HashMap<>();
        for (ItemAnalysis a : analyses.findByOrgIdAndSourceTypeAndSourceIdIn(orgId, type, ids)) {
            String c = a.getCategory();
            if (c != null && com.sellerops.itemanalysis.ItemAnalysisCategories.SUPPORTED.contains(c)) {
                out.put(a.getSourceId(), c);
            }
        }
        return out;
    }

    private String code(Map<UUID, String> cache, UUID channelId) {
        if (channelId == null) {
            return null;
        }
        return cache.computeIfAbsent(channelId,
                id -> channels.findById(id).map(Channel::getCode).orElse(null));
    }
}
