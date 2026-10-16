package com.sellerops.vendor.ip;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The caller's public IPv4 address, or nothing — contract §7.
 *
 * <p>NAVER's call-IP registration takes IPv4 ({@code NaverAdvertisedEgress} in the backend accepts nothing else), so
 * an IPv6 caller gets no answer rather than an address that would be registered and never match. The service is
 * published on an A-record-only host name so this is the rare case; the PC falls back to manual guidance when it
 * happens.
 *
 * <p>The address arrives already resolved: the edge (Caddy) sets {@code X-Forwarded-For} and the servlet container
 * trusts it only from the internal proxy range ({@code server.forward-headers-strategy: native}).
 */
public final class ClientIpv4 {

    private static final Pattern IPV4 =
            Pattern.compile("(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])(\\.(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])){3}");
    private static final String MAPPED_PREFIX = "::ffff:";

    private ClientIpv4() {
    }

    public static Optional<String> of(String remoteAddress) {
        if (remoteAddress == null) {
            return Optional.empty();
        }
        String address = remoteAddress.trim();
        if (address.regionMatches(true, 0, MAPPED_PREFIX, 0, MAPPED_PREFIX.length())) {
            address = address.substring(MAPPED_PREFIX.length());
        }
        return IPV4.matcher(address).matches() ? Optional.of(address) : Optional.empty();
    }
}
