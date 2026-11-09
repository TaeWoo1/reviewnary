package com.sellerops.connector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sellerops.ingest.canonical.CanonicalInquiry;
import com.sellerops.ingest.canonical.CommentAnswerCheck;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>The fake channel's inquiry board</b> (2026-11-06 fourteenth review: 「가짜 채널을 활용해 실제 수집 · 상태 갱신 경로를
 * 검증」). Development only — wired beside {@code FakeChannelReplyAdapter} under {@code sellerops.dev.fake-channel=true},
 * with the same refusals. It is what the marketplace would show a collector, read from two places:
 * <ul>
 *   <li>a file the developer edits — the inquiries customers wrote, and any answer the seller wrote <b>on the seller
 *       centre</b> (the copy path: SellerOps never saw that answer leave);</li>
 *   <li>answers this process registered through the fake reply adapter — the send path.</li>
 * </ul>
 * The file is read on every collection, so an edit is what the next collection sees. Opens no connection.
 *
 * <p><b>The newest change wins, whichever way it came</b> (2026-11-06 eighteenth review). An answer row has one state on
 * the board, and three things change it: the service registering through the fake adapter ({@link #answered}), the
 * seller editing on the seller centre ({@link #edited}), and the developer editing that row's answer in the file — a
 * file row whose answer fields differ from what it said at the previous read is an edit made now. Before this, a
 * registered answer beat the file forever, so "SellerOps registered, then the seller corrected it on the channel" could
 * not be shown at all. An unchanged file row does not undo a later registration. The state lives in memory: a restart
 * starts again from the file.
 *
 * <p><b>No time is made up.</b> An answer the file gives without {@code answeredAt} has no time on the board, exactly
 * like a channel that does not publish one; before, the board stamped "now", which a collection then stored as the
 * channel's answer time.
 *
 * <p>File shape: a JSON array of {@code {"externalId","title","body","receivedAt","status","answerBody","answeredAt"}},
 * {@code status} {@code UNANSWERED} or {@code ANSWERED}, and optionally {@code "commentCheck"} — the Cafe24 comment look
 * this collection reports for the row (2026-11-08), stamped with the time of the read. A missing or unreadable file is an
 * empty board.
 */
public final class FakeChannelBoard {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** One row's answer as the channel shows it: answered or not, the text if any, the time if any. */
    record Answer(boolean answered, String body, Instant at) {
        static final Answer NONE = new Answer(false, null, null);
    }

    private final Path file;
    /** Each row's answer after the newest change. */
    private final Map<String, Answer> latest = new HashMap<>();
    /** What each file row said at the previous read — how a file edit is told apart from a file left alone. */
    private final Map<String, Answer> fileSaid = new HashMap<>();

    public FakeChannelBoard(Path file) {
        this.file = file;
    }

    /** The fake adapter registered {@code body} as the answer to {@code externalId}, at {@code at} (null: no time). */
    public synchronized void answered(String externalId, String body, Instant at) {
        if (externalId != null) {
            latest.put(externalId, new Answer(true, body, at));
        }
    }

    /**
     * The seller changed the answer on the seller centre: the text now shown, and the time the channel now shows — null
     * when it shows none. What it replaces does not matter: a registered answer, a file answer, or none.
     */
    public synchronized void edited(String externalId, String body, Instant at) {
        if (externalId != null) {
            latest.put(externalId, new Answer(true, body, at));
        }
    }

    /** Every inquiry as the channel shows it now, oldest first as written in the file. */
    public synchronized List<CanonicalInquiry> read() {
        List<CanonicalInquiry> out = new ArrayList<>();
        JsonNode rows;
        try {
            if (file == null || !Files.isReadable(file)) {
                return out;
            }
            rows = MAPPER.readTree(Files.readString(file));
        } catch (Exception e) {
            return out;
        }
        if (rows == null || !rows.isArray()) {
            return out;
        }
        int row = 0;
        // One look per collection, stamped once — as the real connector stamps one instant for a page of checks.
        Instant readAt = Instant.now();
        for (JsonNode r : rows) {
            row++;
            String externalId = text(r, "externalId");
            if (externalId == null) {
                continue;
            }
            boolean answeredInFile = "ANSWERED".equals(text(r, "status"));
            Answer inFile = answeredInFile
                    ? new Answer(true, text(r, "answerBody"), instant(text(r, "answeredAt")))
                    : Answer.NONE;
            Answer before = fileSaid.put(externalId, inFile);
            // First sight of the row: the file is the starting state, unless something already changed it since.
            // Later: the file counts only when the developer changed it — then it is the newest change.
            if (before == null ? !latest.containsKey(externalId) : !before.equals(inFile)) {
                latest.put(externalId, inFile);
            }
            Answer a = latest.get(externalId);
            CommentAnswerCheck check = commentCheck(text(r, "commentCheck"), readAt);
            // A proven shop comment answers the inquiry, as the Cafe24 mapper has it — with no text and with only the
            // time the file gives (none is made up).
            boolean answered = a.answered() || (check != null && check.result() == CommentAnswerCheck.Result.SHOP_ANSWERED);
            Instant answeredAt = a.answered() ? a.at() : answered ? instant(text(r, "answeredAt")) : null;
            out.add(new CanonicalInquiry(null, null, null, text(r, "body"), answered ? "ANSWERED" : "UNANSWERED",
                    instant(text(r, "receivedAt")), externalId, row, text(r, "title"), null, false, null, null,
                    a.answered() ? a.body() : null, answeredAt, null, null, null, check));
        }
        return out;
    }

    /**
     * The comment look this collection reports for a row: {@code SHOP_ANSWERED}, {@code NO_SHOP_COMMENT} or
     * {@code NOT_CHECKED}. A row without the field is a collection whose comment lane did not run for it, which leaves
     * the stored record as it was; an unknown word is treated the same.
     */
    private static CommentAnswerCheck commentCheck(String value, Instant at) {
        if (value == null) {
            return null;
        }
        try {
            return new CommentAnswerCheck(CommentAnswerCheck.Result.valueOf(value), at);
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    private static String text(JsonNode r, String field) {
        JsonNode v = r.path(field);
        return v.isMissingNode() || v.isNull() || v.asText().isBlank() ? null : v.asText();
    }

    private static Instant instant(String s) {
        try {
            return s == null ? null : Instant.parse(s);
        } catch (Exception e) {
            return null;
        }
    }
}
