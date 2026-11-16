package com.sellerops.intakealert;

/**
 * Live sending was asked for ({@code sellerops.intake-alert.mode=solapi}) but cannot run: live is off, or a required
 * setting is missing. Every delivery is recorded {@code BLOCKED} with the reason, so the seller sees that nothing was
 * sent — it never falls back to the mock and pretends.
 */
public final class BlockedIntakeAlertSender implements IntakeAlertSender {

    private final String reason;

    public BlockedIntakeAlertSender(String reason) {
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }

    @Override
    public IntakeAlertDelivery.Mode mode() {
        return IntakeAlertDelivery.Mode.BLOCKED;
    }

    @Override
    public boolean needsNumber() {
        return false;
    }

    @Override
    public Outcome send(OutgoingAlert alert) {
        return Outcome.blocked(reason);
    }
}
