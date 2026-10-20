package com.sellerops.vendorlink;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The calls this PC makes to the vendor server — its own registration (contract §2 · §7) and the review relay
 * (§3.2). Every method either returns a closed result or throws {@link Unreachable} (or, for the relay,
 * {@link Refused}); nothing here carries request material in an error.
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

    /* ── review relay (§3.2) ── */

    /** A store of this install's seller account. {@code channel} is the server's closed value (e.g. {@code NAVER}). */
    record Store(UUID id, String channel, String label) {}

    /** One review as the server holds it now. Times are instants; {@code attachmentUrl} is the one kept address. */
    record FeedReview(UUID id, String reviewNo, String productNo, String productName, int rating, String body,
                      String bodyFingerprint, String attachmentUrl, String attachmentKind, Instant writtenAt,
                      String displayState, boolean replied, Instant repliedAt) {}

    /** {@code kind} is {@code NEW}, {@code CHANGED} or {@code REPLIED}; the review is its state as of this event. */
    record FeedEvent(long seq, String kind, FeedReview review) {}

    record FeedPage(List<FeedEvent> events, long cursor, boolean more) {}

    /** This install's seller account's stores. */
    List<Store> stores(String installToken);

    /** The store's review events after {@code cursor}, oldest first. */
    FeedPage reviewFeed(String installToken, UUID storeId, long cursor, int limit);

    /** Tells the server this PC stored everything up to {@code cursor}. */
    void ackReviews(String installToken, UUID storeId, long cursor);

    /** The install token or the store was refused — revoked, suspended, or not this account's. */
    final class Refused extends RuntimeException {
        public Refused() {
            super("공급자 서버가 이 PC의 요청을 받지 않았습니다.");
        }
    }

    /** The server could not be reached or answered something this client does not understand. */
    final class Unreachable extends RuntimeException {
        public Unreachable() {
            super("공급자 서버에 연결하지 못했습니다.");
        }
    }
}
