package com.sellerops.knowledge.teach;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.review.media.ReviewMedia;
import com.sellerops.review.media.ReviewMediaPresentation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The case screen and the review screen say the same sentence for every photo state — one rule, not two. */
class CaseMediaStatusSharedTest {

    @Test
    @DisplayName("케이스 화면의 사진 문장은 리뷰 화면과 같은 한 곳에서 나온다")
    void oneSentencePerState() {
        for (ReviewMedia.InspectionStatus st : ReviewMedia.InspectionStatus.values()) {
            for (boolean on : new boolean[] {true, false}) {
                assertThat(CaseKnowledgeService.mediaStatusKo(st, on))
                        .isEqualTo(ReviewMediaPresentation.statusKo(st, on))
                        .isNotBlank();
            }
        }
    }
}
