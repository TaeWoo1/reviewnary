package com.sellerops.review.draft;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.knowledge.RetrievalQuery;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The parts of a review that say what went wrong, and the query they lead (2026-10-27). */
class ReviewComplaintClausesTest {

    @Test
    void theComplaintIsCutAfterTheConcessionAndThePraiseIsLeftOut() {
        assertThat(ReviewComplaintClauses.of("디자인 좋고 설치도 쉬운데, 정말 접착력이 약하네요. 벽지에 붙였는데, 다음날 떨어지네요. ㅎㅎ"))
                .containsExactly("정말 접착력이 약하네요", "다음날 떨어지네요");
        assertThat(ReviewComplaintClauses.of("만족합니다. 사무실에 쓰려고 샀는데 벽지에는 부착력이 약해 쉽게 떨어지는 단점이 있어요."))
                .containsExactly("벽지에는 부착력이 약해 쉽게 떨어지는 단점이 있어요");
        assertThat(ReviewComplaintClauses.of("양면테이프 빼고는 괜찮아요")).containsExactly("양면테이프 빼고는");
    }

    @Test
    void praiseHasNoComplaintAndASoftenerOrATurnAloneIsNotOne() {
        assertThat(ReviewComplaintClauses.of("설치가 쉽고 정말 깔끔해서 좋아요!!")).isEmpty();
        // 「좀」 and 「는데」 mark a reservation for triage; in a part of their own they name no problem.
        assertThat(ReviewComplaintClauses.of("좀 더 사고 싶은데 품절이네요")).isEmpty();
        assertThat(ReviewComplaintClauses.of(null)).isEmpty();
    }

    @Test
    void theReviewQueryAsksTheComplaintFirstAndStillNeverMoreThanFourForms() {
        String body = "만족합니다. 사무실에 쓰려고 샀는데 벽지에는 부착력이 약해 쉽게 떨어지는 단점이 있어요.";
        RetrievalQuery query = RetrievalQuery.ofReview(ReviewComplaintClauses.of(body), body);
        assertThat(query.candidates().get(0))
                .isEqualTo(new RetrievalQuery.Candidate("벽지에는 부착력이 약해 쉽게 떨어지는 단점이 있어요",
                        RetrievalQuery.Origin.CLAUSE));
        assertThat(query.candidates()).hasSizeLessThanOrEqualTo(RetrievalQuery.MAX_CANDIDATES);
        assertThat(query.customerWritten()).isTrue();
        // With no complaint the review is asked exactly as it always was.
        assertThat(RetrievalQuery.ofReview(List.of(), "디자인이 이뻐서 좋아요.").candidates())
                .isEqualTo(RetrievalQuery.ofCustomer(null, "디자인이 이뻐서 좋아요.").candidates());
    }
}
