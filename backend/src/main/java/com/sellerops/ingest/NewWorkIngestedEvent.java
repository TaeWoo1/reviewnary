package com.sellerops.ingest;

import java.util.List;
import java.util.UUID;

/**
 * Published by {@link IngestFollowUp} after a sync lands new reviews or inquiries — the moment the owner chose for the
 * AI to judge them and for drafts to be written ahead (2026-10-28: 「리뷰 연동할 때, api가 문의 연동할 때마다를 기점으로」).
 * Carries the org, the kind and the ids the ingest inserted; no content.
 */
public record NewWorkIngestedEvent(UUID orgId, Kind kind, List<UUID> ids) {

    public enum Kind { REVIEW, INQUIRY }

    public NewWorkIngestedEvent {
        ids = List.copyOf(ids);
    }
}
