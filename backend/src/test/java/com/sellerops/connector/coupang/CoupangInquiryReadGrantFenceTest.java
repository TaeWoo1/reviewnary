package com.sellerops.connector.coupang;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>The grant opens a READ, it is scoped to named organisations, and it can be turned on by a deployment.</b>
 * Three properties, each of which has failed somewhere in this repository before:
 *
 * <ul>
 *   <li>a read grant that a write gate could also accept — closed structurally, because
 *       {@code ensureLiveWriteAllowed} has no grant parameter at all;</li>
 *   <li>a grant whose scope is «the process», which is only safe while one operator runs one backend;</li>
 *   <li>a name the backend reads that the container never sees — the defect
 *       {@code CapabilityEnvWiringTest} exists for, three times over.</li>
 * </ul>
 */
class CoupangInquiryReadGrantFenceTest {

    private static final Path APPLICATION_YML = Path.of("src/main/resources/application.yml");
    private static final Path COMPOSE = Path.of("..", "docker-compose.yml");
    private static final Path PILOT_ENV = Path.of("..", "deploy", "pilot", "pilot.env.example");
    private static final Path SRC = Path.of("src/main/java/com/sellerops/connector/coupang");

    private static final List<String> NAMES = List.of(
            "SELLEROPS_CONNECTOR_COUPANG_INQUIRY_READ_GRANT_ID",
            "SELLEROPS_CONNECTOR_COUPANG_INQUIRY_READ_GRANT_ORG_IDS");

    /** Comments stripped: these classes EXPLAIN that they reach no write, so a raw scan would fail on the
     * sentence stating the property, and a guard that fails on its own documentation gets deleted. */
    private static String code(String file) throws IOException {
        return Files.readString(SRC.resolve(file))
                .replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    @Test
    @DisplayName("the write gate takes no grant — so no value this grant produces can open a write")
    void writeGateHasNoGrantParameter() throws IOException {
        String guard = code("CoupangLiveCallGuard.java");
        assertThat(guard).contains("ensureLiveWriteAllowed(String baseUrl, String liveApprovalId)");
        // the READ gate is the only one that takes a third argument
        assertThat(guard).contains("ensureLiveReadAllowed(String baseUrl, String liveApprovalId,");
    }

    @Test
    @DisplayName("the reply client cannot even name the grant")
    void replyClientNeverNamesTheGrant() throws IOException {
        String reply = code("CoupangInquiryReplyClient.java");
        assertThat(reply).doesNotContain("CoupangInquiryReadGrant");
        assertThat(reply).doesNotContain("ReadGrant");
        assertThat(reply).doesNotContain("readGrant");
    }

    @Test
    @DisplayName("the grant class names no write, approval-minting or execution path")
    void grantNamesNoWritePath() throws IOException {
        String grant = code("CoupangInquiryReadGrant.java");
        for (String forbidden : List.of("Write", "write", "post", "Post", "reply", "Reply",
                "execute", "Execution", "mint", "Approval")) {
            assertThat(grant).as("CoupangInquiryReadGrant must not mention %s", forbidden)
                    .doesNotContain(forbidden);
        }
    }

    @Test
    @DisplayName("ORDER_SUMMARY is deliberately NOT widened by this grant")
    void ordersClientIsUnchanged() throws IOException {
        // The orders client keeps the process-wide Self-Pilot grant. ORDER_SUMMARY is not a responsibility
        // source and nothing in the Coupang-join contract asks for it; opening it would be a wider grant.
        String config = code("CoupangConnectorConfiguration.java");
        // The construction STATEMENT, not a line range: the precedence helper lives between the two bean
        // methods, so a range would read its signature and see the scoped grant where it is not used.
        int at = config.indexOf("new CoupangOrdersClient(");
        assertThat(at).as("the orders client is constructed in the configuration").isGreaterThan(-1);
        String statement = config.substring(at, config.indexOf(";", at));
        assertThat(statement).doesNotContain("CoupangInquiryReadGrant").doesNotContain("inquiryReadGrantOf");
        assertThat(statement).contains("effectiveReadGrant(selfPilotEnabled, standingReadGrantId)");
        // and the inquiries client is the ONLY place the scoped grant reaches
        int inq = config.indexOf("new CoupangInquiriesClient(");
        assertThat(config.substring(inq, config.indexOf(";", inq))).contains("inquiryReadGrantOf(");
    }

    @Test
    @DisplayName("narrow first: the organisation-scoped grant wins, and the Self-Pilot grant is the unchanged fallback")
    void precedenceIsNarrowFirst() {
        UUID named = UUID.fromString("7146c50f-ff6d-4c83-ae96-18c930e6d8e0");
        UUID other = UUID.fromString("790b9825-0e01-4794-bf33-6e95f1412e4e");
        CoupangInquiryReadGrant scoped = new CoupangInquiryReadGrant("spr-0a1b2c3d", named.toString());

        // org-scoped present, self-pilot absent (the cloud posture)
        assertThat(CoupangConnectorConfiguration.inquiryReadGrantOf(scoped, "").forOrg(named))
                .isEqualTo("spr-0a1b2c3d");
        assertThat(CoupangConnectorConfiguration.inquiryReadGrantOf(scoped, "").forOrg(other)).isEmpty();

        // self-pilot present, org-scoped unset (every deployment before this seam)
        CoupangInquiryReadGrant none = new CoupangInquiryReadGrant("", "");
        assertThat(CoupangConnectorConfiguration.inquiryReadGrantOf(none, "spr-99999999").forOrg(other))
                .isEqualTo("spr-99999999");

        // neither
        assertThat(CoupangConnectorConfiguration.inquiryReadGrantOf(none, "").forOrg(named)).isEmpty();
        assertThat(CoupangConnectorConfiguration.inquiryReadGrantOf(null, null).forOrg(named)).isEmpty();
    }

    @Test
    @DisplayName("application.yml binds both names and ships them blank")
    void shippedBlank() throws IOException {
        String yml = Files.readString(APPLICATION_YML);
        assertThat(yml).contains("inquiry-read-grant-id: ${SELLEROPS_CONNECTOR_COUPANG_INQUIRY_READ_GRANT_ID:}");
        assertThat(yml).contains(
                "inquiry-read-grant-org-ids: ${SELLEROPS_CONNECTOR_COUPANG_INQUIRY_READ_GRANT_ORG_IDS:}");
    }

    @Test
    @DisplayName("the container sees both names, and the pilot template gives an operator a name to copy")
    void plumbedEndToEnd() throws IOException {
        String compose = Files.readString(COMPOSE);
        String env = Files.readString(PILOT_ENV);
        for (String name : NAMES) {
            assertThat(compose).as("docker-compose.yml passes %s to the backend", name)
                    .contains(name + ": ${" + name);
            assertThat(env).as("pilot.env.example names %s", name)
                    .containsPattern("(?m)^" + name + "=");
        }
    }
}
