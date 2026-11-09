package com.sellerops.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sellerops.inquiry.publish.FakeChannelReplyAdapter;
import com.sellerops.inquiry.publish.ReplyPublishCommand;
import com.sellerops.ingest.canonical.CanonicalInquiry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The fake channel's board and collector (2026-11-06): what the channel would show, through the ordinary path. */
class FakeChannelInquiryConnectorTest {

    @TempDir
    Path dir;

    private Path board(String json) throws Exception {
        Path f = dir.resolve("board.json");
        Files.writeString(f, json);
        return f;
    }

    private static FetchRequest page(String cursor, int limit) {
        return new FetchRequest(UUID.randomUUID(), UUID.randomUUID(), "CAFE24", DataType.INQUIRY, cursor, limit);
    }

    @Test
    @DisplayName("collects what the file shows, page by page; an answer the seller wrote on the seller centre is read "
            + "on the next collection")
    void readsTheFile() throws Exception {
        Path f = board("[{\"externalId\":\"cafe24:b99:a1\",\"title\":\"교환\",\"body\":\"교환 되나요?\","
                + "\"receivedAt\":\"2026-11-06T01:00:00Z\",\"status\":\"UNANSWERED\"},"
                + "{\"externalId\":\"cafe24:b99:a2\",\"body\":\"환불해 주세요\",\"status\":\"UNANSWERED\"}]");
        FakeChannelInquiryConnector c = new FakeChannelInquiryConnector("CAFE24", new FakeChannelBoard(f));
        FetchPage first = c.fetch(page(null, 1));
        assertThat(first.records()).hasSize(1);
        assertThat(first.hasMore()).isTrue();
        CanonicalInquiry one = (CanonicalInquiry) first.records().get(0);
        assertThat(one.externalId()).isEqualTo("cafe24:b99:a1");
        assertThat(one.status()).isEqualTo("UNANSWERED");
        assertThat(one.answerBody()).isNull();
        FetchPage second = c.fetch(page(first.nextCursorValue(), 1));
        assertThat(second.records()).hasSize(1);
        assertThat(second.hasMore()).isFalse();

        Files.writeString(f, Files.readString(f).replaceFirst("\"status\":\"UNANSWERED\"",
                "\"status\":\"ANSWERED\",\"answerBody\":\"판매자센터에서 직접 답함\""));
        CanonicalInquiry again = (CanonicalInquiry) c.fetch(page(null, 10)).records().get(0);
        assertThat(again.status()).isEqualTo("ANSWERED");
        assertThat(again.answerBody()).isEqualTo("판매자센터에서 직접 답함");
        assertThat(again.answeredAt()).as("the file gave no time, and the board makes none up").isNull();
    }

    @Test
    @DisplayName("the newest change wins: registered, then corrected by the seller in the file, then by an explicit "
            + "seller-centre edit; an unchanged file row does not undo a registration (2026-11-06 eighteenth review)")
    void theNewestChangeWins() throws Exception {
        String unanswered = "[{\"externalId\":\"cafe24:b99:a4\",\"body\":\"언제 와요?\",\"status\":\"UNANSWERED\"}]";
        Path f = board(unanswered);
        FakeChannelBoard b = new FakeChannelBoard(f);
        FakeChannelInquiryConnector c = new FakeChannelInquiryConnector("CAFE24", b);
        java.util.function.Supplier<CanonicalInquiry> shown = () -> (CanonicalInquiry) c.fetch(page(null, 10))
                .records().get(0);
        assertThat(shown.get().status()).isEqualTo("UNANSWERED");

        Instant registered = Instant.parse("2026-11-06T02:00:00Z");
        b.answered("cafe24:b99:a4", "오늘 출고 예정입니다.", registered);
        CanonicalInquiry afterPost = shown.get();
        assertThat(afterPost.answerBody()).as("the file still says unanswered, but it said so before the registration")
                .isEqualTo("오늘 출고 예정입니다.");
        assertThat(afterPost.answeredAt()).isEqualTo(registered);

        // The seller corrects it on the seller centre — the developer writes that into the file.
        Files.writeString(f, "[{\"externalId\":\"cafe24:b99:a4\",\"body\":\"언제 와요?\",\"status\":\"ANSWERED\","
                + "\"answerBody\":\"내일 출고됩니다.\",\"answeredAt\":\"2026-11-06T03:00:00Z\"}]");
        CanonicalInquiry corrected = shown.get();
        assertThat(corrected.answerBody()).isEqualTo("내일 출고됩니다.");
        assertThat(corrected.answeredAt()).isEqualTo(Instant.parse("2026-11-06T03:00:00Z"));
        assertThat(shown.get().answerBody()).as("read again, unchanged").isEqualTo("내일 출고됩니다.");

        // The same correction as an explicit seller-centre edit, on a channel that shows no time.
        b.edited("cafe24:b99:a4", "모레 출고됩니다.", null);
        CanonicalInquiry edited = shown.get();
        assertThat(edited.answerBody()).isEqualTo("모레 출고됩니다.");
        assertThat(edited.answeredAt()).as("no time shown is no time, not now").isNull();

        // And a registration after that is the newest again.
        b.answered("cafe24:b99:a4", "다시 안내드립니다.", registered.plusSeconds(3600));
        assertThat(shown.get().answerBody()).isEqualTo("다시 안내드립니다.");
    }

