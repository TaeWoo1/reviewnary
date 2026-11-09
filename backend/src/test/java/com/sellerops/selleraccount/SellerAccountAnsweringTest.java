package com.sellerops.selleraccount;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * <b>One store, several routes; one account answers</b> — {@code docs/standing/server_operation_boundary_v1.md} §5
 * (external review 2026-10-31). The delegate's route attached to a store never makes a second answering account: it
 * answers only while it is all the store has, and yields once the store's own connection exists.
 */
class SellerAccountAnsweringTest {

    @Test
    void theDelegatesRouteAnswersOnlyWhileItIsAllThereIs() {
        SellerAccount delegated = account(true, SellerAccount.ROUTE_DELEGATE_UPLOAD);
        assertThat(SellerAccount.answering(List.of(delegated))).isSameAs(delegated);

        SellerAccount api = account(false, null);
        assertThat(SellerAccount.answering(List.of(delegated, api))).as("the official connection answers").isSameAs(api);
        assertThat(SellerAccount.answering(List.of(api, delegated))).isSameAs(api);

        SellerAccount ownFile = account(true, null);
        assertThat(SellerAccount.answering(List.of(delegated, ownFile))).as("the seller's own file upload too")
                .isSameAs(ownFile);
    }

    @Test
    void noneOrTwoOwnConnectionsStillAnswerNothing() {
        assertThat(SellerAccount.answering(List.of())).isNull();
        assertThat(SellerAccount.answering(List.of(account(false, null), account(true, null)))).isNull();
        assertThat(SellerAccount.answering(List.of(account(true, null), account(true, null),
                account(true, SellerAccount.ROUTE_DELEGATE_UPLOAD)))).isNull();
    }

    private static SellerAccount account(boolean fileUpload, String route) {
        SellerAccount a = new SellerAccount();
        a.setId(UUID.randomUUID());
        a.setFileUpload(fileUpload);
        a.setReviewRoute(route);
        return a;
    }
}
