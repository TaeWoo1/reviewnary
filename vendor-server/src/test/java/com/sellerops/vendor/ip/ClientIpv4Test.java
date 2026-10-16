package com.sellerops.vendor.ip;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ClientIpv4Test {

    @Test
    void anIpv4CallerGetsItsAddress() {
        assertThat(ClientIpv4.of("203.0.113.7")).contains("203.0.113.7");
        assertThat(ClientIpv4.of(" 198.51.100.255 ")).contains("198.51.100.255");
    }

    @Test
    void anIpv4MappedIpv6CallerGetsTheIpv4() {
        assertThat(ClientIpv4.of("::ffff:203.0.113.7")).contains("203.0.113.7");
        assertThat(ClientIpv4.of("::FFFF:203.0.113.7")).contains("203.0.113.7");
    }

    @Test
    void anythingElseGetsNothing() {
        for (String other : List.of("2001:db8::1", "::1", "256.1.1.1", "203.0.113", "203.0.113.7.1", "01.2.3.4",
                "", "localhost")) {
            assertThat(ClientIpv4.of(other)).as(other).isEmpty();
        }
        assertThat(ClientIpv4.of(null)).isEmpty();
    }
}
