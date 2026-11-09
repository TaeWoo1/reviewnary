package com.sellerops.connector;

import com.sellerops.ingest.canonical.CanonicalInquiry;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>The fake channel's collector</b> (2026-11-06 fourteenth review). A {@link PullConnector} dedicated to the fake
 * channel's code that pages {@link FakeChannelBoard} through the ordinary collection path — 「지금 수집하기」 →
 * {@code SyncRunExecutor} → {@code IngestionService.ingestInquiries} with the seller account stamped, the same door a
 * real connector uses. Inquiries only. Development only, wired by {@code FakeChannelWiring} with its refusals; it opens
 * no connection and holds no credential.
 */
public final class FakeChannelInquiryConnector implements PullConnector {

    public static final String KIND = "FAKE_CHANNEL";

    private final String channelCode;
    private final FakeChannelBoard board;

    public FakeChannelInquiryConnector(String channelCode, FakeChannelBoard board) {
        this.channelCode = channelCode;
        this.board = board;
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public Set<String> dedicatedChannels() {
        return Set.of(channelCode);
    }

    @Override
    public ConnectorCapabilities capabilities(String code) {
        return new ConnectorCapabilities("API", Set.of(DataType.INQUIRY), Map.of(DataType.INQUIRY, "DEV_FAKE"),
                "개발용 가짜 채널: 파일로 둔 문의 게시판을 읽는다. 네트워크 없음.");
    }

    @Override
    public FetchPage fetch(FetchRequest request) {
        if (request.dataType() != DataType.INQUIRY) {
            throw new UnsupportedDataTypeException(request.channelCode(), request.dataType());
        }
        List<CanonicalInquiry> all = board.read();
        int from = 0;
        try {
            from = request.cursorValue() == null ? 0 : Math.max(0, Integer.parseInt(request.cursorValue()));
        } catch (NumberFormatException e) {
            from = 0;
        }
        int limit = request.limit() <= 0 ? 50 : request.limit();
        int to = Math.min(all.size(), from + limit);
        List<CanonicalInquiry> page = from >= all.size() ? List.of() : all.subList(from, to);
        boolean more = to < all.size();
        return FetchPage.of(DataType.INQUIRY, page, more ? String.valueOf(to) : null, more, KIND);
    }
}
