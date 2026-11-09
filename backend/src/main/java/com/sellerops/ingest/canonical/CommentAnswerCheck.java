package com.sellerops.ingest.canonical;

import java.time.Instant;

/**
 * What one collection learned by looking for a shop answer written as a COMMENT on this inquiry (Cafe24 board 6,
 * 2026-11-08). Carried on {@link CanonicalInquiry#commentCheck()}.
 *
 * <p><b>Null on the canonical row is its own statement: the comment lane did not run for this row.</b> Every other
 * channel, every thread reply, every article the source already called answered, and every collection without the lane
 * carry null, and ingestion then leaves the stored result exactly as it was. A null never means "no comment".
 *
 * <p>{@code attemptedAt} is when the lane looked, whatever it found. It is not "when we confirmed" — only {@link
 * Result#confirmed()} results may be read that way, which is why ingestion keeps a separate success-only time.
 *
 * <p>No comment body, author id or comment number crosses this boundary.
 */
public record CommentAnswerCheck(Result result, Instant attemptedAt) {

    public enum Result {
        /** A comment by the shop itself (proven by the platform's own author condition), with a parseable time. */
        SHOP_ANSWERED,
        /** Looked, and there is no shop comment: no comments at all, or customer comments only. */
        NO_SHOP_COMMENT,
        /**
         * Could not look: a failed or rate-limited read, the read cap, an incomplete discovery list, an unknown author,
         * an unreadable time. A record of failing to check — never evidence that the shop has not answered.
         */
        NOT_CHECKED;

        /** True when the lane actually saw the comments it needed to see. */
        public boolean confirmed() {
            return this != NOT_CHECKED;
        }
    }

    public CommentAnswerCheck {
        if (result == null || attemptedAt == null) {
            throw new IllegalArgumentException("comment check needs a result and the time it was attempted");
        }
    }
}
