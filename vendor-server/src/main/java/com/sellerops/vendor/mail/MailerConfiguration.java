package com.sellerops.vendor.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Picks the one {@link Mailer} from {@code vendor.mail.mode}: {@code smtp} needs {@code spring.mail.host} and refuses
 * to start without it — a misconfigured deployment must not quietly become "off"; {@code dev-outbox} writes mails to
 * the log (local only); {@code off} (default) delivers nothing, so no new PC can be linked.
 */
@Configuration
public class MailerConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MailerConfiguration.class);

    @Bean
    public Mailer mailer(@Value("${vendor.mail.mode:off}") String mode,
                         @Value("${vendor.mail.from:no-reply@localhost}") String from,
                         @Value("${vendor.mail.reply-to:}") String replyTo,
                         ObjectProvider<JavaMailSender> senders) {
        String normalized = mode == null ? "off" : mode.trim().toLowerCase();
        switch (normalized) {
            case "smtp" -> {
                JavaMailSender sender = senders.getIfAvailable();
                if (sender == null) {
                    throw new IllegalStateException(
                            "VENDOR_MAIL_MODE=smtp but no JavaMailSender: set SPRING_MAIL_HOST (spring.mail.host)");
                }
                log.info("mailer: smtp");
                return new SmtpMailer(sender, from, replyTo);
            }
            case "dev-outbox" -> {
                log.warn("mailer: DEV OUTBOX — mails, one-time codes included, are written to this log. Never deployed.");
                return new DevOutboxMailer();
            }
            case "off", "" -> {
                log.warn("mailer: off — no step-up code can be sent, so no new PC can be linked");
                return new NoopMailer();
            }
            default -> throw new IllegalStateException("unknown VENDOR_MAIL_MODE '" + mode + "' (smtp | dev-outbox | off)");
        }
    }
}
