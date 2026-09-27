package com.sellerops.connector.coupang;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The grant is «this id, for these organisations», and every other answer is {@code ""}. The interesting
 * assertions are the refusals: each is a way a standing grant could otherwise have become wider than the
 * organisations a deployment named.
 */
class CoupangInquiryReadGrantTest {

    private static final String GRANT = "spr-0a1b2c3d4e5f6071";
    private static final UUID ORG = UUID.fromString("7146c50f-ff6d-4c83-ae96-18c930e6d8e0");
    private static final UUID OTHER = UUID.fromString("790b9825-0e01-4794-bf33-6e95f1412e4e");

    @Test
    @DisplayName("shipped blank: nothing is admitted")
    void blankAdmitsNobody() {
        assertThat(new CoupangInquiryReadGrant("", "").forOrg(ORG)).isEmpty();
        assertThat(new CoupangInquiryReadGrant("", "").isArmed()).isFalse();
    }

    @Test
    @DisplayName("a named organisation is admitted; another one is not")
    void namedOrgAdmitted() {
        CoupangInquiryReadGrant g = new CoupangInquiryReadGrant(GRANT, ORG.toString());
        assertThat(g.forOrg(ORG)).isEqualTo(GRANT);
        assertThat(g.forOrg(OTHER)).isEmpty();
        assertThat(g.isArmed()).isTrue();
    }

    @Test
    @DisplayName("a grant with no organisation named admits nobody")
    void grantWithoutOrgs() {
        CoupangInquiryReadGrant g = new CoupangInquiryReadGrant(GRANT, "");
        assertThat(g.forOrg(ORG)).isEmpty();
        assertThat(g.isArmed()).isFalse();
    }

    @Test
    @DisplayName("an organisation named with no grant admits nobody")
    void orgsWithoutGrant() {
        assertThat(new CoupangInquiryReadGrant("", ORG.toString()).forOrg(ORG)).isEmpty();
    }

    @Test
    @DisplayName("* is refused, not widened — it is an empty list, and an empty list admits nobody")
    void wildcardRefused() {
        CoupangInquiryReadGrant g = new CoupangInquiryReadGrant(GRANT, "*");
        assertThat(g.forOrg(ORG)).isEmpty();
        assertThat(g.orgIds()).isEmpty();
        assertThat(g.isArmed()).isFalse();
    }

    @Test
    @DisplayName("a * mixed into a real list does not admit the * and does not drop the real id")
    void wildcardMixedIn() {
        CoupangInquiryReadGrant g = new CoupangInquiryReadGrant(GRANT, ORG + ",*");
        assertThat(g.orgIds()).containsExactly(ORG);
        assertThat(g.forOrg(ORG)).isEqualTo(GRANT);
        assertThat(g.forOrg(OTHER)).isEmpty();
    }

    @Test
    @DisplayName("a null organisation is not admitted — a call that lost whose data it asks for fails closed")
    void nullOrgRefused() {
        assertThat(new CoupangInquiryReadGrant(GRANT, ORG.toString()).forOrg(null)).isEmpty();
    }

    @Test
    @DisplayName("a malformed grant id refuses the boot rather than disarming quietly")
    void malformedGrantRefusesBoot() {
        for (String bad : new String[] {"not-a-grant", "spr-", "spr-XYZ", "spr-0a1b", "apr-0a1b2c3d",
                "spr-0a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d"}) {
            assertThatThrownBy(() -> new CoupangInquiryReadGrant(bad, ORG.toString()))
                    .as(bad)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("INQUIRY_READ_GRANT_ID");
        }
    }

    @Test
    @DisplayName("the accepted shape is the one SelfPilotProperties already validates")
    void acceptedShape() {
        assertThat(new CoupangInquiryReadGrant("spr-0a1b2c3d", ORG.toString()).forOrg(ORG)).isNotEmpty();
        assertThat(new CoupangInquiryReadGrant("spr-0a1b2c3d4e5f60718293a4b5c6d7e8f", ORG.toString())
                .forOrg(ORG)).isNotEmpty();
    }

    @Test
    @DisplayName("whitespace, separators and unparseable entries")
    void listParsing() {
        assertThat(new CoupangInquiryReadGrant(GRANT, " " + ORG + " , " + OTHER + " ").orgIds())
                .containsExactly(ORG, OTHER);
        assertThat(new CoupangInquiryReadGrant(GRANT, " , ").orgIds()).isEmpty();
        assertThat(new CoupangInquiryReadGrant(GRANT, "not-a-uuid," + ORG).orgIds()).containsExactly(ORG);
    }
}
