package com.sellerops.vendor.install;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class InstallPublicKeyTest {

    @Test
    void exactlyThirtyTwoNonZeroBytesInEitherBase64Alphabet() {
        byte[] key = new byte[32];
        key[31] = (byte) 0xff;
        key[0] = (byte) 0xfb;
        assertThat(InstallPublicKey.parse(Base64.getEncoder().encodeToString(key))).hasValue(key);
        assertThat(InstallPublicKey.parse(Base64.getUrlEncoder().withoutPadding().encodeToString(key))).hasValue(key);
        assertThat(InstallPublicKey.parse(Base64.getEncoder().encodeToString(new byte[32]))).as("all zero").isEmpty();
        assertThat(InstallPublicKey.parse(Base64.getEncoder().encodeToString(new byte[31]))).isEmpty();
        assertThat(InstallPublicKey.parse(Base64.getEncoder().encodeToString(new byte[33]))).isEmpty();
        assertThat(InstallPublicKey.parse("not base64!")).isEmpty();
        assertThat(InstallPublicKey.parse(null)).isEmpty();
    }
}
