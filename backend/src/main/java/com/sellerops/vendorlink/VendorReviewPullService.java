package com.sellerops.vendorlink;

import com.sellerops.channel.Channel;
import com.sellerops.channel.ChannelRepository;
import com.sellerops.collect.runtime.CollectionMethod;
import com.sellerops.ingest.IngestFollowUp;
import com.sellerops.ingest.IngestOutcome;
import com.sellerops.ingest.IngestionService;
import com.sellerops.ingest.canonical.CanonicalReview;
import com.sellerops.ingest.canonical.ChannelProductRef;
import com.sellerops.review.Review;
import com.sellerops.review.ReviewReplyState;
import com.sellerops.review.ReviewRepository;
import com.sellerops.sync.SyncJob;
import com.sellerops.sync.SyncJobRepository;
import com.sellerops.vendorlink.VendorServerClient.FeedEvent;
import com.sellerops.vendorlink.VendorServerClient.FeedPage;
import com.sellerops.vendorlink.VendorServerClient.FeedReview;
import com.sellerops.vendorlink.VendorServerClient.Store;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * <b>Pulls the vendor server's review feed into this PC's review lane</b> — contract
 * {@code docs/vendor_server_contract_v1.md} §3.2.
 *
 * <ol>
 *   <li><b>Which stores.</b> A store is pulled into the organisation it is bound to. A binding is made only by
 *       {@link #start} — the seller's 「리뷰 받기 시작」 for the stores the screen showed, into that seller's
 *       organisation. No pull binds: a store the server adds later is shown and waits to be started, so nothing here
 *       guesses whose reviews these are. A store the seller stopped ({@link #setPaused}) brings no new review, but
 *       the replies already handed over in it keep following the server.</li>
 *   <li><b>Each event is the review as it is now.</b> A review this PC does not hold is ingested through the
 *       ordinary lane ({@link IngestionService}, keyed on 리뷰글번호 as every NAVER source is). One it holds is brought
 *       up to date: text and rating as the server says, the reply flag forward only (the lane never un-answers a
 *       review). The server's review id, body fingerprint, display state and one attachment go in
 *       {@link VendorReviewRef} — the lane has no column for them and a reply task needs the first two.</li>
 *   <li><b>The cursor moves only past what was stored.</b> An event that fails stops the store's pull at the event
 *       before it; the next pull starts there. Re-applying an event is harmless — it sets the same state again.
 *       The server is told ({@code ack}) after the cursor is saved, best effort: the ack only starts its retention
 *       clock (D4). Which cursor the server confirmed is kept apart ({@code ackedCursor}), so a lost ack is repeated
 *       by the next pull even when that pull brings nothing new.</li>
 * </ol>
 *
 * <p>Deliberately not one transaction, like the Coupang handoff: a failure late in a page must not roll back
 * reviews already stored, and the cursor is what makes a repeat safe.
 */
@Service
public class VendorReviewPullService {

    private static final Logger log = LoggerFactory.getLogger(VendorReviewPullService.class);

    static final String NAVER = "NAVER";
    static final int PAGE_SIZE = 200;
    /** One pull reads at most this many pages per store; the rest waits for the next pull. */
    static final int MAX_PAGES = 25;

    public enum State { OFF, NOT_LINKED, NOT_AUTHORIZED, UNREACHABLE, PULLED }

    /**
     * What one store's pull did. {@code bound} false: the store belongs to no organisation on this PC yet;
     * {@code paused}: the seller stopped receiving it, so no review was asked for — only its reply tasks.
     */
    public record StoreResult(UUID storeId, String label, boolean bound, boolean otherOrganisation, int received,
                              int inserted, int updated, int failed, long cursor, boolean more, int taskChanges,
                              int noReplyReported, boolean paused) {}

    /** Where a server store stands for the organisation looking at it. */
    public enum Standing { NOT_RECEIVING, RECEIVING, PAUSED, OTHER_ORGANISATION }

    /** Why a store's last pull failed; null after one that succeeded. */
    public enum Failure { UNREACHABLE, NOT_AUTHORIZED, NOT_STORED }

    /**
     * One server store as the 「채널 연결」 section shows it. Two times, never one: {@code lastCheckedAt} is when this
     * PC last asked the server, {@code lastUploadAt} when a delegate last uploaded the store's export — a PC that
     * asked a minute ago still holds nothing newer than the last upload. {@code received}: reviews held here from it.
     */
    public record StoreView(UUID storeId, String label, Standing standing, Instant lastCheckedAt, Instant lastUploadAt,
                            long received, Failure failure, Instant failedAt) {}

    /**
     * The section: whose server account this PC receives for and each of its NAVER stores. Unreachable or refused,
     * the stores are this organisation's bound ones as last seen, so the seller still sees what they had.
     */
    public record Overview(State state, String accountEmail, List<StoreView> stores) {}

    public record PullResult(State state, List<StoreResult> stores) {

        static PullResult of(State state) {
            return new PullResult(state, List.of());
        }
    }

    private final VendorLinkService link;
    private final VendorStoreBindingRepository bindings;
    private final VendorReviewRefRepository refs;
    private final ReviewRepository reviews;
    private final ChannelRepository channels;
    private final IngestionService ingestion;
    private final IngestFollowUp followUp;
    private final SyncJobRepository syncJobs;
    /** Reply tasks follow each review pull (§4); null where a test pulls reviews alone. */
    private final VendorReplyTaskService replyTasks;
    private final Clock clock;

    /** Tells the seller's open screens to read again; null where a test pulls without them. */
    private com.sellerops.live.ScreenChanges screens;

    @Autowired(required = false)
    public void setScreenChanges(com.sellerops.live.ScreenChanges screens) {
        this.screens = screens;
    }

    /** Links a relayed review to its product by its channel product number; null in a bare test. */
    private com.sellerops.product.ReviewProductLink reviewLinks;

    @Autowired(required = false)
    public void setReviewLinks(com.sellerops.product.ReviewProductLink reviewLinks) {
        this.reviewLinks = reviewLinks;
    }

    @Autowired
    public VendorReviewPullService(VendorLinkService link, VendorStoreBindingRepository bindings,
                                   VendorReviewRefRepository refs, ReviewRepository reviews,
                                   ChannelRepository channels, IngestionService ingestion, IngestFollowUp followUp,
                                   SyncJobRepository syncJobs, VendorReplyTaskService replyTasks) {
        this(link, bindings, refs, reviews, channels, ingestion, followUp, syncJobs, replyTasks, Clock.systemUTC());
    }

    VendorReviewPullService(VendorLinkService link, VendorStoreBindingRepository bindings,
                            VendorReviewRefRepository refs, ReviewRepository reviews, ChannelRepository channels,
                            IngestionService ingestion, IngestFollowUp followUp, SyncJobRepository syncJobs,
                            Clock clock) {
        this(link, bindings, refs, reviews, channels, ingestion, followUp, syncJobs, null, clock);
    }

    VendorReviewPullService(VendorLinkService link, VendorStoreBindingRepository bindings,
                            VendorReviewRefRepository refs, ReviewRepository reviews, ChannelRepository channels,
                            IngestionService ingestion, IngestFollowUp followUp, SyncJobRepository syncJobs,
                            VendorReplyTaskService replyTasks, Clock clock) {
        this.link = link;
        this.bindings = bindings;
        this.refs = refs;
        this.reviews = reviews;
        this.channels = channels;
        this.ingestion = ingestion;
        this.followUp = followUp;
        this.syncJobs = syncJobs;
        this.replyTasks = replyTasks;
        this.clock = clock;
    }

    /**
     * 「리뷰 받기 시작」: binds exactly the stores the seller was shown — named by id, on the server, NAVER, not yet bound
     * to any organisation here — to {@code orgId}, then pulls that organisation's stores. A store the server adds
     * later is not taken by this or any later pull; it waits to be shown and started the same way.
     */
    public synchronized PullResult start(UUID orgId, List<UUID> storeIds) {
        Objects.requireNonNull(orgId);
        if (link.client() == null || link.status().state() == VendorLinkService.State.OFF) {
            return PullResult.of(State.OFF);
        }
        Optional<String> token = link.installToken();
        if (token.isEmpty()) {
            return PullResult.of(State.NOT_LINKED);
        }
        Optional<Channel> naver = channels.findByCode(NAVER);
        if (naver.isEmpty()) {
            return PullResult.of(State.PULLED);
        }
        List<Store> stores;
        try {
            stores = link.client().stores(token.get());
        } catch (VendorServerClient.Refused e) {
            return PullResult.of(State.NOT_AUTHORIZED);
        } catch (VendorServerClient.Unreachable e) {
            return PullResult.of(State.UNREACHABLE);
        }
        for (Store store : stores) {
            if (NAVER.equals(store.channel()) && storeIds.contains(store.id())
                    && bindings.findByVendorStoreId(store.id()).isEmpty()) {
                bind(store, orgId, naver.get().getId());
            }
        }
        return pull(orgId, "MANUAL");
    }

    /** 「지금 받기」: this organisation's bound stores (a stopped one for its reply tasks only). Binds nothing. */
    public synchronized PullResult pullFor(UUID orgId) {
        return pull(Objects.requireNonNull(orgId), "MANUAL");
    }

    /** Stops or resumes receiving one store of this organisation. False when it is not bound to it. */
    public synchronized boolean setPaused(UUID orgId, UUID storeId, boolean paused) {
        Optional<VendorStoreBinding> bound = bindings.findByVendorStoreId(storeId)
                .filter(b -> b.getOrgId().equals(orgId));
        bound.ifPresent(b -> {
            b.setPaused(paused);
            bindings.save(b);
        });
        return bound.isPresent();
    }

    /** What the section shows. Asks the server for the store list and the account; stores nothing but their ages. */
    public synchronized Overview overview(UUID orgId) {
        if (link.client() == null || link.status().state() == VendorLinkService.State.OFF) {
            return new Overview(State.OFF, null, List.of());
        }
        Optional<String> token = link.installToken();
        if (token.isEmpty()) {
            return new Overview(State.NOT_LINKED, null, List.of());
        }
        List<Store> stores;
        String email;
        try {
            stores = link.client().stores(token.get());
            email = link.client().accountEmail(token.get()).orElse(null);
        } catch (VendorServerClient.Refused e) {
            return new Overview(State.NOT_AUTHORIZED, null, lastSeen(orgId));
        } catch (VendorServerClient.Unreachable e) {
            return new Overview(State.UNREACHABLE, null, lastSeen(orgId));
        }
        List<StoreView> views = new ArrayList<>();
        for (Store store : stores) {
            if (!NAVER.equals(store.channel())) {
                continue;
            }
            Optional<VendorStoreBinding> bound = bindings.findByVendorStoreId(store.id());
            if (bound.isEmpty()) {
                views.add(new StoreView(store.id(), store.label(), Standing.NOT_RECEIVING, null, store.lastUploadAt(),
                        0, null, null));
            } else if (!bound.get().getOrgId().equals(orgId)) {
                views.add(new StoreView(store.id(), store.label(), Standing.OTHER_ORGANISATION, null, null, 0, null,
                        null));
            } else {
                VendorStoreBinding b = bound.get();
                if (!Objects.equals(b.getLastUploadAt(), store.lastUploadAt()) && store.lastUploadAt() != null) {
                    b.setLastUploadAt(store.lastUploadAt());
                    bindings.save(b);
                }
                views.add(view(b, store.label()));
            }
        }
        return new Overview(State.PULLED, email, views);
    }

    private List<StoreView> lastSeen(UUID orgId) {
        return bindings.findAllByOrgIdOrderByCreatedAt(orgId).stream().map(b -> view(b, b.getStoreLabel())).toList();
    }

    private StoreView view(VendorStoreBinding b, String label) {
        return new StoreView(b.getVendorStoreId(), label, b.isPaused() ? Standing.PAUSED : Standing.RECEIVING,
                b.getLastPulledAt(), b.getLastUploadAt(), refs.countByVendorStoreId(b.getVendorStoreId()),
                b.getLastFailure() == null ? null : Failure.valueOf(b.getLastFailure()), b.getLastFailureAt());
    }

    /**
     * What a waiting question names ({@link VendorReviewArrivalWatch}): each store this PC receives — not a stopped one
     * — with the cursor stored for it. Empty when the link is off or not made.
     */
    public Map<UUID, Long> receivingCursors() {
        if (link.client() == null || link.status().state() == VendorLinkService.State.OFF
                || link.installToken().isEmpty()) {
            return Map.of();
        }
        Map<UUID, Long> cursors = new LinkedHashMap<>();
        for (VendorStoreBinding binding : bindings.findAll()) {
            if (!binding.isPaused()) {
                cursors.put(binding.getVendorStoreId(), binding.getReviewCursor());
            }
        }
        return cursors;
    }

    /**
     * Asks the server to say when one of these stores has news, and waits for the answer (up to about 25 s; empty when
     * nothing came). Deliberately not under this service's lock: the screen and a pull must not queue behind a wait.
     */
    public List<UUID> waitForNews(Map<UUID, Long> cursors) {
        VendorServerClient client = link.client();
        Optional<String> token = link.installToken();
        if (client == null || token.isEmpty() || cursors.isEmpty()) {
            return List.of();
        }
        return client.waitForReviews(token.get(), cursors);
    }

    /** The scheduled pull: every bound store (a stopped one for its reply tasks only), binding nothing new. */
    public synchronized PullResult pullBound() {
        return pull(null, "SCHEDULED");
    }

    private PullResult pull(UUID sellerOrg, String trigger) {
        if (link.client() == null || link.status().state() == VendorLinkService.State.OFF) {
            return PullResult.of(State.OFF);
        }
        Optional<String> token = link.installToken();
        if (token.isEmpty()) {
            return PullResult.of(State.NOT_LINKED);
        }
        Optional<Channel> naver = channels.findByCode(NAVER);
        if (naver.isEmpty()) {
            return PullResult.of(State.PULLED);
        }
        VendorServerClient client = link.client();
        List<Store> stores;
        try {
            stores = client.stores(token.get());
        } catch (VendorServerClient.Refused e) {
            return PullResult.of(State.NOT_AUTHORIZED);
        } catch (VendorServerClient.Unreachable e) {
            return PullResult.of(State.UNREACHABLE);
        }
        List<StoreResult> results = new ArrayList<>();
        for (Store store : stores) {
            if (!NAVER.equals(store.channel())) {
                continue;
            }
            Optional<VendorStoreBinding> bound = bindings.findByVendorStoreId(store.id());
            if (bound.isEmpty()) {
                // Never bound here by a pull: only 「리뷰 받기 시작」 binds, and only what it was shown (start).
                results.add(new StoreResult(store.id(), store.label(), false, false, 0, 0, 0, 0, 0, false, 0, 0,
                        false));
                continue;
            }
            VendorStoreBinding binding = bound.get();
            if (sellerOrg != null && !sellerOrg.equals(binding.getOrgId())) {
                // Bound to another organisation on this PC: its reviews are not this seller's to pull.
                results.add(new StoreResult(store.id(), store.label(), true, true, 0, 0, 0, 0,
                        binding.getReviewCursor(), false, 0, 0, false));
                continue;
            }
            if (binding.isPaused()) {
                // Stopping stops new reviews only. Replies already handed over keep following the server — the
                // phone's approval, the delegate's post — so the review feed is not asked, the task changes are.
                try {
                    results.add(followTasks(token.get(), binding, store.label()));
                } catch (VendorServerClient.Refused e) {
                    failed(binding, Failure.NOT_AUTHORIZED);
                    return new PullResult(State.NOT_AUTHORIZED, results);
                } catch (VendorServerClient.Unreachable e) {
                    failed(binding, Failure.UNREACHABLE);
                    return new PullResult(State.UNREACHABLE, results);
                }
                continue;
            }
            binding.setLastUploadAt(store.lastUploadAt() != null ? store.lastUploadAt() : binding.getLastUploadAt());
            try {
                StoreResult result = pullStore(client, token.get(), binding, store.label(), trigger);
                failed(binding, result.failed() > 0 ? Failure.NOT_STORED : null);
                results.add(result);
            } catch (VendorServerClient.Refused e) {
                failed(binding, Failure.NOT_AUTHORIZED);
                return new PullResult(State.NOT_AUTHORIZED, results);
            } catch (VendorServerClient.Unreachable e) {
                failed(binding, Failure.UNREACHABLE);
                return new PullResult(State.UNREACHABLE, results);
            }
        }
        return new PullResult(State.PULLED, results);
    }

    /** After the pull's own writes and their follow-up: a screen that reads again now sees them classified. */
    private void screensChanged(VendorStoreBinding binding) {
        if (screens != null) {
            screens.changed(binding.getOrgId());
        }
    }

    /** Records how this store's pull ended; null clears an earlier failure. */
    private void failed(VendorStoreBinding binding, Failure failure) {
        binding.setLastFailure(failure == null ? null : failure.name());
        binding.setLastFailureAt(failure == null ? null : clock.instant());
        bindings.save(binding);
    }

    private VendorStoreBinding bind(Store store, UUID orgId, UUID channelId) {
        VendorStoreBinding binding = new VendorStoreBinding();
        binding.setVendorStoreId(store.id());
        binding.setOrgId(orgId);
        binding.setChannelId(channelId);
        binding.setStoreLabel(store.label());
        binding.setReviewCursor(0);
        return bindings.save(binding);
    }

    private StoreResult pullStore(VendorServerClient client, String token, VendorStoreBinding binding, String label,
                                  String trigger) {
        Instant startedAt = clock.instant();
        long cursor = binding.getReviewCursor();
        int received = 0;
        int failed = 0;
        boolean more = true;
        List<UUID> inserted = new ArrayList<>();
        List<UUID> updated = new ArrayList<>();
        ackSaved(client, token, binding);
        for (int page = 0; page < MAX_PAGES && more && failed == 0; page++) {
            FeedPage feed = client.reviewFeed(token, binding.getVendorStoreId(), cursor, PAGE_SIZE);
            long stored = cursor;
            for (FeedEvent event : feed.events()) {
                received++;
                try {
                    apply(binding, event.review(), inserted, updated);
                    stored = event.seq();
                } catch (RuntimeException e) {
                    failed++;
                    log.warn("vendor review relay: event not stored store={} seq={} type={}",
                            binding.getVendorStoreId(), event.seq(), e.getClass().getSimpleName());
                    break;
                }
            }
            more = failed == 0 && feed.more();
            if (stored != cursor) {
                cursor = stored;
                binding.setReviewCursor(cursor);
                binding.setLastPulledAt(clock.instant());
                bindings.save(binding);
                ackSaved(client, token, binding);
            }
        }
        if (received == 0) {
            binding.setLastPulledAt(clock.instant());
            bindings.save(binding);
        }
        if (!inserted.isEmpty() && reviewLinks != null) {
            // A new product's first reviews take their product before the follow-ups read them (2026-10-27). Never
            // fails the pull: the reviews are stored, and the next pass links what this one could not.
            try {
                reviewLinks.reconcile(binding.getOrgId());
            } catch (RuntimeException e) {
                log.warn("vendor review relay: product link skipped store={} cause={}",
                        binding.getVendorStoreId(), e.getClass().getSimpleName());
            }
        }
        followUp.afterReviewIngest(binding.getOrgId(), binding.getChannelId(), inserted);
        if (received > 0) {
            record(binding, trigger, startedAt, received, inserted, updated.size(), failed);
        }
        // Counts and closed words only — the reviews are in hand here, which is why they are not in this line.
        log.info("vendor review relay: store={} received={} inserted={} updated={} failed={} cursor={} more={}",
                binding.getVendorStoreId(), received, inserted.size(), updated.size(), failed, cursor, more);
        // Tasks after reviews: a task change names a review, and the review must already be here.
        int taskChanges = followReplies(binding, token);
        int noReply = replyTasks == null ? 0 : replyTasks.reportNoReply(binding, token);
        if (received > 0 || taskChanges > 0) {
            screensChanged(binding);
        }
        return new StoreResult(binding.getVendorStoreId(), label, true, false, received, inserted.size(),
                updated.size(), failed, cursor, more, taskChanges, noReply, false);
    }

    /** A stopped store: its reply tasks only, no review asked for. */
    private StoreResult followTasks(String token, VendorStoreBinding binding, String label) {
        int taskChanges = followReplies(binding, token);
        int noReply = replyTasks == null ? 0 : replyTasks.reportNoReply(binding, token);
        if (taskChanges > 0) {
            screensChanged(binding);
        }
        return new StoreResult(binding.getVendorStoreId(), label, true, false, 0, 0, 0, 0, binding.getReviewCursor(),
                false, taskChanges, noReply, true);
    }

    /**
     * The store's reply tasks: what this PC's drafts are now written under first (so a change sends waiting drafts
     * back in the same pass), then the server's changes, then the sent-back ones checked again, then phone edits checked
     * under the basis they were written in, then the approved ones drafted under other fingerprints (2026-10-23), then
     * the drafts themselves brought together with the phone's (2026-10-25).
     * Returns the changes applied.
     */
    private int followReplies(VendorStoreBinding binding, String token) {
        if (replyTasks == null) {
            return 0;
        }
        replyTasks.reportBasis(binding, token);
        int applied = replyTasks.sync(binding, token);
        replyTasks.recheckSentBack(binding, token);
        replyTasks.checkPhoneEdits(binding, token);
        replyTasks.recheckApproved(binding, token);
        // Last: this PC's drafts up and the phone's edits in, against the task copies just brought up to date.
        replyTasks.mirror(binding, token);
        replyTasks.refreshPosted(binding, token);
        return applied;
    }

    /** Tells the server how far this PC has stored, when it has not yet confirmed that far. Best effort. */
    private void ackSaved(VendorServerClient client, String token, VendorStoreBinding binding) {
        long cursor = binding.getReviewCursor();
        if (cursor <= binding.getAckedCursor()) {
            return;
        }
        try {
            client.ackReviews(token, binding.getVendorStoreId(), cursor);
        } catch (RuntimeException e) {
            // Owed still: the next pull — new events or none — asks again from the saved cursor.
            return;
        }
        binding.setAckedCursor(cursor);
        bindings.save(binding);
    }

    /** Brings one review to the state the event carries. Throws when it cannot be stored. */
    private void apply(VendorStoreBinding binding, FeedReview r, List<UUID> inserted, List<UUID> updated) {
        Optional<Review> held = reviews.findByOrgIdAndChannelIdAndExternalId(binding.getOrgId(),
                binding.getChannelId(), r.reviewNo());
        Optional<VendorReviewRef> known = refs.findByVendorReviewId(r.id());
        if (known.isPresent() && !held.map(h -> h.getId().equals(known.get().getReviewId())).orElse(false)) {
            // The server's review arriving under another 리뷰글번호 than the one it is paired with here: never two.
            throw new IllegalStateException("vendor review bound to another local review");
        }
        UUID reviewId;
        if (held.isEmpty()) {
            IngestOutcome outcome = ingestion.ingestReviews(binding.getOrgId(), binding.getChannelId(),
                    List.of(canonical(r)));
            if (outcome.insertedIds().size() != 1) {
                throw new IllegalStateException("review not stored");
            }
            reviewId = outcome.insertedIds().get(0);
            inserted.add(reviewId);
        } else {
            Review review = held.get();
            if (refresh(review, r)) {
                reviews.save(review);
                updated.add(review.getId());
            }
            reviewId = review.getId();
        }
        VendorReviewRef ref = known.orElseGet(() -> {
            VendorReviewRef fresh = new VendorReviewRef();
            fresh.setVendorReviewId(r.id());
            fresh.setVendorStoreId(binding.getVendorStoreId());
            fresh.setReviewId(reviewId);
            return fresh;
        });
        ref.setBodyFingerprint(r.bodyFingerprint());
        ref.setDisplayState(r.displayState());
        ref.setAttachmentUrl(r.attachmentUrl());
        ref.setAttachmentKind(r.attachmentKind());
        refs.save(ref);
    }

    /** The server's current text, rating and reply flag onto a held review; true when anything changed. */
    static boolean refresh(Review review, FeedReview r) {
        boolean changed = false;
        if (!Objects.equals(review.getBody(), r.body())) {
            review.setBody(r.body());
            changed = true;
        }
        if (!Objects.equals(review.getRating(), r.rating())) {
            review.setRating(r.rating());
            review.setNegative(r.rating() <= 2);
            changed = true;
        }
        ReviewReplyState stated = r.replied() ? ReviewReplyState.ANSWERED : ReviewReplyState.PENDING;
        if (ReviewReplyState.isProgress(review.getReplyState(), stated)) {
            review.setReplyState(stated);
            changed = true;
        }
        if (r.replied() && r.repliedAt() != null && review.getRepliedAt() == null
                && review.getReplyState() == ReviewReplyState.ANSWERED) {
            review.setRepliedAt(r.repliedAt());
            changed = true;
        }
        return changed;
    }

    private static CanonicalReview canonical(FeedReview r) {
        return new CanonicalReview(
                r.productName(),
                null,
                r.rating(),
                r.body(),
                r.writtenAt(),
                r.reviewNo(),
                1,
                r.replied() ? ReviewReplyState.ANSWERED : ReviewReplyState.PENDING,
                r.replied() ? r.repliedAt() : null,
                null,
                0,
                // The export carries one attachment address, not a count: nobody counted.
                false,
                r.body().isBlank(),
                // Attribute by the channel's own 상품번호, find-only, as every NAVER source does.
                ChannelProductRef.of(r.productNo()));
    }

    /** The run's own import record. No data type: a relayed file's age is the delegate's, not freshness. */
    private void record(VendorStoreBinding binding, String trigger, Instant startedAt, int received,
                        List<UUID> inserted, int updated, int failed) {
        try {
            SyncJob job = new SyncJob();
            job.setOrgId(binding.getOrgId());
            job.setChannelId(binding.getChannelId());
            job.setUploadType("REVIEW");
            job.setJobType("VENDOR_RELAY_PULL");
            job.setMethod(CollectionMethod.VENDOR_RELAY.name());
            job.setTrigger(trigger);
            job.setStartedAt(startedAt);
            job.setFinishedAt(clock.instant());
            job.setTotalRows(received);
            job.setSuccessRows(inserted.size() + updated);
            job.setSkippedRows(Math.max(0, received - inserted.size() - updated - failed));
            job.setFailedRows(failed);
            job.setStatus(failed == 0 ? "SUCCESS" : "PARTIAL");
            SyncJob saved = syncJobs.save(job);
            ingestion.stampAcquisition(binding.getOrgId(), inserted, saved.getId());
        } catch (RuntimeException e) {
            log.warn("vendor review relay stored, import history not recorded: type={}",
                    e.getClass().getSimpleName());
        }
    }
}
