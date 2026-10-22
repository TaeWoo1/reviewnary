package com.sellerops.vendor.mail;

/**
 * The vendor server's mail sender — contract §2 (추가 인증 · 새 PC 알림). Same shape as the backend's
 * {@code com.sellerops.mail.Mailer}; exactly one implementation is wired by {@link MailerConfiguration}.
 */
public interface Mailer {

    /** True when a mail sent here can reach a person (SMTP) or the local operator (dev outbox). */
    boolean deliverable();

    void send(OutboundMail mail);
}
