package com.sellerops.vendor.task;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * <b>The same text fingerprints the same here as in the backend</b> — vectors computed on 2026-10-20 by running the
 * backend's own {@code com.sellerops.attention.reply.ReviewReplyFingerprint.of} on these exact strings.
 */
class ReplyTextTest {

    @Test
    void theBackendsReviewReplyFingerprintIsReproduced() {
        assertThat(ReplyText.fingerprint("감사합니다.\n다음에도 찾아주세요!"))
                .isEqualTo("5fc703aa61a45c8294711f8d56e274e3340a01c142e6550b44528cded30434e1");
        assertThat(ReplyText.fingerprint("Thanks \"quoted\" \\ back"))
                .isEqualTo("6924c3fd5f45c87abf17f5aeb723a5c966a2f6bca13235efd8a0782d6bb09f23");
        assertThat(ReplyText.fingerprint("배송이 늦어 죄송합니다. 고객센터 02-123-4567"))
                .isEqualTo("0c8138d564541a74a8369af7ca03ccb4bb82d59006618038bcd03e589c3309c5");
        assertThat(ReplyText.fingerprint("tab\there\u0001ctl"))
                .isEqualTo("019b7642aef81b6f090a916b145e49ab09d5d612c0dc011968b29e32f91efe3e");
    }

    @Test
    void normalizationIsTheBackendsAndTheBoundIsBytes() {
        assertThat(ReplyText.normalize("  첫 줄\r\n둘째 줄\r셋째  \n")).isEqualTo("첫 줄\n둘째 줄\n셋째");
        assertThat(ReplyText.storable("")).isFalse();
        assertThat(ReplyText.storable("가".repeat(1333))).isTrue();
        assertThat(ReplyText.storable("가".repeat(1334))).as("3 bytes each; 4002 > 4000").isFalse();
    }

    @Test
    void theChecksFlagContactsAndLinksAndNothingElse() {
        assertThat(ReplyText.checks("감사합니다. 문의는 010-1234-5678 또는 help@example.com, www.example.com"))
                .containsExactly("CONTACT_PHONE", "CONTACT_EMAIL", "URL");
        assertThat(ReplyText.checks("고객센터 02 123 4567")).containsExactly("CONTACT_PHONE");
        assertThat(ReplyText.checks("소중한 리뷰 감사합니다. 3일 안에 다시 보내 드릴게요.")).isEmpty();
    }
}
