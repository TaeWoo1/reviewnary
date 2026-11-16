package com.sellerops.intakealert;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Chooses the one {@link IntakeAlertSender} at startup.
 *
 * <ul>
 *   <li>{@code sellerops.intake-alert.mode=mock} (default) → {@link MockIntakeAlertSender}: nothing leaves the server.</li>
 *   <li>{@code mode=solapi} with {@code solapi.live-enabled=true} and every one of api-key, api-secret, pf-id,
 *       template-id and an https {@code sellerops.public-base-url} → {@link SolapiAlimtalkSender}. {@code sender-number}
 *       is optional: SOLAPI needs {@code from} for the SMS fallback, which this service always disables, and accepts an
 *       empty one for 알림톡 (SOLAPI messages document, Codex check 2026-11-16) — sent when set, left out when not.</li>
 *   <li>{@code mode=solapi} with anything missing or live off → {@link BlockedIntakeAlertSender}: deliveries are recorded
 *       BLOCKED with the reason (setting <i>names</i> only), and nothing is sent or mocked.</li>
 * </ul>
 * Any other mode value is BLOCKED too — a typo never sends.
 */
@Configuration
public class IntakeAlertSenderConfiguration {

    private static final Logger log = LoggerFactory.getLogger(IntakeAlertSenderConfiguration.class);

    @Bean
    public IntakeAlertSender intakeAlertSender(
            @Value("${sellerops.intake-alert.mode:mock}") String mode,
            @Value("${sellerops.intake-alert.solapi.live-enabled:false}") boolean liveEnabled,
            @Value("${sellerops.intake-alert.solapi.base-url:https://api.solapi.com}") String baseUrl,
            @Value("${sellerops.intake-alert.solapi.api-key:}") String apiKey,
            @Value("${sellerops.intake-alert.solapi.api-secret:}") String apiSecret,
            @Value("${sellerops.intake-alert.solapi.pf-id:}") String pfId,
            @Value("${sellerops.intake-alert.solapi.template-id:}") String templateId,
            @Value("${sellerops.intake-alert.solapi.sender-number:}") String senderNumber,
            @Value("${sellerops.public-base-url:http://localhost:5173}") String publicBaseUrl) {
        IntakeAlertSender sender = choose(mode, liveEnabled, baseUrl, apiKey, apiSecret, pfId, templateId, senderNumber,
                publicBaseUrl);
        log.info("intake alert: sender mode={}{}", sender.mode(),
                sender instanceof BlockedIntakeAlertSender b ? " reason=" + b.reason() : "");
        return sender;
    }

    static IntakeAlertSender choose(String mode, boolean liveEnabled, String baseUrl, String apiKey, String apiSecret,
                                    String pfId, String templateId, String senderNumber, String publicBaseUrl) {
        String m = mode == null ? "" : mode.trim().toLowerCase(java.util.Locale.ROOT);
        if (m.equals("mock")) {
            return new MockIntakeAlertSender();
        }
        if (!m.equals("solapi")) {
            return new BlockedIntakeAlertSender("UNKNOWN_MODE");
        }
        if (!liveEnabled) {
            return new BlockedIntakeAlertSender("LIVE_OFF");
        }
        List<String> missing = new ArrayList<>();
        if (blank(apiKey)) missing.add("api-key");
        if (blank(apiSecret)) missing.add("api-secret");
        if (blank(pfId)) missing.add("pf-id");
        if (blank(templateId)) missing.add("template-id");
        if (blank(baseUrl) || !baseUrl.startsWith("https://")) missing.add("base-url(https)");
        if (blank(publicBaseUrl) || !publicBaseUrl.startsWith("https://")) missing.add("public-base-url(https)");
        if (!missing.isEmpty()) {
            return new BlockedIntakeAlertSender("MISSING:" + String.join(",", missing));
        }
        return new SolapiAlimtalkSender(new SolapiAlimtalkSender.Settings(baseUrl, apiKey, apiSecret, pfId, templateId,
                blank(senderNumber) ? null : senderNumber.trim(), Duration.ofSeconds(5), Duration.ofSeconds(10)));
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
