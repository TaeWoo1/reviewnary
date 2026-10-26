package com.sellerops.product;

import com.sellerops.review.ReviewRepository;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>A stored review finds its product by the number the channel gave it</b> (owner decision 2026-10-27).
 *
 * <p>A NAVER review arrives — from the delegate's export, through the vendor relay — carrying its 채널상품번호 in
 * {@code source_product_ref} and no product: ingest links a declaring row only to a listing that already exists, and on
 * the seller PC none did. Every one of the demo store's 3,555 reviews was unlinked, so nothing the owner writes about a
 * product could reach a reply to its reviews, and a draft that had no evidence could not even ask for it — the product
 * ask is filed only for a review with a product. V105 added the index for this join; nothing ran it until now.
 *
 * <p><b>Two steps, and only the first is the product's.</b>
 * <ol>
 *   <li><b>Link by listing.</b> A review whose {@code (channel, source_product_ref)} names a listing in
 *       {@code channel_products} takes that listing's product. The listing comes from the official catalogue read
 *       ({@link ProductKnowledgeWriter}), which runs this step itself once it has written.</li>
 *   <li><b>Derive from the reviews</b> — off by default, on for the local demo until the PC reads the NAVER catalogue
 *       itself. A ref with no listing becomes a product named as its reviews most often name it, with a listing
 *       stamped {@link #SOURCE} and nothing else: no price, no url, no status, because no review states them. The
 *       catalogue read later finds that listing by the same {@code (channel, external id)} and fills it in, keeping
 *       the product, so the two paths converge rather than minting a second product.</li>
 * </ol>
 *
 * <p>Idempotent: a linked review is not in either work list, and a ref that has a listing is not derived twice.
 * Reaches no marketplace and calls no model.
 */
@Service
public class ReviewProductLink {

    private static final Logger log = LoggerFactory.getLogger(ReviewProductLink.class);

    /** The provenance stamp of a listing this class derived from review rows. */
    public static final String SOURCE = "DERIVED:REVIEW_REF";

    private final ReviewRepository reviews;
    private final ChannelProductRepository channelProducts;
    private final ProductService productService;
    private final boolean deriveFromReviews;

    public ReviewProductLink(ReviewRepository reviews, ChannelProductRepository channelProducts,
                             ProductService productService,
                             @Value("${sellerops.review-product.derive-from-review-refs:false}")
                             boolean deriveFromReviews) {
        this.reviews = reviews;
        this.channelProducts = channelProducts;
        this.productService = productService;
        this.deriveFromReviews = deriveFromReviews;
    }

    /** This bean through its proxy, so the start pass runs each org in its own transaction. */
    private ReviewProductLink self;

    @org.springframework.beans.factory.annotation.Autowired
    public void setSelf(@org.springframework.context.annotation.Lazy ReviewProductLink self) {
        this.self = self;
    }

    /** What one pass did, and what is still unlinked — a ref with no listing while derivation is off. */
    public record Result(int linked, int derived, long unlinked) {
    }

    /** Both steps for one org: derive where allowed, then link. */
    @Transactional
    public Result reconcile(UUID orgId) {
        int derived = deriveFromReviews ? derive(orgId) : 0;
        int linked = reviews.linkToListedProducts(orgId);
        long unlinked = reviews.countUnlinkedWithProductRef(orgId);
        if (linked > 0 || derived > 0) {
            log.info("review_product_link orgId={} linked={} derived={} unlinked={}", orgId, linked, derived, unlinked);
        }
        return new Result(linked, derived, unlinked);
    }

    /**
     * Where derivation is on, every org's stored reviews are joined once at start — the demo's databases hold
     * reviews relayed before this existed, and nothing else would revisit them. Never fails the start.
     */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void reconcileStoredOnStart() {
        if (!deriveFromReviews) {
            return;
        }
        ReviewProductLink tx = self == null ? this : self;
        for (String orgId : reviews.orgIdsWithUnlinkedProductRef()) {
            try {
                tx.reconcile(UUID.fromString(orgId));
            } catch (RuntimeException e) {
                log.warn("review_product_link start pass skipped orgId={} cause={}", orgId, e.getClass().getSimpleName());
            }
        }
    }

    /** The first step alone — what the catalogue read runs after it writes listings. */
    @Transactional
    public int linkListed(UUID orgId) {
        return reviews.linkToListedProducts(orgId);
    }

    private int derive(UUID orgId) {
        int derived = 0;
        Instant now = Instant.now();
        for (Object[] row : reviews.unlistedProductRefs(orgId)) {
            UUID channelId = UUID.fromString(String.valueOf(row[0]));
            String ref = String.valueOf(row[1]).strip();
            String name = row[2] == null ? null : String.valueOf(row[2]).strip();
            // The ref is the SKU, as the catalogue read falls back to it when a listing has no 판매자관리코드: one
            // product per listing, whichever path saw it first.
            Product product = productService.resolveOrCreateWithinTransaction(orgId, name, ref);
            ChannelProduct listing = new ChannelProduct();
            listing.setOrgId(orgId);
            listing.setProductId(product.getId());
            listing.setChannelId(channelId);
            listing.setExternalProductId(ref);
            if (name != null && !name.isBlank()) {
                listing.setChannelProductName(name.length() <= 500 ? name : name.substring(0, 500));
            }
            listing.setSourceKind(SOURCE);
            listing.setObservedAt(now);
            listing.setFirstSeenAt(now);
            listing.setLastSeenAt(now);
            channelProducts.save(listing);
            derived++;
        }
        return derived;
    }
}
