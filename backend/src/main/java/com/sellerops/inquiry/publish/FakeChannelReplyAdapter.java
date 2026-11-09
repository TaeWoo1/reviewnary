package com.sellerops.inquiry.publish;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>A channel that registers by rule, for the end-to-end check of approval and registration without a marketplace</b>
 * (2026-11-05 eighth review: 「실제 마켓 대신 개발용 가짜 채널 어댑터를 붙여 … 실제 API로 한 번 통과」). Development only —
 * {@link FakeChannelWiring} registers it when {@code sellerops.dev.fake-channel=true}, refuses a {@code prod} profile, and
 * refuses to start beside the real adapters ({@code sellerops.inquiry.publish.execution-enabled}). It opens no connection.
 *
 * <p>It serves one channel code (CAFE24 by default, the subtype-less row) and answers as the channel would:
 * <ul>
 *   <li>a reply to an inquiry whose title carries 「[일시오류]」 is refused once as nothing-sent
 *       ({@link ReplyPublishResult#retryableFailure()} — a brief outage), and goes through on the next attempt;</li>
 *   <li>anything else is accepted, with a provider reference of its own;</li>
 *   <li>verification confirms what it accepted, and nothing else.</li>
 * </ul>
 */
public final class FakeChannelReplyAdapter implements ChannelReplyAdapter {

    public static final String OUTAGE_MARK = "[일시오류]";

    private final String channelCode;
    /** Inquiries already refused once — the outage is over for them. */
    private final Set<String> refusedOnce = ConcurrentHashMap.newKeySet();
    /** Provider references this fake issued. */
    private final Set<String> issued = ConcurrentHashMap.newKeySet();

    /** Where an accepted reply shows up for the fake channel's collector (null: nowhere — the 2026-11-05 shape). */
    private final com.sellerops.connector.FakeChannelBoard board;

    public FakeChannelReplyAdapter(String channelCode) {
        this(channelCode, null);
    }

    public FakeChannelReplyAdapter(String channelCode, com.sellerops.connector.FakeChannelBoard board) {
        this.channelCode = channelCode;
        this.board = board;
    }

    @Override
    public String channelCode() {
        return channelCode;
    }

    @Override
    public ReplyPublishResult publish(ReplyPublishCommand command) {
        String key = command.channelId() + ":" + command.externalId();
        if (command.targetSubject() != null && command.targetSubject().contains(OUTAGE_MARK) && refusedOnce.add(key)) {
            return ReplyPublishResult.retryableFailure();
        }
        String ref = "fake-" + UUID.randomUUID();
        issued.add(ref);
        if (board != null) {
            board.answered(command.externalId(), command.body(), java.time.Instant.now());
        }
        return ReplyPublishResult.confirmed(ref);
    }

    @Override
    public ReplyVerificationResult verify(ReplyVerificationCommand command) {
        return command.providerRef() != null && issued.contains(command.providerRef())
                ? ReplyVerificationResult.completed("FAKE_CHANNEL_CONFIRMED")
                : ReplyVerificationResult.notCompleted("FAKE_CHANNEL_NOT_FOUND");
    }
}
