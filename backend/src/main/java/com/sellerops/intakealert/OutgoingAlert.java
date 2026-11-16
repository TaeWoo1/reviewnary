package com.sellerops.intakealert;

import java.util.Map;
import java.util.UUID;

/**
 * What a sender is handed: the delivery id (for idempotent bookkeeping), the recipient's number — present only for a
 * live sender, opened from the sealed column just before the call and never logged — the template variables, the
 * rendered text and the absolute link to the in-service notice page.
 */
public record OutgoingAlert(UUID deliveryId, String toPhone, Map<String, String> variables, String text, String linkUrl) {

    public OutgoingAlert {
        variables = Map.copyOf(variables);
    }

    @Override
    public String toString() {
        return "OutgoingAlert[deliveryId=" + deliveryId + ", to=" + (toPhone == null ? "-" : "(masked)") + "]";
    }
}
