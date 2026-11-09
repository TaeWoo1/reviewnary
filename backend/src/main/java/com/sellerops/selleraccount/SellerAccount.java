package com.sellerops.selleraccount;

import com.sellerops.channel.ChannelStatus;
import com.sellerops.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * An org's connection to a specific channel (or a file-upload channel).
 *
 * <p><b>At most one API-mode account per (org, channel).</b> An org's official-API connection to a
 * channel is singular, so a duplicate is prevented by a <b>partial</b> unique index —
 * {@code uq_seller_accounts_api_org_channel} on {@code (org_id, channel_id) WHERE is_file_upload =
 * false} (Flyway migration V36). File-upload accounts are deliberately NOT covered: ESM file-import
 * legitimately holds several file-upload rows on one channel, one per marketplace seller identity
 * ({@code EsmFileImportAccountService}). The index is filtered, which JPA's {@code @UniqueConstraint}
 * cannot express, so it lives in the migration only (like the existing V2 partial unique indexes) and
 * is the DB backstop behind the PESSIMISTIC_WRITE channel-row lock in {@code SellerAccountService}.
 */
@Getter
@Setter
@Entity
@Table(name = "seller_accounts")
public class SellerAccount extends BaseEntity {

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "channel_id", nullable = false)
    private UUID channelId;

    private String alias;

    @Enumerated(EnumType.STRING)
    @Column(name = "connection_status", nullable = false)
    private ChannelStatus connectionStatus;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    @Column(name = "is_file_upload", nullable = false)
    private boolean fileUpload;

    /**
     * Which store this account IS, in the channel's own terms (Coupang 업체코드) — never permission to
     * call that channel's API.
     *
     * <p>Null means we cannot say which store this is, which is a refusal downstream and not a pass:
     * an acquisition run with no expectation stops without reading a row. For accounts connected before
     * this column existed the acquisition read falls back to the {@code vendor_id} sealed beside their
     * API credential, so nothing had to be backfilled.
     *
     * <p><b>Not a secret</b> (the credential template has always declared it so), which is why it lives
     * here in plain text rather than being pushed into the vault to borrow its storage.
     */
    @Column(name = "store_identity", length = 64)
    private String storeIdentity;

    /** This account's route when it is one attached to the store rather than the store's own connection (V163). */
    public static final String ROUTE_DELEGATE_UPLOAD = "DELEGATE_UPLOAD";

    /**
     * Null for the store's own connection (official API, the seller's own file upload). {@link #ROUTE_DELEGATE_UPLOAD}
     * for the account 「리뷰 받기 시작」 makes so delegate-uploaded NAVER reviews have one to be answered through —
     * a route attached to the store, which yields to the store's own connection when there is one ({@link #answering}).
     */
    @Column(name = "review_route", length = 24)
    private String reviewRoute;

    /**
     * <b>One store, several routes; one account answers</b> (boundary §5, 2026-10-31). Of an organisation's accounts
     * on one channel, the store's own connection answers; a route attached to it (the delegate's uploads) answers only
     * when it is all there is. Still null for none, and for two own connections — a reply from either is a question
     * nobody asked. The route a review came in by stays the review's (its provenance), not the account's.
     */
    public static SellerAccount answering(java.util.List<SellerAccount> onChannel) {
        java.util.List<SellerAccount> own = onChannel.stream().filter(a -> a.getReviewRoute() == null).toList();
        java.util.List<SellerAccount> pick = own.isEmpty() ? onChannel : own;
        return pick.size() == 1 ? pick.get(0) : null;
    }
}
