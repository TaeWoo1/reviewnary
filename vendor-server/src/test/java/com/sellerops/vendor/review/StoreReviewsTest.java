package com.sellerops.vendor.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.vendor.Codes;
import com.sellerops.vendor.MutableClock;
import com.sellerops.vendor.TestTotp;
import com.sellerops.vendor.account.SellerAccounts;
import com.sellerops.vendor.delegate.Delegates;
import com.sellerops.vendor.review.StoreReviews.Event;
import com.sellerops.vendor.review.StoreReviews.EventKind;
import com.sellerops.vendor.review.StoreReviews.Imported;
import com.sellerops.vendor.review.StoreReviews.Page;
import com.sellerops.vendor.store.Stores;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/**
 * <b>An upload says what is new, changed or now answered; a PC pulls exactly that, in order</b> — contract §3.
 */
@SpringBootTest
@ActiveProfiles("test")
class StoreReviewsTest {

    private static final String PASSWORD = "a long enough password";

    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private SellerAccounts accounts;
    @Autowired
    private Delegates delegates;

    private MutableClock clock;
    private StoreReviews reviews;
    private UUID store;
    private UUID delegate;
    private UUID install;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-10-20T03:00:00Z"));
        reviews = new StoreReviews(jdbc, clock);
        UUID account = accounts.redeem(accounts.issueInvite("s-" + UUID.randomUUID() + "@example.com"), PASSWORD);
        store = new Stores(jdbc, clock).add(account, Stores.Channel.NAVER, "데모 스토어");
        String invite = delegates.issueInvite("d-" + UUID.randomUUID() + "@example.com", Delegates.Role.DELEGATE);
        Delegates.Staged staged = delegates.stage(invite, PASSWORD);
        delegate = delegates.confirm(invite, TestTotp.code(staged.secret(), Instant.now(), 0)).delegateId();
        install = UUID.randomUUID();
        jdbc.sql("insert into installs (id, account_id, token_hash, public_key, device_name, created_at, expires_at) "
                        + "values (?, ?, ?, ?, ?, ?, ?)")
                .params(install, account, Codes.hash(install.toString()), new byte[32], "Seller Mac",
                        Timestamp.from(clock.instant()), Timestamp.from(clock.instant().plus(Duration.ofDays(180))))
                .update();
    }

    @Test
    void theFirstUploadIsAllNewAndTheSameUploadAgainIsNothing() throws Exception {
        Imported first = upload(NaverReviewExport.read(ExportWorkbooks.fixture()));
        assertThat(first).extracting(Imported::rows, Imported::added, Imported::changed, Imported::replied,
                Imported::unchanged).containsExactly(6, 6, 0, 0, 0);
        assertThat(first.attachmentsDropped()).as("the fixture's synthetic host is not NAVER's").isEqualTo(1);
        Page page = reviews.feed(store, 0, 100);
        assertThat(page.events()).extracting(Event::kind).containsOnly(EventKind.NEW).hasSize(6);
        assertThat(page.more()).isFalse();
        assertThat(page.events().get(0).review().bodyFingerprint())
                .isEqualTo(ReviewBodyFingerprint.of(page.events().get(0).review().body()));
        assertThat(page.events()).allSatisfy(e -> assertThat(e.review().attachment()).isNull());

        Imported again = upload(NaverReviewExport.read(ExportWorkbooks.fixture()));
        assertThat(again).extracting(Imported::added, Imported::changed, Imported::replied, Imported::unchanged)
                .containsExactly(0, 0, 0, 6);
        assertThat(reviews.feed(store, page.cursor(), 100).events()).isEmpty();
    }

    @Test
    void anEditedReviewIsAChangeAndAFreshReplyIsSaidSeparately() throws Exception {
        upload(lines(ExportWorkbooks.line("1", Map.of()), ExportWorkbooks.line("2", Map.of()),
                ExportWorkbooks.line("3", Map.of())));
        long cursor = reviews.feed(store, 0, 100).cursor();
        Imported next = upload(lines(
                ExportWorkbooks.line("1", Map.of("리뷰상세내용", "고쳐 쓴 합성 리뷰", "최종수정일", "2026.10.02. 09:00:00")),
                ExportWorkbooks.line("2", Map.of("답글여부", "Y", "답글등록일시", "2026.10.03. 11:00:00")),
                ExportWorkbooks.line("4", Map.of())));
        assertThat(next).extracting(Imported::added, Imported::changed, Imported::replied, Imported::unchanged)
                .containsExactly(1, 1, 1, 0);
        List<Event> events = reviews.feed(store, cursor, 100).events();
        assertThat(events).extracting(e -> e.kind() + ":" + e.review().reviewNo())
                .containsExactly("CHANGED:1", "REPLIED:2", "NEW:4");
        assertThat(events.get(0).review().body()).isEqualTo("고쳐 쓴 합성 리뷰");
        assertThat(events.get(1).review().replied()).isTrue();
        assertThat(reviews.idOf(store, "3")).as("absent from a later upload is not deleted").isPresent();
    }

    @Test
    void theOneKeptAttachmentTravelsWithItsKind() throws Exception {
        upload(lines(ExportWorkbooks.line("1", Map.of("포토/영상",
                "https://phinf.pstatic.net/image.nmv/batch/still.jpg"))));
        ReviewAttachment attachment = reviews.feed(store, 0, 10).events().get(0).review().attachment();
        assertThat(attachment.kind()).isEqualTo(ReviewAttachment.Kind.VIDEO_STILL);
        assertThat(attachment.url()).isEqualTo("https://phinf.pstatic.net/image.nmv/batch/still.jpg");
    }

    @Test
    void theFeedPagesInOrderFromACursor() throws Exception {
        upload(lines(ExportWorkbooks.line("1", Map.of()), ExportWorkbooks.line("2", Map.of()),
                ExportWorkbooks.line("3", Map.of())));
        Page one = reviews.feed(store, 0, 2);
        assertThat(one.events()).hasSize(2);
        assertThat(one.more()).isTrue();
        Page two = reviews.feed(store, one.cursor(), 2);
        assertThat(two.events()).hasSize(1);
        assertThat(two.more()).isFalse();
        assertThat(two.events().get(0).seq()).isGreaterThan(one.cursor());
        assertThat(reviews.feed(store, two.cursor(), 2)).satisfies(p -> {
            assertThat(p.events()).isEmpty();
            assertThat(p.cursor()).isEqualTo(two.cursor());
        });
    }

    @Test
    void retentionStartsOnlyWhenBothSavedAndJudgedAndThenNeverMoves() throws Exception {
        upload(lines(ExportWorkbooks.line("1", Map.of()), ExportWorkbooks.line("2", Map.of())));
        Page page = reviews.feed(store, 0, 100);
        UUID first = reviews.idOf(store, "1").orElseThrow();
        assertThat(reviews.markNoReplyNeeded(store, first)).isTrue();
        assertThat(purgeAfter(first)).as("judged but not yet saved by a PC").isNull();

        clock.advance(Duration.ofHours(2));
        assertThat(reviews.ack(store, install, page.cursor())).isTrue();
        Instant start = clock.instant();
        assertThat(purgeAfter(first)).isEqualTo(start.plus(StoreReviews.NO_REPLY_RETENTION));
        assertThat(purgeAfter(reviews.idOf(store, "2").orElseThrow())).as("saved but not judged").isNull();

        clock.advance(Duration.ofDays(3));
        upload(lines(ExportWorkbooks.line("1", Map.of()), ExportWorkbooks.line("2", Map.of())));
        assertThat(reviews.markNoReplyNeeded(store, first)).isTrue();
        assertThat(purgeAfter(first)).as("a re-upload or a repeat judgement does not move it").isEqualTo(
                start.plus(StoreReviews.NO_REPLY_RETENTION));
        assertThat(reviews.markNoReplyNeeded(store, UUID.randomUUID())).isFalse();
    }

    @Test
    void anAckNamesARealEventAndNeverMovesBack() throws Exception {
        upload(lines(ExportWorkbooks.line("1", Map.of()), ExportWorkbooks.line("2", Map.of())));
        Page page = reviews.feed(store, 0, 100);
        assertThat(reviews.ack(store, install, page.cursor() + 1000)).isFalse();
        assertThat(reviews.ack(store, install, page.cursor())).isTrue();
        assertThat(reviews.ack(store, install, page.events().get(0).seq())).isTrue();
        assertThat(jdbc.sql("select acked_seq from store_review_acks where store_id = ? and install_id = ?")
                .params(store, install).query(Long.class).single()).isEqualTo(page.cursor());
    }

    @Test
    void noForbiddenColumnIsStoredAnywhere() throws Exception {
        upload(NaverReviewExport.read(ExportWorkbooks.fixture()));
        StringBuilder stored = new StringBuilder();
        for (String table : List.of("store_reviews", "review_uploads", "store_review_events")) {
            jdbc.sql("select * from " + table).query(rs -> {
                for (int c = 1; c <= rs.getMetaData().getColumnCount(); c++) {
                    stored.append(rs.getString(c)).append('|');
                }
            });
        }
        assertThat(stored).as("the scan read the stored reviews").contains("1000000001");
        for (String sentinel : List.of("MUST-NOT-PERSIST", "example.invalid")) {
            assertThat(stored.toString()).doesNotContain(sentinel);
        }
    }

    private Imported upload(List<NaverReviewExport.Line> lines) {
        return reviews.importExport(store, delegate, lines);
    }

    @SafeVarargs
    private static List<NaverReviewExport.Line> lines(Map<String, String>... cells) throws Exception {
        return NaverReviewExport.read(ExportWorkbooks.workbook(List.of(cells)));
    }

    private Instant purgeAfter(UUID review) {
        Timestamp t = jdbc.sql("select purge_after from store_reviews where id = ?").param(review)
                .query((rs, n) -> rs.getTimestamp(1)).list().get(0);
        return t == null ? null : t.toInstant();
    }
}
