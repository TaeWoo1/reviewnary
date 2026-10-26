package com.sellerops.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.channel.Channel;
import com.sellerops.channel.ChannelRepository;
import com.sellerops.channel.ChannelStatus;
import com.sellerops.review.Review;
import com.sellerops.review.ReviewReplyState;
import com.sellerops.review.ReviewRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Stored reviews find their product by the channel product number they carry (2026-10-27).
 *
 * <p>On the seller PC every relayed NAVER review was stored with its 채널상품번호 and no product, and nothing ever joined
 * the two — so a product's knowledge could not reach a reply to its own reviews.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class ReviewProductLinkIT {

    @Autowired ReviewRepository reviews;
    @Autowired ProductRepository products;
    @Autowired ChannelProductRepository channelProducts;
    @Autowired ChannelRepository channels;

    private final UUID org = UUID.randomUUID();
    private UUID channelId;

    @BeforeEach
    void setUp() {
        Channel ch = new Channel();
        ch.setCode("NAVER");
        ch.setNameKo("네이버");
        ch.setStatus(ChannelStatus.AVAILABLE);
        ch.setSupportsReview(true);
        ch.setSortOrder(0);
        channels.save(ch);
        channelId = ch.getId();
    }

    private ReviewProductLink link(boolean derive) {
        return new ReviewProductLink(reviews, channelProducts, new ProductService(products), derive);
    }

    private Review review(String ref, String name) {
        Review r = new Review();
        r.setOrgId(org);
        r.setChannelId(channelId);
        r.setBody("접착력이 약해요");
        r.setRating(2);
        r.setNegative(true);
        r.setReceivedAt(Instant.now());
        r.setContentHash(UUID.randomUUID().toString());
        r.setDedupKeyVersion(2);
        r.setReplyState(ReviewReplyState.PENDING);
        r.setMediaCount(0);
        r.setCreatedAt(Instant.now());
        r.setSourceProductRef(ref);
        r.setSourceProductName(name);
        return reviews.save(r);
    }

    private UUID productOf(Review r) {
        return reviews.findById(r.getId()).orElseThrow().getProductId();
    }

    @Test
    void aReviewTakesTheProductItsListingNamesAndNothingIsMadeUpWhileDerivationIsOff() {
        Review listed = review("6473457702", "선바로 일체형 전선몰딩");
        Review unlisted = review("557622761", "종이컵보관함");
        Product product = products.save(product("선바로 일체형 전선몰딩", "SB-1"));
        channelProducts.save(listing(product.getId(), "6473457702", "CHANNEL_API"));

        ReviewProductLink.Result result = link(false).reconcile(org);

        assertThat(result.linked()).isEqualTo(1);
        assertThat(result.derived()).isZero();
        assertThat(result.unlinked()).as("a number with no listing stays unlinked — the honest answer").isEqualTo(1);
        assertThat(productOf(listed)).isEqualTo(product.getId());
        assertThat(productOf(unlisted)).isNull();
        assertThat(products.findAllByOrgId(org)).hasSize(1);
    }

    @Test
    void withDerivationOnANumberBecomesAProductNamedAsItsReviewsMostOftenNameIt() {
        Review a = review("557622761", "종이컵보관함 수거함 디스펜서");
        Review b = review("557622761", "종이컵보관함 수거함 디스펜서");
        Review c = review("557622761", "종이컵보관함 (옛 이름)");

        ReviewProductLink.Result result = link(true).reconcile(org);

        assertThat(result.derived()).isEqualTo(1);
        assertThat(result.linked()).isEqualTo(3);
        assertThat(result.unlinked()).isZero();
        Product made = products.findById(productOf(a)).orElseThrow();
        assertThat(made.getName()).isEqualTo("종이컵보관함 수거함 디스펜서");
        // Named, so retrieval treats it as a real product: the name is not the number.
        assertThat(OperatorProductName.displayNameOrNull(made)).isEqualTo("종이컵보관함 수거함 디스펜서");
        assertThat(productOf(b)).isEqualTo(made.getId());
        assertThat(productOf(c)).isEqualTo(made.getId());
        ChannelProduct derived = channelProducts.findByChannelIdAndExternalProductId(channelId, "557622761").orElseThrow();
        assertThat(derived.getSourceKind()).isEqualTo(ReviewProductLink.SOURCE);
        assertThat(derived.getChannelPrice()).as("no review states a price").isNull();

        // Idempotent: a second pass makes nothing and moves nothing.
        ReviewProductLink.Result again = link(true).reconcile(org);
        assertThat(again.derived()).isZero();
        assertThat(again.linked()).isZero();
        assertThat(products.findAllByOrgId(org)).hasSize(1);
    }

    @Test
    void aLinkIsNeverMovedAndAnotherOrgsListingNeverAnswers() {
        Product mine = products.save(product("내 상품", "MINE"));
        Review linked = review("6473457702", "선바로");
        linked.setProductId(mine.getId());
        reviews.save(linked);
        Product theirs = new Product();
        theirs.setOrgId(UUID.randomUUID());
        theirs.setName("남의 상품");
        theirs.setStatus("ACTIVE");
        theirs = products.save(theirs);
        ChannelProduct foreign = listing(theirs.getId(), "6473457702", "CHANNEL_API");
        foreign.setOrgId(theirs.getOrgId());
        channelProducts.save(foreign);
        Review other = review("6473457702", "선바로");

        ReviewProductLink.Result result = link(false).reconcile(org);

        assertThat(result.linked()).isZero();
        assertThat(productOf(linked)).isEqualTo(mine.getId());
        assertThat(productOf(other)).isNull();
    }

    private Product product(String name, String sku) {
        Product p = new Product();
        p.setOrgId(org);
        p.setName(name);
        p.setSku(sku);
        p.setStatus("ACTIVE");
        return p;
    }

    private ChannelProduct listing(UUID productId, String externalId, String sourceKind) {
        ChannelProduct listing = new ChannelProduct();
        listing.setOrgId(org);
        listing.setProductId(productId);
        listing.setChannelId(channelId);
        listing.setExternalProductId(externalId);
        listing.setSourceKind(sourceKind);
        return listing;
    }
}
