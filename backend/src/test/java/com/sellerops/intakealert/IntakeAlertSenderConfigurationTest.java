package com.sellerops.intakealert;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IntakeAlertSenderConfigurationTest {

    static IntakeAlertSender choose(String mode, boolean live, String key, String secret, String pf, String tpl,
                                    String from, String publicBase) {
        return IntakeAlertSenderConfiguration.choose(mode, live, "https://api.solapi.com", key, secret, pf, tpl, from,
                publicBase);
    }

    @Test
    @DisplayName("기본은 모의 — 밖으로 아무것도 보내지 않는다")
    void defaultIsMock() {
        assertThat(choose("mock", true, "k", "s", "p", "t", null, "https://x")).isInstanceOf(MockIntakeAlertSender.class);
    }

    @Test
    @DisplayName("solapi라도 live-enabled가 꺼져 있으면 BLOCKED(LIVE_OFF)")
    void liveOff() {
        IntakeAlertSender s = choose("solapi", false, "k", "s", "p", "t", null, "https://x");
        assertThat(s).isInstanceOf(BlockedIntakeAlertSender.class);
        assertThat(((BlockedIntakeAlertSender) s).reason()).isEqualTo("LIVE_OFF");
    }

    @Test
    @DisplayName("키·채널·템플릿·https 주소 중 빠진 것은 이름만 적고 BLOCKED")
    void missingNamesOnly() {
        IntakeAlertSender s = choose("solapi", true, "", "secret-value", "", "t", null, "http://localhost:5173");
        assertThat(((BlockedIntakeAlertSender) s).reason())
                .isEqualTo("MISSING:api-key,pf-id,public-base-url(https)").doesNotContain("secret-value");
    }

    @Test
    @DisplayName("발신번호는 선택 — 없어도 다른 필수값이 다 있으면 운영 어댑터")
    void senderNumberOptional() {
        assertThat(choose("solapi", true, "k", "s", "p", "t", null, "https://app")).isInstanceOf(SolapiAlimtalkSender.class);
        assertThat(choose("solapi", true, "k", "s", "p", "t", "", "https://app")).isInstanceOf(SolapiAlimtalkSender.class);
    }

    @Test
    @DisplayName("모르는 모드 값은 BLOCKED — 오타가 보내는 일은 없다")
    void unknownMode() {
        assertThat(((BlockedIntakeAlertSender) choose("Solapi-live", true, "k", "s", "p", "t", null, "https://a")).reason())
                .isEqualTo("UNKNOWN_MODE");
    }
}