    @Test
    @DisplayName("an answer registered before the board ever read its row is kept when the row is first read")
    void aRegistrationBeforeTheFirstReadIsKept() throws Exception {
        FakeChannelBoard b = new FakeChannelBoard(board("[{\"externalId\":\"cafe24:b99:a5\",\"body\":\"?\","
                + "\"status\":\"UNANSWERED\"}]"));
        b.answered("cafe24:b99:a5", "답변", null);
        CanonicalInquiry row = (CanonicalInquiry) new FakeChannelInquiryConnector("CAFE24", b)
                .fetch(page(null, 10)).records().get(0);
        assertThat(row.status()).isEqualTo("ANSWERED");
        assertThat(row.answeredAt()).isNull();
    }

    @Test
    @DisplayName("an answer registered through the fake reply adapter is on the board the collector reads")
    void aRegisteredAnswerIsCollected() throws Exception {
        FakeChannelBoard b = new FakeChannelBoard(board("[{\"externalId\":\"cafe24:b99:a3\",\"body\":\"언제 와요?\","
                + "\"status\":\"UNANSWERED\"}]"));
        new FakeChannelReplyAdapter("CAFE24", b).publish(new ReplyPublishCommand(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "cafe24:b99:a3", Instant.now(), "답변", "오늘 출고 예정입니다.", "언제 와요?"));
        CanonicalInquiry row = (CanonicalInquiry) new FakeChannelInquiryConnector("CAFE24", b)
                .fetch(page(null, 10)).records().get(0);
        assertThat(row.status()).isEqualTo("ANSWERED");
        assertThat(row.answerBody()).isEqualTo("오늘 출고 예정입니다.");
    }

    @Test
    @DisplayName("inquiries only; no file is an empty board; the registry picks it for its channel and nothing else")
    void scope() {
        FakeChannelInquiryConnector c = new FakeChannelInquiryConnector("CAFE24", new FakeChannelBoard(null));
        assertThat(c.fetch(page(null, 10)).records()).isEmpty();
        assertThatThrownBy(() -> c.fetch(new FetchRequest(null, null, "CAFE24", DataType.REVIEW, null, 10)))
                .isInstanceOf(UnsupportedDataTypeException.class);
        assertThat(c.capabilities("CAFE24").supportedDataTypes()).containsExactly(DataType.INQUIRY);
        ConnectorRegistry registry = new ConnectorRegistry(List.of(c), false);
        assertThat(registry.resolvePullConnector("CAFE24")).containsSame(c);
        assertThat(registry.resolvePullConnector("NAVER")).isEmpty();
    }

    @Test
    @DisplayName("a row may report the Cafe24 comment look for this collection; without the field the collection did not "
            + "look, and a proven shop comment answers the row with no text and no invented time (2026-11-08)")
    void reportsTheCommentLook() throws Exception {
        Path f = board("[{\"externalId\":\"cafe24:b99:c1\",\"body\":\"언제 와요?\",\"status\":\"UNANSWERED\","
                + "\"commentCheck\":\"NOT_CHECKED\"},"
                + "{\"externalId\":\"cafe24:b99:c2\",\"body\":\"교환 되나요?\",\"status\":\"UNANSWERED\"},"
                + "{\"externalId\":\"cafe24:b99:c3\",\"body\":\"환불 되나요?\",\"status\":\"UNANSWERED\","
                + "\"commentCheck\":\"SHOP_ANSWERED\"},"
                + "{\"externalId\":\"cafe24:b99:c4\",\"body\":\"재입고 되나요?\",\"status\":\"UNANSWERED\","
                + "\"commentCheck\":\"MAYBE\"}]");
        FakeChannelInquiryConnector c = new FakeChannelInquiryConnector("CAFE24", new FakeChannelBoard(f));
        Instant before = Instant.now();
        List<?> rows = c.fetch(page(null, 10)).records();
        CanonicalInquiry failed = (CanonicalInquiry) rows.get(0);
        assertThat(failed.commentCheck().result())
                .isEqualTo(com.sellerops.ingest.canonical.CommentAnswerCheck.Result.NOT_CHECKED);
        assertThat(failed.commentCheck().attemptedAt()).isAfterOrEqualTo(before);
        assertThat(failed.status()).as("a failed look answers nothing").isEqualTo("UNANSWERED");
        assertThat(((CanonicalInquiry) rows.get(1)).commentCheck()).as("this collection did not look").isNull();
        CanonicalInquiry shop = (CanonicalInquiry) rows.get(2);
        assertThat(shop.status()).isEqualTo("ANSWERED");
        assertThat(shop.answerBody()).isNull();
        assertThat(shop.answeredAt()).isNull();
        assertThat(((CanonicalInquiry) rows.get(3)).commentCheck()).as("an unknown word is no look").isNull();
    }
}
