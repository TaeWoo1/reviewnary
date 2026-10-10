package com.sellerops.order;

import com.sellerops.channel.Channel;
import com.sellerops.channel.ChannelRepository;
import com.sellerops.ingest.IngestOutcome;
import com.sellerops.ingest.canonical.CanonicalOrder;
import com.sellerops.ingest.map.RowError;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Per-order persistence + dedup, kept separate from the shared {@link com.sellerops.ingest.IngestionService}
 * so the aggregate-only path is untouched. Idempotently upserts {@link CanonicalOrder} rows into
 * {@link ChannelOrder} and appends an append-only {@link ChannelOrderStatusEvent} whenever the raw
 * channel status changes:
 *
 * <ul>
 *   <li><b>New</b> product order → insert + initial status event ({@code null → raw}); counts success.</li>
 *   <li><b>Existing, raw status changed</b> → update the current row, append a {@code old → new} event;
 *       counts success.</li>
 *   <li><b>Existing, raw status unchanged</b> → bump {@code last_seen_at} only, no event; counts skip
 *       (idempotent no-op — a repeated sync creates neither a duplicate row nor spurious history).</li>
 * </ul>
 *
 * <p><b>Org/account boundary</b> is re-checked on every read and write: the identity lookup is scoped
 * by {@code (org_id, seller_account_id, external_order_id)}, so a product order can never be matched,
 * updated, or aggregated across an org or an account.
 *
 * <p>Each product order's (row + event) pair is written in its own transaction via
 * {@link TransactionTemplate} — matching {@code InquiryWorkItemWriter} — so the pair is atomic yet one
 * bad row can never roll back rows that already landed (an honest PARTIAL, per the sync model).
 */
