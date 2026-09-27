package com.sellerops.reviewimport.unattended;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The gate is three answers, and no two of them are enough. These tests are the record of that —
 * in particular that {@code *} is not a widening here but an empty list.
 */
class UnattendedReviewExportPropertiesTest {

    private static final UUID ORG = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DEVICE = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID OTHER = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static UnattendedReviewExportProperties props(boolean enabled, String orgs, String devices) {
        return new UnattendedReviewExportProperties(enabled, orgs, devices);
    }

    @Test
    @DisplayName("off by default: nothing is admitted even when both ids are named")
    void offAdmitsNobody() {
        assertThat(props(false, ORG.toString(), DEVICE.toString()).admits(ORG, DEVICE)).isFalse();
    }

    @Test
    @DisplayName("all three together admit")
    void allThreeAdmit() {
        assertThat(props(true, ORG.toString(), DEVICE.toString()).admits(ORG, DEVICE)).isTrue();
    }

    @Test
    @DisplayName("an unnamed organisation is not admitted by a named device")
    void otherOrgRefused() {
        assertThat(props(true, ORG.toString(), DEVICE.toString()).admits(OTHER, DEVICE)).isFalse();
    }

    @Test
    @DisplayName("an unnamed device is not admitted by a named organisation")
    void otherDeviceRefused() {
        assertThat(props(true, ORG.toString(), DEVICE.toString()).admits(ORG, OTHER)).isFalse();
    }

    @Test
    @DisplayName("blank lists admit nobody, which is what 'enabled with nothing named' must mean")
    void blankListsAdmitNobody() {
        assertThat(props(true, "", "").admits(ORG, DEVICE)).isFalse();
        assertThat(props(true, ORG.toString(), "").admits(ORG, DEVICE)).isFalse();
        assertThat(props(true, "", DEVICE.toString()).admits(ORG, DEVICE)).isFalse();
    }

    @Test
    @DisplayName("* is NOT a wildcard here — it is an empty list, and an empty list admits nobody")
    void wildcardIsRefusedNotWidened() {
        assertThat(props(true, "*", "*").admits(ORG, DEVICE)).isFalse();
        assertThat(props(true, "*", DEVICE.toString()).admits(ORG, DEVICE)).isFalse();
        assertThat(props(true, ORG.toString(), "*").admits(ORG, DEVICE)).isFalse();
        assertThat(props(true, "*", "*").orgIds()).isEmpty();
        assertThat(props(true, "*", "*").deviceIds()).isEmpty();
    }

    @Test
    @DisplayName("a * mixed into a real list does not admit the * and does not drop the real id")
    void wildcardMixedIn() {
        UnattendedReviewExportProperties p = props(true, ORG + ",*", DEVICE.toString());
        assertThat(p.orgIds()).containsExactly(ORG);
        assertThat(p.admits(ORG, DEVICE)).isTrue();
        assertThat(p.admits(OTHER, DEVICE)).isFalse();
    }

    @Test
    @DisplayName("a typo is dropped rather than admitted, and does not stop a deployment that has this off")
    void unparseableIdDropped() {
        UnattendedReviewExportProperties p = props(true, "not-a-uuid," + ORG, DEVICE.toString());
        assertThat(p.orgIds()).containsExactly(ORG);
    }

    @Test
    @DisplayName("nulls are not admitted")
    void nullsRefused() {
        UnattendedReviewExportProperties p = props(true, ORG.toString(), DEVICE.toString());
        assertThat(p.admits(null, DEVICE)).isFalse();
        assertThat(p.admits(ORG, null)).isFalse();
    }

    @Test
    @DisplayName("whitespace and multiple entries parse")
    void listParsing() {
        UnattendedReviewExportProperties p = props(true, " " + ORG + " , " + OTHER + " ", DEVICE.toString());
        assertThat(p.orgIds()).containsExactly(ORG, OTHER);
    }
}
