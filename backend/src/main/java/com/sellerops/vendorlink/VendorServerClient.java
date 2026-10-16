package com.sellerops.vendorlink;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

/**
 * The calls this PC makes to the vendor server for its own registration — contract §2 and §7. Every method either
 * returns a closed result or throws {@link Unreachable}; nothing here carries request material in an error.
 */
public interface VendorServerClient {

    record Code(String deviceCode, String userCode, Instant expiresAt, int intervalSeconds) {}

    /** What one poll of the grant learned. */
    sealed interface Poll permits Pending, Expired, Linked {}

    record Pending() implements Poll {}

    record Expired() implements Poll {}

    record Linked(String token, UUID installId, Instant expiresAt) implements Poll {}

    /** What the server says this PC's public address is. */
    sealed interface PublicIp permits Ipv4, NoIpv4, NotAuthorized {}

    record Ipv4(String address) implements PublicIp {}

    /** The server was reached over IPv6; there is no IPv4 to register. */
    record NoIpv4() implements PublicIp {}

    /** The install token was refused — revoked, expired, or the account is suspended. */
    record NotAuthorized() implements PublicIp {}

    Code requestCode(String deviceName, String appVersion, String publicKeyBase64);

    Poll poll(String deviceCode);

    PublicIp publicIp(String installToken);

    /** The vendor server's approval page with this code filled in — opened in the seller's default browser. */
    URI approvalPage(String userCode);

    /** Removes this install on the server. Best effort: the local record is dropped either way. */
    void unlink(String installToken);

    /** The server could not be reached or answered something this client does not understand. */
    final class Unreachable extends RuntimeException {
        public Unreachable() {
            super("공급자 서버에 연결하지 못했습니다.");
        }
    }
}
