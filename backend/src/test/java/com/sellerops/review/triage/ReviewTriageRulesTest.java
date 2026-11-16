package com.sellerops.review.triage;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The tier rule, over its whole input space.
 *
 * <p>The tests that matter most here are {@link #textMovesOnlyAThreeStarAndOnlyDownToFyi} and
 * {@link #noTextCanReachOrLeaveNeedsAttention}. Everything else checks a table; those check the BOUNDARY the slice
 * exists to hold — {@code contracts/review-eval/naver/v1/RUBRIC.md} §5 forbids surfacing an unmeasured text detector,
 * and the way that rule gets broken in practice is not by someone adding a detector on purpose. It is by a well-meant
 * "if the body mentions 파손, bump it up", which reads as an improvement and is exactly the gated thing. Since
 * 2026-10-27 (product-owner decision, RUBRIC v2 §2.4) text may LOWER a 3★ from 지켜보기 to 참고 when it carries no
 * reservation, and nothing else: no rating but 3 is read for content, and 확인 필요 is never entered or left by it.
 */
class ReviewTriageRulesTest {

    /**
     * 2026-11-15 owner decision (RUBRIC v2 §2.4 amendment): a 3★ that says a surface defect HAPPENED stays 지켜보기. An
     * unmeasured exception — these cases pin the behaviour, they measure nothing.
     */
    @ParameterizedTest
    @ValueSource(strings = {"식기세척기 돌렸더니 겉이 벗겨졌어요.", "도장이 까졌어요", "한 달 만에 변색됐어요", "색이 변색돼서 아쉽",
            "손잡이가 녹슬었어요", "나사에 녹이 슬었네요", "뚜껑이 갈라졌어요", "바닥에 크랙이 생겼어요", "모서리가 부서져서 왔어요"})
    void aSurfaceDefectIsAReservation(String body) {
        assertThat(ReviewTriageRules.reservationFree(body)).isFalse();
        assertThat(ReviewTriageRules.tier(3, body)).isEqualTo(ReviewTriageTier.WATCH);
    }

    /** The contrasts: a wrapper taken off and 「없이」 name no defect; a negation keeps the older words' reading. */
    @org.junit.jupiter.api.Test
    void aWrapperTakenOffOrNoDefectIsNotADefect() {
        for (String body : List.of("포장을 벗겨 보니 색이 예뻐요", "비닐 벗겨서 바로 썼어요 만족합니다", "포장 벗기자마자 향이 좋아요",
                "변색 없이 깨끗하게 잘 쓰고 있어요")) {
            assertThat(ReviewTriageRules.reservationFree(body)).as(body).isTrue();
            assertThat(ReviewTriageRules.tier(3, body)).as(body).isEqualTo(ReviewTriageTier.FYI);
        }
        // Marked by words that were there before this amendment (않 · 없어), not by it — and it does not change them.
        for (String body : List.of("식세기에 돌려도 전혀 벗겨지지 않아요", "녹슬지 않고 좋아요", "변색이 없어요")) {
            assertThat(ReviewTriageRules.tier(3, body)).as(body).isEqualTo(ReviewTriageTier.WATCH);
            String older = body.replace("벗겨지", "").replace("녹슬", "").replace("변색", "");
            assertThat(ReviewTriageRules.reservationFree(older)).as("still marked without the new words: " + older).isFalse();
        }
        assertThat(ReviewTriageRules.tier(5, "겉이 벗겨졌어요")).as("only 3★ is read").isEqualTo(ReviewTriageTier.FYI);
    }

    @Test
    void aLowRatingWithSomethingToReadIsTheTopOfTheList() {
        assertThat(ReviewTriageRules.tier(1, "접착력이 약해서 떨어졌어요"))
                .isEqualTo(ReviewTriageTier.NEEDS_ATTENTION);
        assertThat(ReviewTriageRules.tier(2, "포장이 찌그러져서 왔어요"))
                .isEqualTo(ReviewTriageTier.NEEDS_ATTENTION);
    }

    @Test
    void aLowRatingWithNothingWrittenIsWatchedRatherThanQueued() {
        // RUBRIC §2: "Low rating, no text → NO_ACTION. There is nothing to detect." Not demoted to
        // 참고 either — the rating is real and still counts.
        assertThat(ReviewTriageRules.tier(1, "")).isEqualTo(ReviewTriageTier.WATCH);
        assertThat(ReviewTriageRules.tier(1, "   ")).isEqualTo(ReviewTriageTier.WATCH);
        assertThat(ReviewTriageRules.tier(2, null)).isEqualTo(ReviewTriageTier.WATCH);
    }

    @Test
    void aMiddlingRatingIsWatched() {
        assertThat(ReviewTriageRules.tier(3, "그럭저럭 쓸만합니다")).isEqualTo(ReviewTriageTier.WATCH);
        assertThat(ReviewTriageRules.tier(3, "")).isEqualTo(ReviewTriageTier.WATCH);
        assertThat(ReviewTriageRules.tier(3, "   ")).isEqualTo(ReviewTriageTier.WATCH);
    }

    /** The owner's judging sample, 2026-10-27: 3★ that only praise, thank or name the product. */
    @ParameterizedTest
    @ValueSource(strings = {
            "감사합니다  잘쓰겠습니다",
            "매일 쓰기 편하고 마음에 들어요. 만족해요.",
            "디자인이 이뻐서 좋아요.",
            "나누리샵 세모금생수컵 일회용생수컵",
            "배송 포장 제품 만족해요!",
    })
    void aMiddlingRatingThatOnlyPraisesIsFyi(String praise) {
        assertThat(ReviewTriageRules.tier(3, praise)).isEqualTo(ReviewTriageTier.FYI);
    }

    /** The same sample's 3★ that each name one thing wrong — they stay where the seller watches. */
    @ParameterizedTest
    @ValueSource(strings = {
            "재구매 좋은데 접착력이 .. 따로 접착제 사용",
            "쓰기는 편하지만, 접착테이프는 꼭 보완이 필요해요. 벽지에도 잘 안붙어요.",
            "양면테이프 빼고는 괜찮아요",
            "종이는 얇지만 값이 싸서 좋아요",
            "깔끔함은 좋으나 접착력은 약함.",
            "가운데서 걸려요......",
            "버튼 누르면 컵이 자꾸 씹힘",
            "아이가 다쳤어요",
            "곰팡이가 피었어요",
    })
    void aMiddlingRatingWithAReservationIsWatched(String reservation) {
        assertThat(ReviewTriageRules.tier(3, reservation)).isEqualTo(ReviewTriageTier.WATCH);
    }

    @Test
    void anUnknownRatingIsWatchedBecauseUnknownIsNotGoodNews() {
        assertThat(ReviewTriageRules.tier(null, "본문은 있는데 별점이 없습니다"))
                .isEqualTo(ReviewTriageTier.WATCH);
        assertThat(ReviewTriageRules.tier(null, null)).isEqualTo(ReviewTriageTier.WATCH);
    }

    @Test
    void aGoodRatingCarriesNoAction() {
        assertThat(ReviewTriageRules.tier(4, "만족합니다")).isEqualTo(ReviewTriageTier.FYI);
        assertThat(ReviewTriageRules.tier(5, "")).isEqualTo(ReviewTriageTier.FYI);
    }

    /**
     * Bodies chosen to be the tempting ones: every one of them is a complaint a keyword pass would
     * love to promote, and two are the exact 5★-with-a-complaint case RUBRIC §2 calls
     * {@code NEEDS_LOOK}. v1 does not detect it, does not approximate it, and this pins that the code
     * has not quietly started to.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "배송이 너무 늦었어요",
            "제품이 파손되어 도착했습니다",
            "불량입니다 환불해주세요",
            "예쁜데 택배 기사님이 던지고 갔어요",
            "설치하다가 깨졌어요 최악",
    })
    void textMovesOnlyAThreeStarAndOnlyDownToFyi(String complaint) {
        for (Integer rating : new Integer[] {null, 1, 2, 4, 5}) {
            assertThat(ReviewTriageRules.tier(rating, complaint))
                    .as("rating %s with a complaint body must tier exactly as any other non-blank body",
                            rating)
                    .isEqualTo(ReviewTriageRules.tier(rating, "본문"));
        }
        assertThat(ReviewTriageRules.tier(3, complaint))
                .as("a 3★ complaint stays where the seller watches")
                .isEqualTo(ReviewTriageTier.WATCH);
    }

    @Test
    void noTextCanReachOrLeaveNeedsAttention() {
        for (String body : List.of("만족합니다", "본문", "불량입니다 환불해주세요", "아이가 다쳤어요")) {
            assertThat(ReviewTriageRules.tier(3, body)).isNotEqualTo(ReviewTriageTier.NEEDS_ATTENTION);
            assertThat(ReviewTriageRules.tier(4, body)).isEqualTo(ReviewTriageTier.FYI);
            assertThat(ReviewTriageRules.tier(1, body)).isEqualTo(ReviewTriageTier.NEEDS_ATTENTION);
        }
    }

    /** The SQL rank spells the markers out; it must spell exactly these, in this order, and nothing else. */
    @Test
    void theSqlRankNamesExactlyTheJavaMarkers() {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("locate\\('([^']+)', r\\.body\\) = 0")
                .matcher(com.sellerops.review.ReviewRepository.RESERVATION_FREE);
        List<String> sql = new java.util.ArrayList<>();
        while (m.find()) {
            sql.add(m.group(1));
        }
        assertThat(sql).isNotEmpty().containsExactlyElementsOf(ReviewTriageRules.RESERVATION_MARKERS);
        assertThat(ReviewTriageRules.RESERVATION_MARKERS).containsAll(ReviewSafetySignal.stems());
        assertThat(com.sellerops.review.ReviewRepository.TRIAGE_TIER_RANK)
                .contains(com.sellerops.review.ReviewRepository.RESERVATION_FREE);
    }

    @Test
    void theRankIsTheOrderAnOperatorReadsIn() {
        assertThat(List.of(ReviewTriageTier.NEEDS_ATTENTION, ReviewTriageTier.WATCH, ReviewTriageTier.FYI)
                .stream().map(ReviewTriageRules::rank).toList())
                .containsExactly(0, 1, 2);
    }

    @Test
    void everyTierHasARankAndNoTwoShareOne() {
        // Guards the switch against a future value being added with no rank, and against two tiers
        // colliding — either would make the worklist order silently arbitrary rather than fail.
        assertThat(java.util.Arrays.stream(ReviewTriageTier.values()).map(ReviewTriageRules::rank).toList())
                .doesNotHaveDuplicates()
                .hasSize(ReviewTriageTier.values().length);
    }

    @Test
    void blanknessIsOneDefinition() {
        assertThat(ReviewTriageRules.isTextless(null)).isTrue();
        assertThat(ReviewTriageRules.isTextless("")).isTrue();
        assertThat(ReviewTriageRules.isTextless("   ")).isTrue();
        assertThat(ReviewTriageRules.isTextless("ㅎ")).isFalse();
    }

    @Test
    void anUnknownTierFilterIsRefusedRatherThanIgnored() {
        assertThat(ReviewTriageTier.parse("NEEDS_ATTENTION")).isEqualTo(ReviewTriageTier.NEEDS_ATTENTION);
        assertThat(ReviewTriageTier.parse(" WATCH ")).isEqualTo(ReviewTriageTier.WATCH);
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> ReviewTriageTier.parse("URGENT"))
                .hasMessageContaining("알 수 없는");
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> ReviewTriageTier.parse(" "))
                .hasMessageContaining("지정해");
    }
}