@Service
public class ChannelOrderIngestionService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ChannelOrderIngestionService.class);

    private final ChannelOrderRepository orders;
    private final ChannelOrderStatusEventRepository statusEvents;
    private final ChannelRepository channels;
    private final TransactionTemplate tx;

    public ChannelOrderIngestionService(ChannelOrderRepository orders,
                                        ChannelOrderStatusEventRepository statusEvents,
                                        ChannelRepository channels,
                                        PlatformTransactionManager transactionManager) {
        this.orders = orders;
        this.statusEvents = statusEvents;
        this.channels = channels;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /**
     * 주문의 상품 참조를 보관·해소하는 협력자 (Order Context Foundation v1, D1).
     *
     * <p>optional인 것이 정직한 모양이다 — 주문 수집은 이것 없이 완전했고 여전히 완전하다. binder를
     * 배선하지 않은 context는 이 패키지가 상품을 모르던 때의 제품이고, 정확히 그때처럼 동작한다.
     */
    private ChannelOrderProductBinder productBinder;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setProductBinder(ChannelOrderProductBinder productBinder) {
        this.productBinder = productBinder;
    }

    /**
     * 채널이 이 주문에 준 상품 식별자를 보관하고 가능하면 연결한다 — <b>best-effort</b>.
     *
     * <p>실패가 주문 수집을 멈추지 않는다. 상품 참조는 주문 행에 덧붙는 사실이고, 그것 때문에 주문이
     * 저장되지 않으면 오늘 동작하는 수집이 관측된 적 없는 필드 하나 때문에 멈춘다. 대신 로그에 남는다 —
     * 조용히 사라지는 실패는 「채널이 식별자를 주지 않았다」와 구별되지 않는다.
     */
    private void recordProductRefs(UUID orgId, UUID channelId, String channelCode, UUID channelOrderId,
                                   CanonicalOrder row) {
        if (productBinder == null || row.productRefs().isEmpty()) {
            return;
        }
        try {
            productBinder.record(orgId, channelId, channelCode, channelOrderId, row.productRefs(),
                    ChannelProductRefSource.forChannel(channelCode));
        } catch (Exception e) {
            log.warn("주문 상품 참조를 기록하지 못했습니다 (채널={}): {}", channelCode, e.getMessage());
        }
    }

    /**
     * Upsert one page of per-order records for a specific seller connection. A per-order row has no
     * home without the exact connection, so a null {@code sellerAccountId} fails closed (every row
     * counted failed) rather than persisting an unscoped order.
     */
    public IngestOutcome ingest(UUID orgId, UUID channelId, UUID sellerAccountId, List<CanonicalOrder> rows) {
        Tally tally = new Tally();
        // Whose vocabulary these codes are written in. Resolved once per page, not per row, and
        // never guessed: a status code means something only as THIS channel's word for it
        // (ChannelOrderStatusVocabulary), so a channel we cannot name normalizes to UNKNOWN.
        String channelCode = channels.findById(channelId).map(Channel::getCode).orElse(null);
        Set<String> seen = new HashSet<>();
        for (CanonicalOrder row : rows) {
            if (sellerAccountId == null) {
                tally.fail(row.sourceRow(), "판매 계정 없이 주문을 저장할 수 없습니다.");
                continue;
            }
            String externalOrderId = row.externalOrderId();
            if (externalOrderId == null || externalOrderId.isBlank()) {
                tally.fail(row.sourceRow(), "주문 식별자가 없습니다.");
                continue;
            }
            // In-batch dedup: the same product order twice in one page is not persisted twice.
            if (!seen.add(externalOrderId)) {
                tally.skip();
                continue;
            }
            // Records whether this row took the INSERT branch, so a unique-index violation can be
            // classified correctly: only an insert can lose the identity race to a concurrent writer.
            boolean[] attemptedInsert = {false};
            try {
                UpsertResult result = tx.execute(status ->
                        upsertOne(orgId, channelId, channelCode, sellerAccountId, row, attemptedInsert));
                switch (result.kind()) {
                    case INSERTED -> tally.success(result.id());
                    case UPDATED -> tally.update();
                    case UNCHANGED -> tally.skip();
                }
            } catch (DataIntegrityViolationException dup) {
                // A concurrent INSERT winning the identity race is the ONLY benign case: the row now
                // exists though our insert lost. A violation on the UPDATE path (e.g. a bad column
                // value) is a genuine failure and must never be silently masked as a duplicate skip.
                if (attemptedInsert[0]
                        && orders.existsByOrgIdAndSellerAccountIdAndExternalOrderId(orgId, sellerAccountId, externalOrderId)) {
                    tally.skip();
                } else {
                    tally.fail(row.sourceRow(), "저장 실패: 제약 조건 위반");
                }
            } catch (Exception e) {
                tally.fail(row.sourceRow(), "처리 실패: " + e.getMessage());
            }
        }
        return tally.toOutcome();
    }

    private UpsertResult upsertOne(UUID orgId, UUID channelId, String channelCode, UUID sellerAccountId,
                                   CanonicalOrder row, boolean[] attemptedInsert) {
        Instant now = Instant.now();
        ChannelOrder existing = orders
                .findByOrgIdAndSellerAccountIdAndExternalOrderId(orgId, sellerAccountId, row.externalOrderId())
                .orElse(null);

        if (existing == null) {
            attemptedInsert[0] = true;
            ChannelOrder entity = new ChannelOrder();
            entity.setOrgId(orgId);
            entity.setSellerAccountId(sellerAccountId);
            entity.setChannelId(channelId);
            entity.setExternalOrderId(row.externalOrderId());
            entity.setParentOrderId(row.parentOrderId());
            entity.setRawStatusCode(row.rawStatusCode());
            entity.setNormalizedStatus(NormalizedOrderStatus.fromRaw(channelCode, row.rawStatusCode()));
            entity.setPaymentAmount(row.paymentAmount());
            entity.setSummaryDate(row.summaryDate());
            entity.setPaidAt(row.paidAt());
            entity.setStatusChangedAt(row.statusChangedAt());
            entity.setFirstSeenAt(now);
            entity.setLastSeenAt(now);
            ChannelOrder saved = orders.save(entity);
            appendEvent(orgId, saved.getId(), null, row.rawStatusCode(), row.statusChangedAt(), now);
            recordProductRefs(orgId, channelId, channelCode, saved.getId(), row);
            return UpsertResult.inserted(saved.getId());
        }

        existing.setLastSeenAt(now);
        boolean statusChanged = !existing.getRawStatusCode().equals(row.rawStatusCode());
        if (statusChanged) {
            String previous = existing.getRawStatusCode();
            existing.setRawStatusCode(row.rawStatusCode());
            existing.setNormalizedStatus(NormalizedOrderStatus.fromRaw(channelCode, row.rawStatusCode()));
            existing.setStatusChangedAt(row.statusChangedAt());
            existing.setPaymentAmount(row.paymentAmount());
            // Fill paidAt only when newly supplied — never overwrite a known value with null.
            if (row.paidAt() != null) {
                existing.setPaidAt(row.paidAt());
            }
            orders.save(existing);
            appendEvent(orgId, existing.getId(), previous, row.rawStatusCode(), row.statusChangedAt(), now);
            recordProductRefs(orgId, channelId, channelCode, existing.getId(), row);
            return UpsertResult.updated(existing.getId());
        }
        orders.save(existing);
        // 상태가 그대로인 재동기화에서도 참조는 기록한다 — 식별자가 처음 도착하는 것은 상태 변화와
        // 무관한 사건이고(수집이 넓어진 날이 그렇다), 그때 저장하지 않으면 다음 상태 변화까지 기다린다.
        recordProductRefs(orgId, channelId, channelCode, existing.getId(), row);
        return UpsertResult.unchanged(existing.getId());
    }

    private void appendEvent(UUID orgId, UUID channelOrderId, String from, String to,
                             Instant observedAt, Instant recordedAt) {
        ChannelOrderStatusEvent event = new ChannelOrderStatusEvent();
        event.setOrgId(orgId);
        event.setChannelOrderId(channelOrderId);
        event.setFromStatusCode(from);
        event.setToStatusCode(to);
        event.setObservedAt(observedAt);
        event.setRecordedAt(recordedAt);
        statusEvents.save(event);
    }

    private enum UpsertKind { INSERTED, UPDATED, UNCHANGED }

    private record UpsertResult(UpsertKind kind, UUID id) {
        static UpsertResult inserted(UUID id) {
            return new UpsertResult(UpsertKind.INSERTED, id);
        }

        static UpsertResult updated(UUID id) {
            return new UpsertResult(UpsertKind.UPDATED, id);
        }

        static UpsertResult unchanged(UUID id) {
            return new UpsertResult(UpsertKind.UNCHANGED, id);
        }
    }

    /** Mutable per-call tally that builds an {@link IngestOutcome}; mirrors IngestionService's. */
    private static final class Tally {
        private int success;
        private int skipped;
        private int failed;
        private final List<RowError> errors = new ArrayList<>();
        private final List<UUID> insertedIds = new ArrayList<>();

        void success(UUID id) {
            success++;
            insertedIds.add(id);
        }

        /** A successful in-place update: counts as success, contributes no inserted id. */
        void update() {
            success++;
        }

        void skip() {
            skipped++;
        }

        void fail(int sourceRow, String message) {
            failed++;
            errors.add(new RowError(sourceRow, message));
        }

        IngestOutcome toOutcome() {
            return new IngestOutcome(success, skipped, failed, errors, insertedIds);
        }
    }
}
