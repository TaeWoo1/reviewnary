package com.sellerops.vendorlink;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The calls this PC makes to the vendor server — its own registration (contract §2 · §7) and the review relay
 * (§3.2). Every method either returns a closed result or throws {@link Unreachable} (or, for the relay,
 * {@link Refused}); nothing here carries request material in an error.
 */
public interface VendorServerClient {

    record Code(String deviceCode, String userCode, Instant expiresAt, int intervalSeconds) {}

    /** What one poll of the grant learned. */
    sealed interface Poll permits Pending, Expired, Linked {}

    record Pending() implements Poll {}

    record Expired() implements Poll {}

    record Linked(String token, UUID installId, Instant expiresAt) implements Poll {}

    /** What the server says this PC's public address is. */
    sealed interface PublicIp permits Ipv4, NoIpv4, NotAuthorized {}

    record Ipv4(String address) implements PublicIp {}

    /** The server was reached over IPv6; there is no IPv4 to register. */
    record NoIpv4() implements PublicIp {}

    /** The install token was refused — revoked, expired, or the account is suspended. */
    record NotAuthorized() implements PublicIp {}

    Code requestCode(String deviceName, String appVersion, String publicKeyBase64);

    Poll poll(String deviceCode);

    PublicIp publicIp(String installToken);

    /** The vendor server's approval page with this code filled in — opened in the seller's default browser. */
    URI approvalPage(String userCode);

    /** Removes this install on the server. Best effort: the local record is dropped either way. */
    void unlink(String installToken);

    /* ── review relay (§3.2) ── */

    /**
     * A store of this install's seller account. {@code channel} is the server's closed value (e.g. {@code NAVER});
     * {@code lastUploadAt} is when a delegate last uploaded its export, null before the first.
     */
    record Store(UUID id, String channel, String label, Instant lastUploadAt) {}

    /** One review as the server holds it now. Times are instants; {@code attachmentUrl} is the one kept address. */
    record FeedReview(UUID id, String reviewNo, String productNo, String productName, int rating, String body,
                      String bodyFingerprint, String attachmentUrl, String attachmentKind, Instant writtenAt,
                      String displayState, boolean replied, Instant repliedAt) {}

    /** {@code kind} is {@code NEW}, {@code CHANGED} or {@code REPLIED}; the review is its state as of this event. */
    record FeedEvent(long seq, String kind, FeedReview review) {}

    record FeedPage(List<FeedEvent> events, long cursor, boolean more) {}

    /** This install's seller account's stores. */
    List<Store> stores(String installToken);

    /** The seller account this install belongs to, by its sign-in address — shown as where reviews come from. */
    Optional<String> accountEmail(String installToken);

    /** The store's review events after {@code cursor}, oldest first. */
    FeedPage reviewFeed(String installToken, UUID storeId, long cursor, int limit);

    /** Tells the server this PC stored everything up to {@code cursor}. */
    void ackReviews(String installToken, UUID storeId, long cursor);

    /* ── reply tasks (§4.3) ── */

    /** §17.9 초안 메타 as the server takes it. */
    record DraftMeta(String reviewBodyFingerprint, Instant generatedAt, String knowledgeFp, String policyFp,
                     String voiceFp) {}

    /**
     * A task as the server reports it to a PC. {@code reviewId} is the server's review id. {@code draftBasis} is the
     * three fingerprints the current version was drafted under, joined as {@code ReviewDraftBasis.Fingerprints#combined}
     * does; {@code basisNoteJson} the reason a PC recorded for it (owner decision 2026-10-23), as the server holds it;
     * {@code pcRecheckVersion} · {@code pcRecheck} the version a PC last checked and its result ({@code PASSED} ·
     * {@code FLAGGED:<codes>}).
     */
    record TaskSnapshot(UUID id, UUID reviewId, String state, int stateVersion, int draftVersion, String draftBody,
                        String draftFingerprint, String approvedFingerprint, boolean editedOnMobile,
                        Integer approvedVersion, String draftBasis, String basisNoteJson, Integer pcRecheckVersion,
                        String pcRecheck) {

        public TaskSnapshot(UUID id, UUID reviewId, String state, int stateVersion, int draftVersion, String draftBody,
                            String draftFingerprint, String approvedFingerprint, boolean editedOnMobile,
                            Integer approvedVersion, String draftBasis, String basisNoteJson) {
            this(id, reviewId, state, stateVersion, draftVersion, draftBody, draftFingerprint, approvedFingerprint,
                    editedOnMobile, approvedVersion, draftBasis, basisNoteJson, null, null);
        }

        public TaskSnapshot(UUID id, UUID reviewId, String state, int stateVersion, int draftVersion, String draftBody,
                            String draftFingerprint, String approvedFingerprint, boolean editedOnMobile) {
            this(id, reviewId, state, stateVersion, draftVersion, draftBody, draftFingerprint, approvedFingerprint,
                    editedOnMobile, null, null, null, null, null);
        }
    }

