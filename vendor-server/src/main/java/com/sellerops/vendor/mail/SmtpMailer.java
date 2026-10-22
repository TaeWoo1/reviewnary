package com.sellerops.vendor.mail;

import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/** {@code VENDOR_MAIL_MODE=smtp} — Spring's JavaMail sender configured by {@code spring.mail.*}. Logs nothing. */
public final class SmtpMailer implements Mailer {

    private final JavaMailSender sender;
    private final String from;
    private final String replyTo;

    public SmtpMailer(JavaMailSender sender, String from, String replyTo) {
        this.sender = sender;
        this.from = from;
        this.replyTo = replyTo;
    }

    @Override
    public boolean deliverable() {
        return true;
    }

    @Override
    public void send(OutboundMail mail) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        if (replyTo != null && !replyTo.isBlank()) {
            message.setReplyTo(replyTo);
        }
        message.setTo(mail.to());
        message.setSubject(mail.subject());
        message.setText(mail.text());
        sender.send(message);
    }
}
