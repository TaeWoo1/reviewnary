package com.sellerops.outbound;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * <b>On a seller's PC, every outbound request names where it is going and is checked before it leaves</b> —
 * scope lock v1.14 ⑤ ({@code docs/product-scope-v1.md}), proposal §17.3 rule 1 / §18.7 L1b.
 *
 * <p>Every JDK {@code HttpClient} in the backend calls {@link #check} on the exact request URI immediately
 * before {@code send}, naming the {@link Destination} it believes it is talking to. In a seller-PC install the
 * pair must match an allow-list written here — not read from configuration, so a changed base-URL property
 * cannot point a channel client, or the model transport, at another host. {@code OutboundGateFenceTest} keeps
 * every client in the codebase calling it.
 *
 * <p><b>Off by default.</b> {@link #check} does nothing until {@code LocalSellerConfigValidator} has accepted a
 * local-seller configuration and armed it, so every existing deployment and test behaves exactly as before.
 *
 * <p><b>Loopback is always allowed.</b> A request to this machine leaves nothing behind it; the synthetic channel
 * servers that L2 develops against live there.
 *
 * <p>A refusal throws {@link OutboundRefusedException} naming the destination class, never the host or the URI —
 * a URI can carry a query with seller identifiers.
 */
public final class OutboundGate {

    /** What a client says it is talking to. */
    public enum Destination {
        /** NAVER Commerce API (inquiries, products, answers). */
        NAVER_COMMERCE,
        /** Coupang Open API. */
        COUPANG,
        /** Cafe24 Admin API — the mall's own {@code {mall}.cafe24api.com}. */
        CAFE24,
        /** A model provider, reached only through the backend's LLM capabilities. */
        LLM,
        /**
         * A public product image named by the seller's own product page. Any https host: the URL is already
         * public, the request carries no credential and no seller data, and {@code ImageFetchPolicy} screens it
         * (SSRF, size, type) on every hop.
         */
        PRODUCT_IMAGE,
        /** Channels outside the seller-visible set (NAVER/Coupang/Cafe24) — refused on a seller's PC. */
        ESM,
        ELEVENST,
        SSG
    }

    private static final Set<String> NAVER_HOSTS = Set.of("api.commerce.naver.com");
    private static final Set<String> COUPANG_HOSTS = Set.of("api-gateway.coupang.com");
    private static final String CAFE24_SUFFIX = ".cafe24api.com";
    private static final Set<String> LLM_HOSTS = Set.of("api.anthropic.com", "api.openai.com");
    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "::1", "[::1]");

    private static volatile boolean armed;

    private OutboundGate() {
    }

    /** Armed by {@code LocalSellerConfigValidator} once a local-seller configuration has passed; disarmed on close. */
    public static void arm(boolean on) {
        armed = on;
    }

    public static boolean armed() {
        return armed;
    }

    /** Throws if this request may not leave a seller's PC; does nothing when the gate is not armed. */
    public static void check(URI uri, Destination destination) {
        if (!armed) {
            return;
        }
        if (!allows(uri, destination)) {
            throw new OutboundRefusedException(destination);
        }
    }

    /** The allow-list itself, as a pure function, so a test can read it without arming anything. */
    static boolean allows(URI uri, Destination destination) {
        if (uri == null || destination == null || uri.getHost() == null || uri.getRawUserInfo() != null) {
            return false;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (LOOPBACK.contains(host)) {
            return scheme.equals("http") || scheme.equals("https");
        }
        if (!scheme.equals("https")) {
            return false;
        }
        return switch (destination) {
            case NAVER_COMMERCE -> NAVER_HOSTS.contains(host);
            case COUPANG -> COUPANG_HOSTS.contains(host);
            case CAFE24 -> host.endsWith(CAFE24_SUFFIX) && host.length() > CAFE24_SUFFIX.length()
                    && host.indexOf('.') == host.length() - CAFE24_SUFFIX.length();
            case LLM -> LLM_HOSTS.contains(host);
            case PRODUCT_IMAGE -> true;
            case ESM, ELEVENST, SSG -> false;
        };
    }

    /** A request this install may not send. Names the destination class only. */
    public static final class OutboundRefusedException extends IllegalStateException {
        public OutboundRefusedException(Destination destination) {
            super("판매자 PC 모드에서 허용되지 않은 외부 요청입니다 (" + destination + ").");
        }
    }
}