    /**
     * Why this PC stopped a version — what changed, the promise words that lost their ground, and the reply's own
     * sentences that carry them (owner decision 2026-10-23). The server keeps it for the phone and the delegate.
     */
    record BasisNote(List<String> codes, List<ChangedSource> sources, List<String> promises, List<String> sentences) {}

    /** A cited product or company document that was edited after the version, or taken out of use. */
    record ChangedSource(String kind, String title, Instant changedAt, boolean removed) {}

    record TaskChange(long seq, String state, TaskSnapshot task) {}

    record TaskChanges(List<TaskChange> changes, long cursor, boolean more) {}

    /** Opens a task for the review, or makes this text its next version while it awaits approval. */
    TaskSnapshot submitDraft(String installToken, UUID storeId, UUID reviewId, String body, DraftMeta meta);

    /** Approves the task's exact version and text (contract §4.3) — the seller pressing it on this PC. */
    TaskSnapshot approve(String installToken, UUID taskId, int version, String fingerprint, String commandId);

    /** Cancels the task, or asks its delegate to stop once claimed (§4.2). */
    TaskSnapshot cancel(String installToken, UUID taskId, String commandId);

    /** The store's task transitions after {@code cursor}, in order. */
    TaskChanges taskChanges(String installToken, UUID storeId, long cursor, int limit);

    /** One task as the server holds it now. */
    TaskSnapshot task(String installToken, UUID taskId);

    /** What the server did when this PC reported the fingerprints its drafts are now written under (§4.2). */
    record PolicyCheck(List<UUID> sentBackForRecheck, List<UUID> withdrawalSuggested) {}

    /** Reports the store's current draft fingerprints; unapproved drafts made under others are sent back. */
    PolicyCheck policyCheck(String installToken, UUID storeId, String knowledgeFp, String policyFp, String voiceFp);

    /** Records this PC's check of one version — passed, or held with closed codes and, when held, why. */
    TaskSnapshot pcRecheck(String installToken, UUID taskId, int draftVersion, boolean passed, List<String> codes,
                           BasisNote note);

    /**
     * Records this PC's check of an approved text after the basis changed: {@code hold} for a substantive conflict
     * (the server holds it, or asks its delegate to stop), otherwise the note is only kept beside the approval.
     */
    TaskSnapshot checkApproved(String installToken, UUID taskId, int approvedVersion, boolean hold, BasisNote note,
                               String commandId);

    /** Tells the server the seller decided this review needs no reply (§3.2, D4). */
    void markNoReplyNeeded(String installToken, UUID storeId, UUID reviewId, String commandId);

    /** The server refused a task request; {@code code} is its closed error ({@code task_in_progress}, …). */
    final class TaskRefused extends RuntimeException {
        private final String code;

        public TaskRefused(String code) {
            super("공급자 서버가 이 작업 요청을 받지 않았습니다.");
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** The install token or the store was refused — revoked, suspended, or not this account's. */
    final class Refused extends RuntimeException {
        public Refused() {
            super("공급자 서버가 이 PC의 요청을 받지 않았습니다.");
        }
    }

    /** The server could not be reached or answered something this client does not understand. */
    final class Unreachable extends RuntimeException {
        public Unreachable() {
            super("공급자 서버에 연결하지 못했습니다.");
        }
    }
}
