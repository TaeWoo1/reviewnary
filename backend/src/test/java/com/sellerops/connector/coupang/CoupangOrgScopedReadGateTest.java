package com.sellerops.connector.coupang;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>The gate is now asked «for whom», and answers differently.</b>
 *
 * <p>These are the assertions that make the Coupang future-join contract true: the named organisation's
 * inquiry read reaches the wire with no Self-Pilot and no collect scheduler anywhere in the picture, and
 * another organisation's read is refused <b>before</b> a request is signed or sent. The refusal is the same
 * exception an unarmed gate throws, because it is the same gate — an organisation that is not named simply
 * has no grant.
 */
class CoupangOrgScopedReadGateTest {

    private static final UUID NAMED = UUID.fromString("7146c50f-ff6d-4c83-ae96-18c930e6d8e0");
    private static final UUID OTHER = UUID.fromString("790b9825-0e01-4794-bf33-6e95f1412e4e");
    private static final String GRANT = "spr-0a1b2c3d4e5f6071";

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-22T02:00:00Z"), ZoneOffset.UTC);
    private final FakeCoupangHttpClient http = new FakeCoupangHttpClient();
    private final List<Long> pauses = new ArrayList<>();

    /** No live approval id anywhere in this test — the grant is the only thing that can open the gate. */
    private CoupangInquiriesClient clientFor(CoupangInquiryReadGrant grant) {
        return new CoupangInquiriesClient(http, new CoupangSigner(clock), clock,
                "https://api-gateway.coupang.com", "",
                (CoupangInquiriesClient.ReadGrant) grant::forOrg, pauses::add);
    }

    private static CoupangHttpClient.Response json(String body) {
        return new CoupangHttpClient.Response(200, body, java.util.Map.of("Content-Type", "application/json"));
    }

    private static String emptyPage() {
        return "{\"code\":200,\"data\":{\"content\":[],\"pagination\":{\"currentPage\":1,\"countPerPage\":10,"
                + "\"totalPages\":1,\"totalElements\":0}}}";
    }

    @Test
    @DisplayName("the named organisation's read reaches the wire — no Self-Pilot, no collect scheduler")
    void namedOrgReads() {
        http.enqueue(json(emptyPage()));
        CoupangInquiriesClient client = clientFor(new CoupangInquiryReadGrant(GRANT, NAMED.toString()));

        CoupangInquiriesClient.FirstPage page = client.probeFirstPage(NAMED, "AK", "SK", "A00012345",
                "NOANSWER", LocalDate.of(2026, 9, 16), LocalDate.of(2026, 9, 22), 10);

        assertThat(page.records()).isZero();
        assertThat(http.sent).hasSize(1);
    }

    @Test
    @DisplayName("an organisation this deployment did not name is refused BEFORE anything is signed or sent")
    void unnamedOrgRefusedWithoutACall() {
        CoupangInquiriesClient client = clientFor(new CoupangInquiryReadGrant(GRANT, NAMED.toString()));

        assertThatThrownBy(() -> client.probeFirstPage(OTHER, "AK", "SK", "A00012345", "NOANSWER",
                LocalDate.of(2026, 9, 16), LocalDate.of(2026, 9, 22), 10))
                .isInstanceOf(CoupangLiveApprovalRequiredException.class);

        assertThat(http.sent).isEmpty();
    }

    @Test
    @DisplayName("the same refusal for the paged sweep, not only the probe")
    void unnamedOrgRefusedOnTheSweep() {
        CoupangInquiriesClient client = clientFor(new CoupangInquiryReadGrant(GRANT, NAMED.toString()));

        assertThatThrownBy(() -> client.fetchInquiryPage(OTHER, "AK", "SK", "A00012345", null))
                .isInstanceOf(CoupangLiveApprovalRequiredException.class);

        assertThat(http.sent).isEmpty();
    }

    @Test
    @DisplayName("a blank grant admits nobody, including the organisation that would otherwise be named")
    void blankGrantAdmitsNobody() {
        CoupangInquiriesClient client = clientFor(new CoupangInquiryReadGrant("", NAMED.toString()));

        assertThatThrownBy(() -> client.fetchInquiryPage(NAMED, "AK", "SK", "A00012345", null))
                .isInstanceOf(CoupangLiveApprovalRequiredException.class);

        assertThat(http.sent).isEmpty();
    }

    @Test
    @DisplayName("a call that lost the organisation fails closed rather than inheriting a grant")
    void nullOrgFailsClosed() {
        CoupangInquiriesClient client = clientFor(new CoupangInquiryReadGrant(GRANT, NAMED.toString()));

        assertThatThrownBy(() -> client.fetchInquiryPage(null, "AK", "SK", "A00012345", null))
                .isInstanceOf(CoupangLiveApprovalRequiredException.class);

        assertThat(http.sent).isEmpty();
    }

    @Test
    @DisplayName("a constant grant still ignores the organisation — the pre-existing Self-Pilot semantics")
    void constantGrantIsTheOldBehaviour() {
        http.enqueue(json(emptyPage()));
        CoupangInquiriesClient client = new CoupangInquiriesClient(http, new CoupangSigner(clock), clock,
                "https://api-gateway.coupang.com", "", GRANT, pauses::add);

        client.probeFirstPage(OTHER, "AK", "SK", "A00012345", "NOANSWER",
                LocalDate.of(2026, 9, 16), LocalDate.of(2026, 9, 22), 10);

        assertThat(http.sent).hasSize(1);
    }
}
