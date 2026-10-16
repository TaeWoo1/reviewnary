package com.sellerops.config;

import com.sellerops.outbound.OutboundGate;
import com.sellerops.selfpilot.SelfPilotProperties;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * <b>A seller-PC install must not boot in a shape that reaches past the seller's PC</b> — scope lock
 * v1.14 ① · ⑤ ({@code docs/product-scope-v1.md}), proposal §17.4 / §18.7 L1.
 *
 * <p>The failure this exists for is the same class {@link PilotConfigValidator} names: a process that
 * starts, serves every screen, and is wrong in a way no screen can show. Here the wrong thing is where
 * the process can be reached and what it can send: a backend listening on every interface is reachable
 * from the whole LAN, a database off the machine is not the seller's own ledger, the shipped JWT
 * secret signs tokens anyone with this repository can forge, and Sentry, SMTP or a social-login
 * provider is a destination outside the seller's PC that scope lock v1.14 ⑤ keeps OFF in local mode.
 *
 * <p><b>Only when {@code sellerops.local-seller.enabled} is true.</b> Every existing deployment — the
 * developer's {@code bootRun}, the compose stack, the paused pilot — boots exactly as before; the flag is
 * set by the {@code local-seller} profile ({@code application-local-seller.yml}).
 *
 * <p>It runs as the bean initialises rather than at {@code ApplicationReadyEvent}, deliberately unlike
 * {@link PilotConfigValidator}: the embedded server starts accepting connections only after every
 * singleton is built, so throwing here means a misbound install never answers a single request — not
 * even for the moment between "listening" and "ready". Like that validator it names the setting to
 * change and never the value: no secret, DSN or address is read into the message.
 *
 * <p>A configuration it accepts in local-seller mode also arms {@link OutboundGate}, so every outbound request
 * is checked against the seller-PC allow-list from the first one; closing the context disarms it again.
 */
@Component
public class LocalSellerConfigValidator implements InitializingBean, DisposableBean {

    /** The JWT secret {@code application.yml} ships with; any install still on it signs forgeable tokens. */
    static final String SHIPPED_JWT_SECRET = "change-me-local-dev-only-please-use-a-long-random-string";
    /** HS256 wants at least 256 bits of key; shorter secrets are guessable, not merely weak. */
    static final int MIN_JWT_SECRET_LENGTH = 32;

    private final boolean enabled;
    private final String serverAddress;
    private final String datasourceUrl;
    private final String jwtSecret;
    private final String corsOrigin;
    private final String sentryDsn;
    private final String mailMode;
    private final String googleClientId;
    private final String naverLoginClientId;

    public LocalSellerConfigValidator(
            @Value("${sellerops.local-seller.enabled:false}") boolean enabled,
            @Value("${server.address:}") String serverAddress,
            @Value("${spring.datasource.url:}") String datasourceUrl,
            @Value("${sellerops.jwt.secret:}") String jwtSecret,
            @Value("${sellerops.cors.origin:}") String corsOrigin,
            @Value("${sentry.dsn:}") String sentryDsn,
            @Value("${sellerops.mail.mode:off}") String mailMode,
            @Value("${sellerops.oauth.google.client-id:}") String googleClientId,
            @Value("${sellerops.oauth.naver.client-id:}") String naverLoginClientId) {
        this.enabled = enabled;
        this.serverAddress = serverAddress;
        this.datasourceUrl = datasourceUrl;
        this.jwtSecret = jwtSecret;
        this.corsOrigin = corsOrigin;
        this.sentryDsn = sentryDsn;
        this.mailMode = mailMode;
        this.googleClientId = googleClientId;
        this.naverLoginClientId = naverLoginClientId;
    }

    @Override
    public void afterPropertiesSet() {
        List<String> problems = problems();
        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "판매자 PC 모드(local-seller)로 시작할 수 없는 설정입니다. "
                            + "아래를 고친 뒤 다시 시작하세요:\n  - "
                            + String.join("\n  - ", problems));
        }
        if (enabled) {
            OutboundGate.arm(true);
        }
    }

    @Override
    public void destroy() {
        if (enabled) {
            OutboundGate.arm(false);
        }
    }

    /** The conditions, in one list, so a test can read them without starting a context. */
    List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (!enabled) {
            return problems;
        }
        if (!isLoopbackHost(serverAddress)) {
            problems.add("SERVER_ADDRESS — 판매자 PC 모드의 backend는 루프백(127.0.0.1)에만 열려야 합니다. "
                    + "비어 있으면 같은 네트워크의 모든 기기에서 접속할 수 있습니다.");
        }
        if (!SelfPilotProperties.isLoopbackDatabase(datasourceUrl)) {
            problems.add("SPRING_DATASOURCE_URL — 판매자 PC 모드의 데이터베이스는 이 PC(루프백)에 있어야 합니다.");
        }
        if (jwtSecret == null || jwtSecret.isBlank() || jwtSecret.equals(SHIPPED_JWT_SECRET)
                || jwtSecret.length() < MIN_JWT_SECRET_LENGTH) {
            problems.add("SELLEROPS_JWT_SECRET — 저장소에 들어 있는 기본값이거나 " + MIN_JWT_SECRET_LENGTH
                    + "자보다 짧습니다. 설치마다 새로 만든 값이어야 합니다.");
        }
        if (!isLoopbackOrigin(corsOrigin)) {
            problems.add("SELLEROPS_CORS_ORIGIN — 판매자 PC 모드에서 허용할 화면 주소는 루프백 주소여야 합니다.");
        }
        if (sentryDsn != null && !sentryDsn.isBlank()) {
            problems.add("SENTRY_DSN — 판매자 PC 모드에서는 오류 보고를 외부로 보내지 않습니다(scope lock v1.14 ⑤).");
        }
        if (mailMode != null && mailMode.trim().equalsIgnoreCase("smtp")) {
            problems.add("SELLEROPS_MAIL_MODE — 판매자 PC 모드에서는 SMTP 메일을 켤 수 없습니다(scope lock v1.14 ⑤).");
        }
        if (notBlank(googleClientId) || notBlank(naverLoginClientId)) {
            problems.add("SELLEROPS_OAUTH_GOOGLE_CLIENT_ID / SELLEROPS_OAUTH_NAVER_CLIENT_ID — "
                    + "판매자 PC 모드에서는 소셜 로그인을 켤 수 없습니다(scope lock v1.14 ⑤).");
        }
        return problems;
    }

    /** True for localhost / 127.0.0.1 / ::1 — the only hosts a seller-PC install may bind or name. */
    static boolean isLoopbackHost(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String host = raw.trim().toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        return host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1");
    }

    /** An http(s) origin whose host is loopback; anything unparseable is not. */
    static boolean isLoopbackOrigin(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(raw.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            return (scheme.equals("http") || scheme.equals("https")) && isLoopbackHost(uri.getHost());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean notBlank(String v) {
        return v != null && !v.isBlank();
    }
}
