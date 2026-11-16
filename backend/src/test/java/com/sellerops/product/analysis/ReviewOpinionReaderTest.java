package com.sellerops.product.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.reviewissue.RuleBasedIssueSignatureExtractor;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The praise half of the product analysis reads the same units the issue extractor reads, and only
 * reports praise the customer actually wrote about a named aspect (2026-11-16, night unit ②).
 */
class ReviewOpinionReaderTest {

    private final ReviewOpinionReader reader = new ReviewOpinionReader(new RuleBasedIssueSignatureExtractor(false));

    private List<String> praised(String body) {
        return reader.read(body).stream().filter(ReviewOpinionReader.Opinion::praise)
                .map(o -> o.aspect() == null ? "-" : o.aspect()).toList();
    }

    private List<String> complained(String body) {
        return reader.read(body).stream().filter(o -> !o.praise())
                .map(o -> o.aspect() + ":" + o.problem()).toList();
    }

    @Test
    @DisplayName("praise of a named aspect is read per unit; the complaint beside it stays a complaint")
    void praiseAndComplaintInOneReview() {
        String body = "배송이 정말 빨라요. 그런데 포장 박스가 찌그러져서 왔어요.";
        assertThat(praised(body)).containsExactly("배송");
        assertThat(complained(body)).containsExactly("포장:파손");
    }

    @Test
    @DisplayName("「파손없이 잘 도착」 is praise of 배송, not a 파손 complaint")
    void negatedProblemWithPraise() {
        assertThat(praised("파손없이 잘 도착했어요")).containsExactly("배송");
        assertThat(complained("파손없이 잘 도착했어요")).isEmpty();
    }

    @Test
    @DisplayName("negated, wished-for, questioned and opposite-prefixed praise is not praise")
    void notPraise() {
        assertThat(praised("설치가 쉽지 않아요")).isEmpty();
        assertThat(praised("포장이 안 좋아요")).isEmpty();
        assertThat(praised("색상이 만족스럽지 않아요")).isEmpty();
        assertThat(praised("포장이 더 꼼꼼했으면 좋겠어요")).isEmpty();
        assertThat(praised("설치 쉬운가요?")).isEmpty();
        assertThat(praised("설치가 불편해요")).isEmpty();
        assertThat(praised("색상이 아쉽네요")).isEmpty();
    }

    @Test
    @DisplayName("contrast: praise past a clause boundary keeps its praise (「튼튼해서 걱정 없어요」)")
    void clauseBoundaryKeepsPraise() {
        assertThat(praised("포장이 튼튼해서 걱정 없어요")).containsExactly("포장");
    }

    @Test
    @DisplayName("praise that names no aspect is unattributed — it never becomes a strength, whatever the rating")
    void praiseWithoutAspect() {
        assertThat(praised("너무 좋아요! 최고예요")).containsOnly("-");
    }

    @Test
    @DisplayName("praise of another company's product is not this product's strength")
    void anotherProduct() {
        assertThat(praised("다른 제품은 설치가 쉬웠어요")).isEmpty();
    }
}
