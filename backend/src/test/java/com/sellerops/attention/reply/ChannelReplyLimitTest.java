package com.sellerops.attention.reply;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The channel's length limit is a constraint with its own code and its own sentence (2026-11-01 seventh review). */
class ChannelReplyLimitTest {

    @Test
    void naverTakesTwoHundredCharactersAndOthersStateNone() {
        assertThat(ChannelReplyLimit.refusal("NAVER", "가".repeat(200))).isEmpty();
        assertThat(ChannelReplyLimit.refusal("NAVER", "가".repeat(201))).get().asString()
                .contains("200자까지").contains("201자");
        assertThat(ChannelReplyLimit.refusal("CAFE24", "가".repeat(900))).isEmpty();
        assertThat(ChannelReplyLimit.lengthOf("😊".repeat(3))).as("characters, not UTF-16 units").isEqualTo(3);
    }
}
