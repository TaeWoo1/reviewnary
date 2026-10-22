package com.sellerops.review.triage;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.review.ReviewRepository;
import com.sellerops.review.triage.ReviewSafetySignal.Kind;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * <b>What a customer has to have written for a review to rise ahead of the inquiries</b> — and the happy sentences
 * built from the same words that must not (product-owner decision, 2026-10-22; RUBRIC §7).
 */
class ReviewSafetySignalTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "아이가 쓰다가 손에 화상을 입었어요. 손잡이가 너무 뜨거워져요",
            "모서리에 다쳤어요",
            "먹고 나서 배탈이 났습니다 ★5 준 건 실수",
            "피가 났는데 연락이 없어요",
            "바르고 두드러기가 올라왔어요",
            "충전하다가 타는 냄새가 나요",
            "타는냄새",
            "안에서 이물질이 나왔어요",
            "소비자원에 신고했습니다",
            "환불 안 해주시면 고소하겠습니다",
            "먹고 병원에 갔어요",
            "사용하다 화상을 입었어요",
            // A worry is the customer telling the seller the product may hurt someone (2026-10-23).
            "너무 뜨거워서 화상 입을까 걱정돼요",
            "아이가 다칠까 봐 염려됩니다",
    })
    void aReportOfHarmRises(String body) {
        assertThat(ReviewSafetySignal.in(body)).as(body).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "아이가 다칠 걱정 없어요",
            "뜨겁지 않고 화상 걱정 없어요",
            "다칠 걱정은 전혀 없어요",
            "이물질 없이 깨끗해요",
            "알레르기 반응 없이 잘 쓰고 있어요",
            "화상 방지 손잡이라 안심이에요",
            "곰팡이가 싹 지워져요",
            "화상회의용으로 샀는데 화질이 좋아요",
            "식탁에 상처가 하나 있었어요",
            "포장이 터졌어요",
            "기분이 상했어요",
            "고소하고 맛있어요",
            "병원에서 쓰려고 샀어요",
            "흡입력 폭발",
            "배송이 빨라요",
            "",
    })
    void theSameWordsInAHappyOrUnrelatedSentenceDoNot(String body) {
        assertThat(ReviewSafetySignal.in(body)).as(body).isFalse();
    }

    @Test
    void nothingIsNotASignal() {
        assertThat(ReviewSafetySignal.in(null)).isFalse();
    }

    @Test
    void theKindIsTheReasonASellerCanBeGiven() {
        assertThat(ReviewSafetySignal.of("손을 데였어요")).contains(Kind.INJURY);
        assertThat(ReviewSafetySignal.of("먹고 구토를 했어요")).contains(Kind.BODY_REACTION);
        assertThat(ReviewSafetySignal.of("콘센트에서 불꽃이 튀었어요")).contains(Kind.PRODUCT_HAZARD);
        assertThat(ReviewSafetySignal.of("식약처에 문의하겠습니다")).contains(Kind.LEGAL);
    }

    @Test
    void aNegatedFirstMentionDoesNotHideALaterReport() {
        assertThat(ReviewSafetySignal.in("다칠 걱정 없다고 해서 샀는데 결국 다쳤어요")).isTrue();
        // The same word twice: the first is negated, the second is the report.
        assertThat(ReviewSafetySignal.in("이물질 없다고 해서 샀는데 이물질이 나왔어요")).isTrue();
    }

    @Test
    void theDatabaseFilterNamesExactlyTheOpeningsThisClassReads() {
        Set<String> inQuery = new TreeSet<>();
        Matcher m = Pattern.compile("r\\.body like '%([^%']+)%'").matcher(ReviewRepository.SAFETY_STEM_PREDICATE);
        while (m.find()) {
            inQuery.add(m.group(1));
        }
        assertThat(inQuery).isNotEmpty().isEqualTo(ReviewSafetySignal.stems());
        assertThat(ReviewSafetySignal.TERMS.keySet())
                .as("every term starts with a stem the query lets through")
                .allMatch(term -> inQuery.contains(term.substring(0, 2)));
    }
}
