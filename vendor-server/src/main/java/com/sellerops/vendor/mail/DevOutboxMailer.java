package com.sellerops.vendor.mail;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code VENDOR_MAIL_MODE=dev-outbox} — local runs and tests only: the mail is kept in a bounded in-memory outbox and
 * written whole (one-time codes included) to this server's log under {@code [DEV MAIL OUTBOX]}. The only mailer that
 * ever logs a body; a deployment never sets this mode (the backend's mailer has the same rule).
 */
public final class DevOutboxMailer implements Mailer {

    private static final Logger log = LoggerFactory.getLogger(DevOutboxMailer.class);
    private static final int CAPACITY = 50;

    private final Deque<OutboundMail> outbox = new ArrayDeque<>();

    @Override
    public boolean deliverable() {
        return true;
    }

    @Override
    public synchronized void send(OutboundMail mail) {
        if (outbox.size() >= CAPACITY) {
            outbox.removeFirst();
        }
        outbox.addLast(mail);
        log.info("[DEV MAIL OUTBOX] to={} subject={}\n{}", mail.to(), mail.subject(), mail.text());
    }

    /** Newest last. Tests and local inspection only. */
    public synchronized List<OutboundMail> outbox() {
        return List.copyOf(outbox);
    }
}
