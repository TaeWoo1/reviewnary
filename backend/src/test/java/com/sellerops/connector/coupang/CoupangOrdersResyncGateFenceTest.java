package com.sellerops.connector.coupang;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>The {@code orders-resync} live gate, asserted on the harness itself</b> — Order Context Foundation
 * v1 §8.1 gate 1.
 *
 * <p><b>Why a Java test reads shell scripts.</b> The approval harness is the thing that decides whether a
 * marketplace call may happen, and until now its most load-bearing field was a string literal:
 * {@code "mode": "WRITE"} was hardcoded, so no run could ever be approved as a read however read-only it
 * was. Deriving it is the fix, and a derivation has a failure mode a literal did not — a kind added later
 * could fall into the READ_ONLY branch, or {@code READ_ONLY} could be claimed by a kind that is able to
 * take a credential. Those are the two things asserted here.
 *
 * <p>Nothing in this test runs a script or touches a marketplace. It reads the source, the way the
 * package's other fences do.
 */
class CoupangOrdersResyncGateFenceTest {

    private static final Path TOOLS = Paths.get("../tools/coupang-local");

    private static String script(String name) throws IOException {
        return Files.readString(TOOLS.resolve(name));
    }

    @Test
    @DisplayName("the mode is derived from the run kind, WRITE is the default, and only orders-resync is READ_ONLY")
    void modeIsDerivedAndDefaultsToWrite() throws IOException {
        String preflight = script("preflight.sh");
        // Not a literal any more — a hardcoded WRITE made the field uninformative, and a hardcoded
        // READ_ONLY would be worse.
        assertThat(preflight).doesNotContain("\"mode\": \"WRITE\"").doesNotContain("\"mode\": \"READ_ONLY\"");
        assertThat(preflight).contains("\"mode\": \"$APPROVAL_MODE\"");

        // The derivation itself: one READ_ONLY arm, and a default that is WRITE. A kind added later with
        // no considered mode is WRITE — the safe direction to be wrong in.
        assertThat(preflight).contains("orders-resync) APPROVAL_MODE=\"READ_ONLY\" ;;");
        assertThat(preflight).contains("*)             APPROVAL_MODE=\"WRITE\" ;;");
        int readOnlyArms = occurrences(preflight, "APPROVAL_MODE=\"READ_ONLY\"");
        assertThat(readOnlyArms)
                .as("a second READ_ONLY arm means a second kind claims a read posture — it needs its own "
                        + "structural refusal, and this test should be extended rather than relaxed")
                .isEqualTo(1);

        // And the displayed line shows the derived mode, not the word WRITE. An operator approves the
        // DISPLAYED manifest, so a display that disagrees with the JSON is the whole contract failing.
        assertThat(preflight).contains("echo \"  $APPROVAL_MODE · run ");
    }

    @Test
    @DisplayName("orders-resync cannot be the place a credential is entered — it refuses to start without one")
    void theReadOnlyKindNeverTakesACredential() throws IOException {
        String preflight = script("preflight.sh");
        // Its declared ceiling says so...
        assertThat(preflight).contains("sync=1, credential=0, test=0, re-sync=0");
        // ...and its baseline ENFORCES it: the credential must already be stored, so the handoff cannot
        // happen inside this run even by accident. A kind that claimed READ_ONLY while being able to take
        // a credential is the failure §7.1 of the contract exists to prevent.
        assertThat(preflight).contains("run the credential handoff first");
        assertThat(preflight).contains("this run does not enter one");
    }

    @Test
    @DisplayName("the gate proves the reference and NOT the canonical binding — that is a second surface")
    void gateOneStopsShortOfTheProductBinding() throws IOException {
        String preflight = script("preflight.sh");
        // channel_order_products is the success criterion...
        assertThat(preflight).contains("channel_order_products");
        // ...and the manifest says out loud what it does not prove, because the product catalogue is a
        // SECOND marketplace surface and therefore a second manifest and a second grant.
        assertThat(preflight).contains("SELLER_PRODUCTS");
        assertThat(preflight).contains("UNBOUND here is the expected result");
    }

    @Test
    @DisplayName("the gate refuses a backend that would not record its own answer")
    void theObservationMustBeArmed() throws IOException {
        String preflight = script("preflight.sh");
        // Reads the flag off /setup — the same property the orders client reads, so the two cannot
        // disagree — and fails closed when it is off. Spending the one approval and finding out
        // afterwards that nothing was recorded is the failure this check exists to prevent.
        assertThat(preflight).contains("orderWireShapeObserved");
        assertThat(preflight).contains("wire-shape observation OFF");
        assertThat(preflight).contains("SELLEROPS_CONNECTOR_COUPANG_ORDER_WIRE_SHAPE=true");
    }

    @Test
    @DisplayName("bootstrap knows the kind, and every kind the bootstrap mints is one preflight accepts")
    void theTwoScriptsAgreeOnTheKinds() throws IOException {
        String bootstrap = script("bootstrap.sh");
        assertThat(bootstrap).contains("orders-resync)");
        // The two scripts carry the kind list separately, so they can drift apart — and a kind the
        // bootstrap mints but the preflight rejects is a run that can never be approved.
        List<String> kinds = List.of("orders", "orders-resync", "inquiries", "inquiries-dedupe");
        String accepted = "orders|orders-resync|inquiries|inquiries-dedupe) ;;";
        assertThat(script("preflight.sh")).contains(accepted);
        for (String kind : kinds) {
            assertThat(bootstrap).as("bootstrap does not mint %s", kind).contains("  " + kind + ")");
            assertThat(accepted).as("preflight does not accept %s", kind).contains(kind);
        }
    }

    private static int occurrences(String text, String needle) {
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }
}
