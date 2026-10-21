package com.sellerops.vendorlink;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The vendor server, holding one NAVER store: its review feed, its reply tasks and what the PC told it. The grant
 * answers with {@link #TOKEN} at once. {@code JdkVendorServerClientTest} holds the wire shapes; this holds behaviour.
 */
final class FakeVendorServer implements VendorServerClient {

    static final String TOKEN = "rvi_synthetic-install-token-for-tests";

    final UUID store;
    final Map<String, UUID> ids = new HashMap<>();
    final List<FeedEvent> feed = new ArrayList<>();
    final List<Long> cursorsAsked = new ArrayList<>();
    final List<Long> acks = new ArrayList<>();
    /** This many acks are lost on the way, like a dropped connection. */
    int dropAcks;
    final List<String> bearers = new ArrayList<>();
    boolean refuse;

    /** Every draft the PC submitted, with its meta. */
    final List<Submitted> submitted = new ArrayList<>();
    final List<TaskChange> changes = new ArrayList<>();
    final Map<UUID, TaskSnapshot> tasks = new HashMap<>();
    /** When set, the next submit is refused with this code. */
    String refuseNextSubmit;
    final List<UUID> noReply = new ArrayList<>();
    final List<String> noReplyCommands = new ArrayList<>();
    final List<String> approveCommands = new ArrayList<>();

    record Submitted(UUID reviewId, String body, DraftMeta meta) {}

    FakeVendorServer(UUID store) {
        this.store = store;
    }

    void event(String kind, FeedReview review) {
        feed.add(new FeedEvent(feed.size() + 1, kind, review));
    }

    /** The server moves a task: a new state version, optionally new text (a phone edit), recorded as a change. */
    TaskSnapshot move(UUID taskId, String state, String body, String approvedFingerprint, boolean editedOnMobile) {
        TaskSnapshot t = tasks.get(taskId);
        TaskSnapshot next = new TaskSnapshot(t.id(), t.reviewId(), state, t.stateVersion() + 1,
                body.equals(t.draftBody()) ? t.draftVersion() : t.draftVersion() + 1, body,
                com.sellerops.attention.reply.ReviewReplyFingerprint.of(body), approvedFingerprint,
                editedOnMobile || t.editedOnMobile());
        tasks.put(taskId, next);
        changes.add(new TaskChange(changes.size() + 1, state, next));
        return next;
    }

    @Override
    public Code requestCode(String deviceName, String appVersion, String publicKeyBase64) {
        return new Code("device-code", "BCDFGHJK", Instant.parse("2026-10-21T00:10:00Z"), 3);
    }

    @Override
    public Poll poll(String deviceCode) {
        return new Linked(TOKEN, UUID.randomUUID(), Instant.parse("2027-04-15T00:00:00Z"));
    }

    @Override
    public PublicIp publicIp(String installToken) {
        return new NoIpv4();
    }

    @Override
    public URI approvalPage(String userCode) {
        return URI.create("http://127.0.0.1:8090/link?code=" + userCode);
    }

    @Override
    public void unlink(String installToken) {
    }

    @Override
    public List<Store> stores(String installToken) {
        bearers.add(installToken);
        if (refuse) {
            throw new Refused();
        }
        return List.of(new Store(store, "NAVER", "합성 스토어"));
    }

    @Override
    public FeedPage reviewFeed(String installToken, UUID storeId, long cursor, int limit) {
        bearers.add(installToken);
        cursorsAsked.add(cursor);
        List<FeedEvent> after = feed.stream().filter(e -> e.seq() > cursor).toList();
        List<FeedEvent> page = after.subList(0, Math.min(limit, after.size()));
        long last = page.isEmpty() ? cursor : page.get(page.size() - 1).seq();
        return new FeedPage(page, last, after.size() > page.size());
    }

    @Override
    public void ackReviews(String installToken, UUID storeId, long cursor) {
        bearers.add(installToken);
        if (dropAcks > 0) {
            dropAcks--;
            throw new Unreachable();
        }
        acks.add(cursor);
    }

    @Override
    public TaskSnapshot submitDraft(String installToken, UUID storeId, UUID reviewId, String body, DraftMeta meta) {
        bearers.add(installToken);
        if (refuseNextSubmit != null) {
            String code = refuseNextSubmit;
            refuseNextSubmit = null;
            throw new TaskRefused(code);
        }
        submitted.add(new Submitted(reviewId, body, meta));
        TaskSnapshot open = tasks.values().stream().filter(t -> t.reviewId().equals(reviewId)
                && t.state().equals("AWAITING_APPROVAL")).findFirst().orElse(null);
        String fp = com.sellerops.attention.reply.ReviewReplyFingerprint.of(body);
        TaskSnapshot next = open == null
                ? new TaskSnapshot(UUID.randomUUID(), reviewId, "AWAITING_APPROVAL", 0, 1, body, fp, null, false)
                : new TaskSnapshot(open.id(), reviewId, "AWAITING_APPROVAL", open.stateVersion() + 1,
                        open.draftVersion() + 1, body, fp, null, false);
        tasks.put(next.id(), next);
        changes.add(new TaskChange(changes.size() + 1, next.state(), next));
        return next;
    }

    /** As the server: only the latest version and its exact text, and only while it awaits approval. */
    @Override
    public TaskSnapshot approve(String installToken, UUID taskId, int version, String fingerprint, String commandId) {
        bearers.add(installToken);
        approveCommands.add(commandId);
        TaskSnapshot t = tasks.get(taskId);
        if (t == null || !t.state().equals("AWAITING_APPROVAL")) {
            throw new TaskRefused("wrong_state");
        }
        if (t.draftVersion() != version || !t.draftFingerprint().equals(fingerprint)) {
            throw new TaskRefused("fingerprint_mismatch");
        }
        return move(taskId, "APPROVED", t.draftBody(), fingerprint, false);
    }

    /** As the server: ended at once until claimed, then a request the delegate settles. */
    @Override
    public TaskSnapshot cancel(String installToken, UUID taskId, String commandId) {
        bearers.add(installToken);
        TaskSnapshot t = tasks.get(taskId);
        String to = switch (t == null ? "" : t.state()) {
            case "AWAITING_APPROVAL", "RECHECK_NEEDED", "APPROVED" -> "CANCELLED";
            case "CLAIMED" -> "CANCEL_REQUESTED";
            default -> throw new TaskRefused("wrong_state");
        };
        return move(taskId, to, t.draftBody(), t.approvedFingerprint(), false);
    }

    @Override
    public TaskChanges taskChanges(String installToken, UUID storeId, long cursor, int limit) {
        bearers.add(installToken);
        List<TaskChange> after = changes.stream().filter(c -> c.seq() > cursor).toList();
        List<TaskChange> page = after.subList(0, Math.min(limit, after.size()));
        long last = page.isEmpty() ? cursor : page.get(page.size() - 1).seq();
        return new TaskChanges(page, last, after.size() > page.size());
    }

    @Override
    public void markNoReplyNeeded(String installToken, UUID storeId, UUID reviewId, String commandId) {
        bearers.add(installToken);
        noReply.add(reviewId);
        noReplyCommands.add(commandId);
    }
}
