package com.sellerops.vendor.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code VENDOR_MAIL_MODE=off} (the default): nothing is delivered. Not a way around the step-up — a code that cannot
 * be delivered cannot be entered, so no new PC is linked until mail is set up (contract §2).
 */
public final class NoopMailer implements Mailer {

    private static final Logger log = LoggerFactory.getLogger(NoopMailer.class);

    @Override
    public boolean deliverable() {
        return false;
    }

    @Override
    public void send(OutboundMail mail) {
        log.warn("mail dropped: VENDOR_MAIL_MODE=off (subject '{}')", mail.subject());
    }
}
