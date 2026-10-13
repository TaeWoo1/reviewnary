package com.sellerops.review.media;

import com.sellerops.common.ApiException;
import com.sellerops.product.detail.image.DetailImageFetcher;
import com.sellerops.review.ReviewRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>A review's own photos, for the review screen.</b> Read-only: nothing is inspected, nothing is written, no
 * model is called. Organisation from the caller's principal only; a review of another organisation is a 404,
 * exactly as {@code ReviewDetailController} answers it.
 *
 * <p>The bytes come from {@link ReviewMediaInspector#loadForSeller} — the one fetch, under the image fetch policy,
 * that the case screen already uses. This class constructs no fetcher and takes no address from a caller: the
 * only input to a fetch is a {@code review_media} row this organisation owns.
 */
@Service
public class ReviewMediaReadService {

    private final ReviewRepository reviews;
    private final ReviewMediaRepository media;
    private final ReviewMediaInspector inspector;

    public ReviewMediaReadService(ReviewRepository reviews, ReviewMediaRepository media,
                                  ReviewMediaInspector inspector) {
        this.reviews = reviews;
        this.media = media;
        this.inspector = inspector;
    }

    /** Every attachment of this organisation's review, described. 404 when the review is not theirs. */
    @Transactional(readOnly = true)
    public ReviewMediaView list(UUID orgId, UUID reviewId) {
        requireReview(orgId, reviewId);
        boolean on = inspector.enabledFor(orgId);
        List<ReviewMediaView.Item> items = media.findByOrgIdAndReviewIdOrderByOrdinalAsc(orgId, reviewId).stream()
                .map(m -> {
                    boolean inspected = m.getInspectionStatus() == ReviewMedia.InspectionStatus.INSPECTED;
                    boolean video = m.getMediaKind() == ReviewMedia.Kind.VIDEO;
                    return new ReviewMediaView.Item(m.getOrdinal(), m.getMediaKind().name(), inspected,
                            ReviewMediaPresentation.statusKo(m.getInspectionStatus(), on),
                            inspected ? m.getDepicts() : null,
                            inspected && m.getProblemVisible() != null ? m.getProblemVisible().name() : null,
                            inspected ? m.getProblemDescription() : null,
                            video ? null : imagePath(reviewId, m.getOrdinal()));
                })
                .toList();
        return new ReviewMediaView(items);
    }

    /**
     * One photo's bytes. Empty — a 404 to the caller — when the review is not this organisation's, the ordinal has
     * no attachment, it is a video, or the fetch was refused.
     */
    @Transactional(readOnly = true)
    public Optional<DetailImageFetcher.Loaded> image(UUID orgId, UUID reviewId, int ordinal) {
        if (orgId == null || reviewId == null || reviews.findByIdAndOrgId(reviewId, orgId).isEmpty()) {
            return Optional.empty();
        }
        return media.findByOrgIdAndReviewIdOrderByOrdinalAsc(orgId, reviewId).stream()
                .filter(m -> m.getOrdinal() == ordinal && m.getMediaKind() != ReviewMedia.Kind.VIDEO)
                .findFirst()
                .flatMap(inspector::loadForSeller);
    }

    /** The same-origin route that serves one photo of this review. */
    public static String imagePath(UUID reviewId, int ordinal) {
        return "/api/reviews/" + reviewId + "/media/" + ordinal;
    }

    private void requireReview(UUID orgId, UUID reviewId) {
        if (orgId == null || reviewId == null || reviews.findByIdAndOrgId(reviewId, orgId).isEmpty()) {
            throw ApiException.notFound("리뷰를 찾을 수 없습니다.");
        }
    }
}
