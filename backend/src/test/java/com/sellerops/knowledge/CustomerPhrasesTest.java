package com.sellerops.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 「고객은 이렇게 말해요」 — the seller's list, matched as written, with one guard for a denial (2026-10-27). */
class CustomerPhrasesTest {

    private static final List<String> ADHESION = CustomerPhrases.parse("떨어져요\n들떠, 접착력 약해요\n떨어졌");

    @Test
    @DisplayName("a list is one phrase per line or comma, trimmed, deduplicated, and a single syllable is not a phrase")
    void aListIsParsedTheWayPeopleTypeIt() {
        assertThat(CustomerPhrases.parse(" 떨어져요 ,「들떠」\n\n떨어져요\n약,  접착력   약해요 "))
                .containsExactly("떨어져요", "들떠", "접착력 약해요");
        assertThat(CustomerPhrases.store(List.of())).isNull();
        assertThat(CustomerPhrases.store(List.of("떨어져요", " 들떠 "))).isEqualTo("떨어져요\n들떠");
        assertThat(CustomerPhrases.parse(null)).isEmpty();
        String many = String.join("\n", java.util.stream.IntStream.range(0, 60).mapToObj(i -> "표현" + i).toList());
        assertThat(CustomerPhrases.parse(many)).hasSize(CustomerPhrases.MAX_PHRASES);
        assertThat(CustomerPhrases.overflows(many)).as("said, not silently dropped").isTrue();
        assertThat(CustomerPhrases.overflows("떨어져요\n들떠")).isFalse();
    }

    @Test
    @DisplayName("a review that says it is a hit, spacing and case aside")
    void aReviewThatSaysItIsAHit() {
        assertThat(CustomerPhrases.firstHit("붙여 놓고 하루 만에 다 떨어졌어요 ㅠ", ADHESION)).contains("떨어졌");
        assertThat(CustomerPhrases.firstHit("접착력약해요 다른 테이프 덧댔어요", ADHESION)).contains("접착력 약해요");
        assertThat(CustomerPhrases.firstHit("디자인 예뻐요", ADHESION)).isEmpty();
    }

    @Test
    @DisplayName("a denial is not a hit — 「안 떨어져요」, 「들뜨지 않아요」 — but a phrase that is itself a negation is read as written")
    void aDenialIsNotAHit() {
        assertThat(CustomerPhrases.firstHit("한 달 됐는데 안 떨어져요 최고", ADHESION)).isEmpty();
        assertThat(CustomerPhrases.firstHit("잘 붙고 들뜨지 않아요", CustomerPhrases.parse("들뜨"))).isEmpty();
        // Measured on the owner's store: 「잘떨어」 caught 「잘 떨어지지 않고 튼튼해서 좋아요」.
        assertThat(CustomerPhrases.firstHit("잘 떨어지지 않고 튼튼해서 좋아요", CustomerPhrases.parse("잘떨어")))
                .isEmpty();
        assertThat(CustomerPhrases.firstHit("테이프가 잘 떨어짐", CustomerPhrases.parse("잘떨어"))).contains("잘떨어");
        assertThat(CustomerPhrases.firstHit("자석이 안 붙어요", CustomerPhrases.parse("안 붙어요"))).contains("안 붙어요");
        // One denial does not hide a second, real mention later in the same review.
        assertThat(CustomerPhrases.firstHit("처음엔 안 떨어져요 했는데 일주일 뒤 떨어져요", ADHESION)).contains("떨어져요");
    }

    @Test
    @DisplayName("a problem said to be absent is a denial — 「떨어짐 없이」 — but a complaint that only ends in 없 is not")
    void absenceIsADenial() {
        // Measured on the owner's store (2026-10-27 evaluation): 「깔끔하고 떨어짐없이 잘 쓰고 있어요」 got the adhesion note.
        assertThat(CustomerPhrases.firstHit("깔끔하고 떨어짐없이 잘 쓰고 있어요", CustomerPhrases.parse("떨어짐"))).isEmpty();
        assertThat(CustomerPhrases.firstHit("떨어짐 없이 잘 써요", CustomerPhrases.parse("떨어"))).isEmpty();
        assertThat(CustomerPhrases.firstHit("들뜸이 없어요", CustomerPhrases.parse("들뜸"))).isEmpty();
        assertThat(CustomerPhrases.firstHit("접착력이 약해서 걱정 없이 실리콘으로 붙였어요", CustomerPhrases.parse("접착력이 약")))
                .contains("접착력이 약");
        assertThat(CustomerPhrases.firstHit("하루만에 떨어짐 ㅠㅠ 방법이 없네요", CustomerPhrases.parse("떨어짐")))
                .contains("떨어짐");
    }
}
