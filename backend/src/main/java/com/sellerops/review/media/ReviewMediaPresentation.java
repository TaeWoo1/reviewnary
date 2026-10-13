package com.sellerops.review.media;

/**
 * <b>How one review photo is described to the seller — one rule, used by every screen that shows one.</b>
 *
 * <p>The case screen ({@code CaseKnowledgeService}) and the review screen ({@link ReviewMediaReadService}) both
 * say whether Reviewnary actually looked at a photo. They must never come to disagree about the sentence, so the
 * sentence lives here once.
 */
public final class ReviewMediaPresentation {

    private ReviewMediaPresentation() {
    }

    /** The seller's sentence for an inspection state. {@code on} = the vision capability is on for this org. */
    public static String statusKo(ReviewMedia.InspectionStatus status, boolean on) {
        return switch (status) {
            case INSPECTED -> "Reviewnary가 사진을 확인했습니다.";
            case NOT_INSPECTED -> on ? "아직 사진을 확인하지 않았습니다." : "사진 확인 기능이 꺼져 있어 사진 내용은 보지 않았습니다.";
            case FETCH_FAILED -> "사진을 가져오지 못해 내용은 보지 않았습니다.";
            case MODEL_FAILED -> "사진을 확인하지 못했습니다.";
            case NOT_AN_IMAGE -> "사진이 아니어서 내용은 보지 않았습니다.";
        };
    }
}
