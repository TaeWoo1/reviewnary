package com.sellerops.vendor.review;

import com.sellerops.vendor.Codes;
import com.sellerops.vendor.task.ReplyTasks;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>A store's reviews as the delegate's uploads stated them, and the ordered list a PC pulls</b> — contract §3.
 *
 * <ul>
 *   <li><b>Upload.</b> A review number seen for the first time is {@code NEW}. Seen before with a different text,
 *       rating, times, display state, attachment or product is {@code CHANGED} — not a new review (§3.1 4; the task
 *       lane invalidates on it). A reply flag going from N to Y is {@code REPLIED}: the channel itself says the
 *       review was answered. Identical is nothing. A review missing from a later upload is <i>not</i> deleted — an
 *       export covers whatever period the delegate chose, so absence proves nothing.</li>
 *   <li><b>Feed.</b> Events in order after a cursor, each with the review's current content (§17.9: the seller sees
 *       the original). The PC confirms what it saved ({@link #ack}); that and a "no reply needed" judgement
 *       ({@link #markNoReplyNeeded}) together start the 90-day retention of D4, once, never moved later.</li>
 * </ul>
 *
 * <p>Callers check who may see the store; this class takes a store id that is already allowed.
 */
@Service
public class StoreReviews {

    /** D4: a no-reply review is kept at most this long after both the PC saved it and it was judged so. */
    public static final Duration NO_REPLY_RETENTION = Duration.ofDays(90);
    public static final int MAX_PAGE = 200;

    public enum EventKind { NEW, CHANGED, REPLIED }

    /**
     * {@code purged} counts rows naming a review already deleted at the end of its retention (§6): it is not taken
     * again, so nothing changed for it here and it is counted in {@code unchanged} too.
     */
    public record Imported(UUID uploadId, int rows, int added, int changed, int replied, int unchanged,
                           int attachmentsDropped, int purged) {}

    /** What a PC receives about one review. */
    public record ReviewView(UUID id, String reviewNo, String productNo, String productName, String kind, int rating,
                             String body, String bodyFingerprint, ReviewAttachment attachment, Instant writtenAt,
                             Instant modifiedAt, String displayState, boolean replied, Instant repliedAt,
                             String relatedReviewNo, String relatedBody) {}

    public record Event(long seq, EventKind kind, ReviewView review) {}

    /** {@code cursor} is the last seq returned (or the one asked for, if none); {@code more} says to ask again. */
    public record Page(List<Event> events, long cursor, boolean more) {}

    private final JdbcClient jdbc;
    private final Clock clock;

    @Autowired
    public StoreReviews(JdbcClient jdbc) {
        this(jdbc, Clock.systemUTC());
    }

    public StoreReviews(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public Imported importExport(UUID storeId, UUID delegateId, List<NaverReviewExport.Line> lines) {
        Instant now = clock.instant();
        UUID uploadId = UUID.randomUUID();
        jdbc.sql("insert into review_uploads (id, store_id, delegate_id, uploaded_at, row_count, new_count, "
                        + "changed_count, replied_count, unchanged_count, attachments_dropped) "
                        + "values (?, ?, ?, ?, ?, 0, 0, 0, 0, 0)")
                .params(uploadId, storeId, delegateId, ts(now), lines.size())
                .update();
        Map<String, ReviewView> existing = new HashMap<>();
        for (ReviewView view : jdbc.sql("select * from store_reviews where store_id = ?").param(storeId)
                .query(StoreReviews::view).list()) {
            existing.put(view.reviewNo(), view);
        }
        int added = 0;
        int changed = 0;
        int replied = 0;
        int unchanged = 0;
        int dropped = 0;
        int purged = 0;
        for (NaverReviewExport.Line line : lines) {
            if (ReviewAttachment.refused(line.attachment())) {
                dropped++;
            }
            ReviewAttachment attachment = ReviewAttachment.screen(line.attachment()).orElse(null);
            ReviewView before = existing.get(line.reviewNo());
            if (before == null && purgedNumber(storeId, line.reviewNo())) {
                // D4: re-uploading never extends a review's retention — once deleted, it is not collected again.
                unchanged++;
                purged++;
                continue;
            }
            if (before == null) {
                UUID id = UUID.randomUUID();
                jdbc.sql("insert into store_reviews (id, store_id, review_no, product_no, product_name, review_kind, "
                                + "rating, body, body_fingerprint, attachment_url, attachment_kind, written_at, "
                                + "modified_at, display_state, replied, replied_at, related_review_no, related_body, "
                                + "first_upload_id, last_upload_id, created_at, updated_at) "
                                + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                        .params(id, storeId, line.reviewNo(), line.productNo(), line.productName(), line.kind(),
                                line.rating(), line.body(), ReviewBodyFingerprint.of(line.body()),
                                attachment == null ? null : attachment.url(),
                                attachment == null ? null : attachment.kind().name(), ts(line.writtenAt()),
                                ts(line.modifiedAt()), line.displayState(), line.replied(), ts(line.repliedAt()),
                                line.relatedReviewNo(), line.relatedBody(), uploadId, uploadId, ts(now), ts(now))
                        .update();
                event(storeId, id, EventKind.NEW, now);
                added++;
                continue;
            }
            boolean nowReplied = !before.replied() && line.replied();
            boolean contentChanged = !Objects.equals(before.productNo(), line.productNo())
                    || !Objects.equals(before.productName(), line.productName())
                    || !Objects.equals(before.kind(), line.kind())
                    || before.rating() != line.rating()
                    || !Objects.equals(before.body(), line.body())
                    || !Objects.equals(before.attachment(), attachment)
                    || !Objects.equals(before.writtenAt(), line.writtenAt())
                    || !Objects.equals(before.modifiedAt(), line.modifiedAt())
                    || !Objects.equals(before.displayState(), line.displayState())
                    || (before.replied() && !line.replied())
                    || (!nowReplied && !Objects.equals(before.repliedAt(), line.repliedAt()))
                    || !Objects.equals(before.relatedReviewNo(), line.relatedReviewNo())
                    || !Objects.equals(before.relatedBody(), line.relatedBody());
            if (!nowReplied && !contentChanged) {
                unchanged++;
                continue;
            }
            jdbc.sql("update store_reviews set product_no = ?, product_name = ?, review_kind = ?, rating = ?, body = ?, "
                            + "body_fingerprint = ?, attachment_url = ?, attachment_kind = ?, written_at = ?, "
                            + "modified_at = ?, display_state = ?, replied = ?, replied_at = ?, related_review_no = ?, "
                            + "related_body = ?, last_upload_id = ?, updated_at = ? where id = ?")
                    .params(line.productNo(), line.productName(), line.kind(), line.rating(), line.body(),
                            ReviewBodyFingerprint.of(line.body()), attachment == null ? null : attachment.url(),
                            attachment == null ? null : attachment.kind().name(), ts(line.writtenAt()),
                            ts(line.modifiedAt()), line.displayState(), line.replied(), ts(line.repliedAt()),
                            line.relatedReviewNo(), line.relatedBody(), uploadId, ts(now), before.id())
                    .update();
            if (contentChanged) {
                event(storeId, before.id(), EventKind.CHANGED, now);
                changed++;
            }
            // §4.2: an unclaimed reply task can no longer be posted as approved once the review's text or display
            // changed or the channel says it is answered.
            if (nowReplied || !Objects.equals(before.body(), line.body())
                    || !Objects.equals(before.displayState(), line.displayState())) {
                ReplyTasks.invalidateOpen(jdbc, before.id(), now);
            }
            if (nowReplied) {
                event(storeId, before.id(), EventKind.REPLIED, now);
                replied++;
            }
        }
        jdbc.sql("update review_uploads set new_count = ?, changed_count = ?, replied_count = ?, unchanged_count = ?, "
                        + "attachments_dropped = ? where id = ?")
                .params(added, changed, replied, unchanged, dropped, uploadId)
                .update();
        return new Imported(uploadId, lines.size(), added, changed, replied, unchanged, dropped, purged);
    }

    @Transactional(readOnly = true)
    public Page feed(UUID storeId, long cursor, int limit) {
        int size = Math.max(1, Math.min(limit, MAX_PAGE));
        List<Event> events = new ArrayList<>(jdbc.sql("select e.seq, e.kind, r.* from store_review_events e "
                        + "join store_reviews r on r.id = e.review_id where e.store_id = ? and e.seq > ? "
                        + "order by e.seq limit ?")
                .params(storeId, cursor, size + 1)
                .query((rs, n) -> new Event(rs.getLong("seq"), EventKind.valueOf(rs.getString("kind")), view(rs, n)))
                .list());
        boolean more = events.size() > size;
        if (more) {
            events.remove(events.size() - 1);
        }
        long last = events.isEmpty() ? cursor : events.get(events.size() - 1).seq();
        return new Page(List.copyOf(events), last, more);
    }

    /**
     * A PC saved everything up to {@code cursor}. False when the cursor names no event of this store. Never moves a
     * PC's confirmed position backwards.
     */
    @Transactional
    public boolean ack(UUID storeId, UUID installId, long cursor) {
        boolean known = jdbc.sql("select count(*) from store_review_events where store_id = ? and seq = ?")
                .params(storeId, cursor).query(Integer.class).single() > 0;
        if (!known) {
            return false;
        }
        Instant now = clock.instant();
        int moved = jdbc.sql("update store_review_acks set acked_seq = greatest(acked_seq, ?), acked_at = ? "
                        + "where store_id = ? and install_id = ?")
                .params(cursor, ts(now), storeId, installId)
                .update();
        if (moved == 0) {
            jdbc.sql("insert into store_review_acks (store_id, install_id, acked_seq, acked_at) values (?, ?, ?, ?)")
                    .params(storeId, installId, cursor, ts(now))
                    .update();
        }
        jdbc.sql("update store_reviews set pc_saved_at = ? where store_id = ? and pc_saved_at is null and id in "
                        + "(select review_id from store_review_events where store_id = ? and seq <= ?)")
                .params(ts(now), storeId, storeId, cursor)
                .update();
        startRetention(storeId);
        return true;
    }

    /**
     * The seller's PC (or the seller) judged that this review needs no reply. False for an unknown review. A review
     * already deleted at the end of its retention is answered as done — there is nothing left to keep — so a PC that
     * decided late is not refused forever.
     */
    @Transactional
    public boolean markNoReplyNeeded(UUID storeId, UUID reviewId) {
        int found = jdbc.sql("update store_reviews set no_reply_needed_at = coalesce(no_reply_needed_at, ?) "
                        + "where store_id = ? and id = ?")
                .params(ts(clock.instant()), storeId, reviewId)
                .update();
        if (found == 0) {
            return purged(jdbc, storeId, reviewId);
        }
        startRetention(storeId);
        return true;
    }

    /** D4: once both facts are known, the clock starts from the later of the two — and is never set again. */
    private void startRetention(UUID storeId) {
        List<UUID> ready = jdbc.sql("select id from store_reviews where store_id = ? and purge_after is null "
                        + "and pc_saved_at is not null and no_reply_needed_at is not null")
                .param(storeId).query(UUID.class).list();
        for (UUID id : ready) {
            Instant[] both = jdbc.sql("select pc_saved_at, no_reply_needed_at from store_reviews where id = ?")
                    .param(id)
                    .query((rs, n) -> new Instant[] {rs.getTimestamp(1).toInstant(), rs.getTimestamp(2).toInstant()})
                    .single();
            Instant start = both[0].isAfter(both[1]) ? both[0] : both[1];
            jdbc.sql("update store_reviews set purge_after = ? where id = ? and purge_after is null")
                    .params(ts(start.plus(NO_REPLY_RETENTION)), id)
                    .update();
        }
    }

    /** True when this store's review was deleted at the end of its retention (§6). */
    public static boolean purged(JdbcClient jdbc, UUID storeId, UUID reviewId) {
        return jdbc.sql("select count(*) from purged_reviews where store_id = ? and review_id = ?")
                .params(storeId, reviewId).query(Integer.class).single() > 0;
    }

    /** What a deleted review's 리뷰글번호 is kept as: its hash, never the number. */
    public static String reviewNoHash(String reviewNo) {
        return Codes.hash(reviewNo);
    }

    private boolean purgedNumber(UUID storeId, String reviewNo) {
        return jdbc.sql("select count(*) from purged_reviews where store_id = ? and review_no_hash = ?")
                .params(storeId, reviewNoHash(reviewNo)).query(Integer.class).single() > 0;
    }

    private void event(UUID storeId, UUID reviewId, EventKind kind, Instant at) {
        jdbc.sql("insert into store_review_events (store_id, review_id, kind, at) values (?, ?, ?, ?)")
                .params(storeId, reviewId, kind.name(), ts(at))
                .update();
    }

    private static ReviewView view(ResultSet rs, int n) throws SQLException {
        String url = rs.getString("attachment_url");
        return new ReviewView(rs.getObject("id", UUID.class), rs.getString("review_no"), rs.getString("product_no"),
                rs.getString("product_name"), rs.getString("review_kind"), rs.getInt("rating"), rs.getString("body"),
                rs.getString("body_fingerprint"),
                url == null ? null : new ReviewAttachment(url,
                        ReviewAttachment.Kind.valueOf(rs.getString("attachment_kind"))),
                instant(rs, "written_at"), instant(rs, "modified_at"), rs.getString("display_state"),
                rs.getBoolean("replied"), instant(rs, "replied_at"), rs.getString("related_review_no"),
                rs.getString("related_body"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    /** For tests and the retention job: a review's id by its number. */
    @Transactional(readOnly = true)
    public Optional<UUID> idOf(UUID storeId, String reviewNo) {
        return jdbc.sql("select id from store_reviews where store_id = ? and review_no = ?")
                .params(storeId, reviewNo).query(UUID.class).optional();
    }
}
