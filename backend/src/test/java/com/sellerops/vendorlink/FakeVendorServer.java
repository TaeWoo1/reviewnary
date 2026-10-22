package com.sellerops.vendorlink;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    /** When the delegate last uploaded {@link #store}'s export, as the store list says. */
    Instant lastUploadAt;
    /** Stores the operator added to the account later. Their feeds are empty. */
    final List<Store> laterStores = new ArrayList<>();

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

    /** The fingerprints of the PC's last policy check, as the server keeps them per store; null before one. */
    DraftMeta policy;
    /** Every policy check the PC made. */
    final List<DraftMeta> policyChecks = new ArrayList<>();
    record Recheck(UUID taskId, int draftVersion, boolean passed, List<String> codes, BasisNote note) {}
    final List<Recheck> rechecks = new ArrayList<>();
    record ApprovedCheck(UUID taskId, int approvedVersion, boolean hold, BasisNote note, String commandId) {}
    final List<ApprovedCheck> approvedChecks = new ArrayList<>();
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            com.fasterxml.jackson.databind.json.JsonMapper.builder().findAndAddModules()
                    .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
    /** The fingerprints each task's current version was drafted under. */
    final Map<UUID, DraftMeta> metaOf = new HashMap<>();

    FakeVendorServer(UUID store) {
        this.store = store;
    }

    void event(String kind, FeedReview review) {
        feed.add(new FeedEvent(feed.size() + 1, kind, review));
    }

    /** The server moves a task: a new state version, optionally new text (a phone edit), recorded as a change. */
    TaskSnapshot move(UUID taskId, String state, String body, String approvedFingerprint, boolean editedOnMobile) {
        TaskSnapshot t = tasks.get(taskId);
        boolean sameText = body.equals(t.draftBody());
        int version = sameText ? t.draftVersion() : t.draftVersion() + 1;
        TaskSnapshot next = new TaskSnapshot(t.id(), t.reviewId(), state, t.stateVersion() + 1, version, body,
                com.sellerops.attention.reply.ReviewReplyFingerprint.of(body), approvedFingerprint,
                editedOnMobile || t.editedOnMobile(),
                "APPROVED".equals(state) ? Integer.valueOf(version) : t.approvedVersion(), basisOf(t.id()),
                sameText ? t.basisNoteJson() : null);
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
        List<Store> all = new ArrayList<>(List.of(new Store(store, "NAVER", "합성 스토어", lastUploadAt)));
        all.addAll(laterStores);
        return all;
    }

    @Override
    public Optional<String> accountEmail(String installToken) {
        bearers.add(installToken);
        if (refuse) {
            throw new Refused();
        }
        return Optional.of("seller@example.com");
    }

    @Override
    public FeedPage reviewFeed(String installToken, UUID storeId, long cursor, int limit) {
        bearers.add(installToken);
        if (!store.equals(storeId)) {
            return new FeedPage(List.of(), cursor, false);
        }
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
                && (t.state().equals("AWAITING_APPROVAL") || t.state().equals("RECHECK_NEEDED")
                        || t.state().equals("HELD"))).findFirst()
                .orElse(null);
        String fp = com.sellerops.attention.reply.ReviewReplyFingerprint.of(body);
        // As the server: a draft under other fingerprints than the last policy check lands sent back, and the same
        // text drafted again keeps the phone's mark.
        String lands = underPolicy(meta) ? "AWAITING_APPROVAL" : "RECHECK_NEEDED";
        String basis = meta.knowledgeFp() + "|" + meta.policyFp() + "|" + meta.voiceFp();
        TaskSnapshot next = open == null
                ? new TaskSnapshot(UUID.randomUUID(), reviewId, lands, 0, 1, body, fp, null, false, null, basis, null)
                : new TaskSnapshot(open.id(), reviewId, lands, open.stateVersion() + 1,
                        open.draftVersion() + 1, body, fp, null, open.draftBody().equals(body) && open.editedOnMobile(),
                        open.approvedVersion(), basis, null);
        tasks.put(next.id(), next);
        metaOf.put(next.id(), meta);
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
            case "AWAITING_APPROVAL", "RECHECK_NEEDED", "APPROVED", "HELD" -> "CANCELLED";
            case "CLAIMED", "HOLD_REQUESTED" -> "CANCEL_REQUESTED";
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

    private String basisOf(UUID taskId) {
        DraftMeta m = metaOf.get(taskId);
        return m == null ? null : m.knowledgeFp() + "|" + m.policyFp() + "|" + m.voiceFp();
    }

    private boolean underPolicy(DraftMeta meta) {
        return policy == null || (policy.knowledgeFp().equals(meta.knowledgeFp())
                && policy.policyFp().equals(meta.policyFp()) && policy.voiceFp().equals(meta.voiceFp()));
    }

    @Override
    public TaskSnapshot task(String installToken, UUID taskId) {
        bearers.add(installToken);
        return tasks.get(taskId);
    }

    /** As the server: waiting drafts under other fingerprints go back; approved ones are only listed. */
    @Override
    public PolicyCheck policyCheck(String installToken, UUID storeId, String knowledgeFp, String policyFp,
                                   String voiceFp) {
        bearers.add(installToken);
        policy = new DraftMeta(null, null, knowledgeFp, policyFp, voiceFp);
        policyChecks.add(policy);
        List<UUID> sentBack = new ArrayList<>();
        List<UUID> suggested = new ArrayList<>();
        for (TaskSnapshot t : List.copyOf(tasks.values())) {
            DraftMeta m = metaOf.get(t.id());
            if (m == null || underPolicy(m)) {
                continue;
            }
            if (t.state().equals("AWAITING_APPROVAL")) {
                move(t.id(), "RECHECK_NEEDED", t.draftBody(), null, false);
                sentBack.add(t.id());
            } else if (t.state().equals("APPROVED") || t.state().equals("CLAIMED")) {
                suggested.add(t.id());
            }
        }
        return new PolicyCheck(sentBack, suggested);
    }

    @Override
    public TaskSnapshot pcRecheck(String installToken, UUID taskId, int draftVersion, boolean passed,
                                  List<String> codes, BasisNote note) {
        bearers.add(installToken);
        rechecks.add(new Recheck(taskId, draftVersion, passed, List.copyOf(codes), note));
        if (note != null) {
            tasks.put(taskId, withNote(tasks.get(taskId), tasks.get(taskId).state(), 0, note));
        }
        return tasks.get(taskId);
    }

    /** As the server: an approved or claimed text is held (or its delegate asked to stop), or only noted. */
    @Override
    public TaskSnapshot checkApproved(String installToken, UUID taskId, int approvedVersion, boolean hold,
                                      BasisNote note, String commandId) {
        bearers.add(installToken);
        approvedChecks.add(new ApprovedCheck(taskId, approvedVersion, hold, note, commandId));
        TaskSnapshot t = tasks.get(taskId);
        if (!t.state().equals("APPROVED") && !t.state().equals("CLAIMED")) {
            throw new TaskRefused("wrong_state");
        }
        String to = !hold ? t.state() : t.state().equals("APPROVED") ? "HELD" : "HOLD_REQUESTED";
        TaskSnapshot next = withNote(t, to, hold ? 1 : 0, note);
        tasks.put(taskId, next);
        if (hold) {
            changes.add(new TaskChange(changes.size() + 1, to, next));
        }
        return next;
    }

    private static TaskSnapshot withNote(TaskSnapshot t, String state, int step, BasisNote note) {
        try {
            return new TaskSnapshot(t.id(), t.reviewId(), state, t.stateVersion() + step, t.draftVersion(),
                    t.draftBody(), t.draftFingerprint(), t.approvedFingerprint(), t.editedOnMobile(),
                    t.approvedVersion(), t.draftBasis(), JSON.writeValueAsString(note));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void markNoReplyNeeded(String installToken, UUID storeId, UUID reviewId, String commandId) {
        bearers.add(installToken);
        noReply.add(reviewId);
        noReplyCommands.add(commandId);
    }
}
