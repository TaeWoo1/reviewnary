package com.sellerops.intakealert;

/**
 * The port a delivery leaves through. Three implementations, chosen once at startup by
 * {@link IntakeAlertSenderConfiguration}: {@link MockIntakeAlertSender} (the default — writes nothing outside this
 * server), {@link BlockedIntakeAlertSender} (live requested but not fully configured — fails closed) and
 * {@link SolapiAlimtalkSender} (the operating adapter, only when every live setting is present and live is on).
 */
public interface IntakeAlertSender {

    IntakeAlertDelivery.Mode mode();

    /** Whether {@link OutgoingAlert#toPhone()} must be filled. Only a live sender ever sees a number. */
    boolean needsNumber();

    /** Never throws: every failure is an {@link Outcome}. Must be called outside any database transaction. */
    Outcome send(OutgoingAlert alert);

    /**
     * {@code status} is one of MOCK_RECORDED, ACCEPTED, FAILED, UNKNOWN or BLOCKED. {@code failureCode} is a closed
     * code or the provider's status code — never a message body, a number or a key.
     */
    record Outcome(IntakeAlertDelivery.Status status, String providerMessageId, String failureCode) {

        public static Outcome mockRecorded() {
            return new Outcome(IntakeAlertDelivery.Status.MOCK_RECORDED, null, null);
        }

        public static Outcome blocked(String code) {
            return new Outcome(IntakeAlertDelivery.Status.BLOCKED, null, code);
        }
    }
}
