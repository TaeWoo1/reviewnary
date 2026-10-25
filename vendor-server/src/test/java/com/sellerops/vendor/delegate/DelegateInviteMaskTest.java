package com.sellerops.vendor.delegate;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DelegateInviteMaskTest {

    @Test
    void theMaskKeepsTwoLettersAndTheDomainAndNeverTheWholeName() {
        assertThat(DelegateAuthController.mask("reviewnary.kr@gmail.com")).isEqualTo("re***@gmail.com");
        assertThat(DelegateAuthController.mask("ab@example.com")).isEqualTo("a***@example.com");
        assertThat(DelegateAuthController.mask("a@example.com")).isEqualTo("a***@example.com");
        assertThat(DelegateAuthController.mask("no-at-sign")).isEqualTo("***");
    }
}
