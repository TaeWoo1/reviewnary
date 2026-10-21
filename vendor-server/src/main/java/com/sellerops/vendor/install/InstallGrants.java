package com.sellerops.vendor.install;

import com.sellerops.vendor.Codes;
import com.sellerops.vendor.ip.ClientIpv4;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * <b>Pending install grants — the RFC 8628 device-code half, in memory.</b> Same shape as the backend's
 * {@code HelperDeviceGrants} (contract §2), with one addition: the grant carries the install's public key from the
 * first request, so what the seller approves is that key, and the token issued later is bound to it.
 *
 * <p>The PC gets a {@code deviceCode} (32 random bytes — the PC's own secret, used to poll) and a short
 * {@code userCode} the seller approves while logged in. Both are kept hashed. Guessing a user code only binds a
 * stranger's pending PC to the guesser's own account.
 *
 * <p><b>Nobody is logged in when a grant is issued</b>, so one caller must not be able to crowd out everyone else's
 * PC. Per {@link #source source} address, at most {@link #MAX_PENDING_PER_SOURCE} grants are pending — a sixth
 * displaces that address's oldest, so a seller pressing again is never refused — and at most
 * {@link #MAX_ISSUED_PER_SOURCE} are issued per {@link #TTL}. {@link #MAX_PENDING} bounds the whole, and is then out
 * of reach of fewer than {@code MAX_PENDING / MAX_PENDING_PER_SOURCE} addresses holding it for ten minutes.
 *
 * <p>In memory because the server is one process (contract §9); a restart mid-link costs the seller one more press.
 */
@Component
public class InstallGrants {

    /** Long enough to log in on a phone and type eight characters. */
    public static final Duration TTL = Duration.ofMinutes(10);
    public static final int POLL_INTERVAL_SECONDS = 3;
    public static final int MAX_PENDING = 1000;
    public static final int MAX_PENDING_PER_SOURCE = 5;
    public static final int MAX_ISSUED_PER_SOURCE = 20;
    /** Bounds the issue-rate map itself, like {@code LoginThrottle}. */
    static final int MAX_TRACKED_SOURCES = 10_000;
    private static final char[] USER_CODE_ALPHABET = "BCDFGHJKLMNPQRSTVWXZ23456789".toCharArray();
    private static final int USER_CODE_LENGTH = 8;
    private static final Pattern IPV6_LITERAL = Pattern.compile("[0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*(%[A-Za-z0-9._-]+)?");

    public enum PollOutcome { AUTHORIZATION_PENDING, EXPIRED_TOKEN, APPROVED }

    public record Issued(String deviceCode, String userCode, Instant expiresAt, int intervalSeconds) {}

    /** Why a grant was not issued: the whole is full, or this address asked too often. */
    public enum Refusal { BUSY, TOO_MANY }

    /** Exactly one of the two is set. */
    public record Issue(Issued issued, Refusal refusal) {}

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
        final String source;
        UUID approvedBy;

        Grant(String userCodeHash, Instant expiresAt, Pending pending, String source) {
            this.userCodeHash = userCodeHash;
            this.expiresAt = expiresAt;
            this.pending = pending;
            this.source = source;
        }
    }

    private final Clock clock;
    /** deviceCodeHash → grant. */
    private final Map<String, Grant> byDeviceCode = new HashMap<>();
    /** userCodeHash → deviceCodeHash. */
    private final Map<String, String> byUserCode = new HashMap<>();
    /** source → when it was issued grants inside the last {@link #TTL}. */
    private final Map<String, Deque<Instant>> issuedBySource = new HashMap<>();

    @Autowired
    public InstallGrants() {
        this(Clock.systemUTC());
    }

    InstallGrants(Clock clock) {
        this.clock = clock;
    }

    /** {@code source} is {@link #source(String)} of the caller's address. */
    public synchronized Issue issue(Pending pending, String source) {
        Instant now = clock.instant();
        sweep(now);
        Deque<Instant> issued = issuedBySource.get(source);
        if (issued != null) {
            prune(issued, now);
        }
        if (issued != null && issued.size() >= MAX_ISSUED_PER_SOURCE) {
            return new Issue(null, Refusal.TOO_MANY);
        }
        List<Map.Entry<String, Grant>> own = byDeviceCode.entrySet().stream()
                .filter(e -> e.getValue().source.equals(source) && e.getValue().approvedBy == null)
                .sorted(Comparator.comparing(e -> e.getValue().expiresAt))
                .toList();
        for (int i = 0; i <= own.size() - MAX_PENDING_PER_SOURCE; i++) {
            // The oldest of this address's own makes room; its PC is told the code expired and asks again.
            byUserCode.remove(own.get(i).getValue().userCodeHash);
            byDeviceCode.remove(own.get(i).getKey());
        }
        if (byDeviceCode.size() >= MAX_PENDING) {
            return new Issue(null, Refusal.BUSY);
        }
        String deviceCode = Codes.newCode();
        String userCode;
        do {
            userCode = Codes.fromAlphabet(USER_CODE_ALPHABET, USER_CODE_LENGTH);
        } while (byUserCode.containsKey(Codes.hash(userCode)));
        Instant expiresAt = now.plus(TTL);
        Grant grant = new Grant(Codes.hash(userCode), expiresAt, pending, source);
        byDeviceCode.put(Codes.hash(deviceCode), grant);
        byUserCode.put(grant.userCodeHash, Codes.hash(deviceCode));
        if (issued == null) {
            if (issuedBySource.size() >= MAX_TRACKED_SOURCES) {
                issuedBySource.keySet().stream().findFirst().ifPresent(issuedBySource::remove);
            }
            issued = new ArrayDeque<>();
            issuedBySource.put(source, issued);
        }
        issued.addLast(now);
        return new Issue(new Issued(deviceCode, userCode, expiresAt, POLL_INTERVAL_SECONDS), null);
    }

    /**
     * The address a grant is counted against: an IPv4 as it is, an IPv6 by its /64 — one subscriber's whole
     * allocation, since anyone holding one can pick any address inside it. Anything else is counted as itself.
     */
    public static String source(String remoteAddress) {
        if (remoteAddress == null) {
            return "";
        }
        String address = remoteAddress.trim();
        Optional<String> v4 = ClientIpv4.of(address);
        if (v4.isPresent()) {
            return v4.get();
        }
        if (IPV6_LITERAL.matcher(address).matches()) {
            try {
                // A literal with a colon is parsed, never looked up.
                byte[] raw = InetAddress.getByName(address).getAddress();
                if (raw.length == 16) {
                    StringBuilder prefix = new StringBuilder();
                    for (int i = 0; i < 8; i += 2) {
                        prefix.append(String.format(Locale.ROOT, "%x:", ((raw[i] & 0xff) << 8) | (raw[i + 1] & 0xff)));
                    }
                    return prefix.append(":/64").toString();
                }
            } catch (UnknownHostException e) {
                return address;
            }
        }
        return address;
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
        return raw == null ? "" : raw.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
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
        issuedBySource.values().removeIf(issued -> {
            prune(issued, now);
            return issued.isEmpty();
        });
    }

    private static void prune(Deque<Instant> issued, Instant now) {
        Instant cutoff = now.minus(TTL);
        while (!issued.isEmpty() && !issued.peekFirst().isAfter(cutoff)) {
            issued.removeFirst();
        }
    }
}
