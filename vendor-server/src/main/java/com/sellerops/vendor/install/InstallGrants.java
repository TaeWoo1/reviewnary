package com.sellerops.vendor.install;

import com.sellerops.vendor.Codes;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * <b>Pending install grants — the RFC 8628 device-code half, in memory.</b> Same shape as the backend's
 * {@code HelperDeviceGrants} (contract §2), with one addition: the grant carries the install's public key from the
 * first request, so what the seller approves is that key, and the token issued later is bound to it.
 *
 * <p>The PC gets a {@code deviceCode} (32 random bytes — the PC's own secret, used to poll) and a short
 * {@code userCode} the seller approves while logged in. Both are kept hashed. Guessing a user code only binds a
 * stranger's pending PC to the guesser's own account, and {@link #MAX_PENDING} bounds how many are pending at all.
 *
 * <p>In memory because the server is one process (contract §9); a restart mid-link costs the seller one more press.
 */
@Component
public class InstallGrants {

    /** Long enough to log in on a phone and type eight characters. */
    public static final Duration TTL = Duration.ofMinutes(10);
    public static final int POLL_INTERVAL_SECONDS = 3;
    public static final int MAX_PENDING = 100;
    private static final char[] USER_CODE_ALPHABET = "BCDFGHJKLMNPQRSTVWXZ23456789".toCharArray();
    private static final int USER_CODE_LENGTH = 8;

    public enum PollOutcome { AUTHORIZATION_PENDING, EXPIRED_TOKEN, APPROVED }

    public record Issued(String deviceCode, String userCode, Instant expiresAt, int intervalSeconds) {}

    /**
     * What the PC sent, plus the IPv4 it asked from ({@code null} when unknown or IPv6). The address is held only
     * for the grant's ten minutes, in memory, to tell the approval page whether the approving browser is on the
     * same network as the PC — it is never written anywhere.
     */
    public record Pending(String deviceName, String appVersion, byte[] publicKey, String requesterIpv4) {}

    public record Poll(PollOutcome outcome, UUID accountId, Pending pending) {}

    private static final class Grant {
        final String userCodeHash;
        final Instant expiresAt;
        final Pending pending;
        UUID approvedBy;

        Grant(String userCodeHash, Instant expiresAt, Pending pending) {
            this.userCodeHash = userCodeHash;
            this.expiresAt = expiresAt;
            this.pending = pending;
        }
    }

    private final Clock clock;
    /** deviceCodeHash → grant. */
    private final Map<String, Grant> byDeviceCode = new HashMap<>();
    /** userCodeHash → deviceCodeHash. */
    private final Map<String, String> byUserCode = new HashMap<>();

    @Autowired
    public InstallGrants() {
        this(Clock.systemUTC());
    }

    InstallGrants(Clock clock) {
        this.clock = clock;
    }

    /** Empty when {@link #MAX_PENDING} grants are already live. */
    public synchronized Optional<Issued> issue(Pending pending) {
        Instant now = clock.instant();
        sweep(now);
        if (byDeviceCode.size() >= MAX_PENDING) {
            return Optional.empty();
        }
        String deviceCode = Codes.newCode();
        String userCode;
        do {
            userCode = Codes.fromAlphabet(USER_CODE_ALPHABET, USER_CODE_LENGTH);
        } while (byUserCode.containsKey(Codes.hash(userCode)));
        Instant expiresAt = now.plus(TTL);
        Grant grant = new Grant(Codes.hash(userCode), expiresAt, pending);
        byDeviceCode.put(Codes.hash(deviceCode), grant);
        byUserCode.put(grant.userCodeHash, Codes.hash(deviceCode));
        return Optional.of(new Issued(deviceCode, userCode, expiresAt, POLL_INTERVAL_SECONDS));
    }

    /** The seller's session approves a pending grant. Returns the device name it approved, or empty. */
    public synchronized Optional<String> approve(String userCode, UUID accountId) {
        sweep(clock.instant());
        String deviceCodeHash = byUserCode.get(Codes.hash(normalizeUserCode(userCode)));
        Grant grant = deviceCodeHash == null ? null : byDeviceCode.get(deviceCodeHash);
        if (grant == null || grant.approvedBy != null) {
            return Optional.empty();
        }
        grant.approvedBy = accountId;
        return Optional.of(grant.pending.deviceName());
    }

    /** The still-unapproved grant a user code names, without approving or consuming it. */
    public synchronized Optional<Pending> peek(String userCode) {
        sweep(clock.instant());
        String deviceCodeHash = byUserCode.get(Codes.hash(normalizeUserCode(userCode)));
        Grant grant = deviceCodeHash == null ? null : byDeviceCode.get(deviceCodeHash);
        return grant == null || grant.approvedBy != null ? Optional.empty() : Optional.of(grant.pending);
    }

    /** An approved grant is handed over exactly once: a second poll with the same device code is expired. */
    public synchronized Poll poll(String deviceCode) {
        sweep(clock.instant());
        String hash = Codes.hash(deviceCode == null ? "" : deviceCode);
        Grant grant = byDeviceCode.get(hash);
        if (grant == null) {
            return new Poll(PollOutcome.EXPIRED_TOKEN, null, null);
        }
        if (grant.approvedBy == null) {
            return new Poll(PollOutcome.AUTHORIZATION_PENDING, null, null);
        }
        byDeviceCode.remove(hash);
        byUserCode.remove(grant.userCodeHash);
        return new Poll(PollOutcome.APPROVED, grant.approvedBy, grant.pending);
    }

    /** Case- and separator-insensitive: {@code BCDF-GHJK} and {@code bcdfghjk} are the same code. */
    public static String normalizeUserCode(String raw) {
        return raw == null ? "" : raw.replaceAll("[\\s-]", "").toUpperCase(java.util.Locale.ROOT);
    }

    private void sweep(Instant now) {
        Iterator<Map.Entry<String, Grant>> it = byDeviceCode.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Grant> e = it.next();
            if (!e.getValue().expiresAt.isAfter(now)) {
                byUserCode.remove(e.getValue().userCodeHash);
                it.remove();
            }
        }
    }
}
