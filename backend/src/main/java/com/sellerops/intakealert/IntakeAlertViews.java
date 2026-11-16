package com.sellerops.intakealert;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** What the screens read. No number beyond its last four digits, no customer text, no key or template value. */
public final class IntakeAlertViews {

    private IntakeAlertViews() {
    }

    /** {@code mode} MOCK | LIVE | BLOCKED, {@code reason} only for BLOCKED (setting names, never values). */
    public record SenderView(String mode, String reason) {
    }

    /**
     * The honest limits of the guarantee, shown on the settings screen: a row is found again by the periodic pass only
     * while it is within {@code recoveryHours} of arriving; a row the customer wrote more than {@code oldAfterHours}
     * before it arrived is recorded and not told; at most {@code perPassCap} messages per pass, the rest wait.
     */
    public record Limits(long recoveryHours, long oldAfterHours, int perPassCap, long passSeconds) {
    }

    public record SettingsView(boolean enabled, boolean notifyReviews, boolean notifyInquiries, String deliveryMode,
                               int digestMinutes, boolean quietHoursEnabled, int daysMask, int startMinute,
                               int endMinute, String phoneMasked, boolean phoneConsented, boolean numberStorageAvailable,
                               Instant watchFrom, SenderView sender, Limits limits, Counts counts) {
    }

    /** Item counts for this recipient, by what happened to them. */
    public record Counts(long pending, long told, long handledBeforeTold, long oldNotTold, long cancelled) {
    }

    public record SettingsRequest(Boolean enabled, Boolean notifyReviews, Boolean notifyInquiries, String deliveryMode,
                                  Integer digestMinutes, Boolean quietHoursEnabled, Integer daysMask, Integer startMinute,
                                  Integer endMinute, String phone, Boolean removePhone, Boolean phoneConsent) {

        @Override
        public String toString() {
            return "SettingsRequest[phone=" + (phone == null ? "-" : "(masked)") + "]";
        }
    }

    public record DeliveryRow(UUID id, String kind, String mode, String status, int reviewCount, int inquiryCount,
                              int urgentCount, String messageText, String linkPath, String failureCode,
                              Instant createdAt, Instant finishedAt) {
    }

    /**
     * One original row in a delivery. {@code arrivedAt} is when it reached this service (what the alert is about);
     * {@code writtenAt} is when the channel says the customer wrote it — they differ for a row collected late.
     * {@code stillOpen} is read now, not when the message was made; {@code targetPath} is null when the row is gone.
     */
    public record DeliveryItem(String subjectKind, UUID subjectId, String channelCode, String reason, String issueLabel,
                               boolean urgent, String status, String cancelReason, boolean stillOpen, String targetPath,
                               Instant arrivedAt, Instant writtenAt) {
    }

    public record DeliveryDetail(DeliveryRow delivery, List<DeliveryItem> items) {
    }
}
