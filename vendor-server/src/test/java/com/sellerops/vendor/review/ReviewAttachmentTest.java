package com.sellerops.vendor.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.vendor.review.ReviewAttachment.Kind;
import org.junit.jupiter.api.Test;

/** <b>Only NAVER's review-image host over https is kept</b> — contract §3.1 D5. All addresses are synthetic. */
class ReviewAttachmentTest {

    @Test
    void theObservedShapesAreKeptAndNamed() {
        assertThat(ReviewAttachment.screen("https://phinf.pstatic.net/checkout.phinf/20260101_1/abc_JPEG/x.jpg"))
                .hasValueSatisfying(a -> assertThat(a.kind()).isEqualTo(Kind.PHOTO));
        assertThat(ReviewAttachment.screen("https://phinf.pstatic.net/image.nmv/batch/abc.jpg"))
                .hasValueSatisfying(a -> assertThat(a.kind()).isEqualTo(Kind.VIDEO_STILL));
        assertThat(ReviewAttachment.screen("https://phinf.pstatic.net/other/abc.jpg"))
                .hasValueSatisfying(a -> assertThat(a.kind()).isEqualTo(Kind.IMAGE));
    }

    @Test
    void anythingElseIsRefusedNotKept() {
        for (String bad : new String[] {
                "http://phinf.pstatic.net/checkout.phinf/a/b.jpg",
                "https://phinf.pstatic.net.example.com/checkout.phinf/a/b.jpg",
                "https://evilphinf.pstatic.net/checkout.phinf/a/b.jpg",
                "https://phinf.pstatic.net:8443/checkout.phinf/a/b.jpg",
                "https://user@phinf.pstatic.net/checkout.phinf/a/b.jpg",
                "https://example.invalid/synthetic-media-1",
                "https://phinf.pstatic.net/",
                "javascript:alert(1)",
                "not a url",
                "https://phinf.pstatic.net/" + "a".repeat(ReviewAttachment.MAX_LENGTH)}) {
            assertThat(ReviewAttachment.refused(bad)).as(bad).isTrue();
            assertThat(ReviewAttachment.screen(bad)).as(bad).isEmpty();
        }
        assertThat(ReviewAttachment.refused("")).as("an empty cell is not a refusal").isFalse();
        assertThat(ReviewAttachment.refused(null)).isFalse();
    }
}
