package com.sellerops.selleraccount;

import java.util.UUID;

/**
 * <b>A seller account just became CONNECTED.</b>
 *
 * <p>Published by the channel's own connection lifecycle at the moment the status actually moves — not on every
 * duplicate success event, because «connected again» is not a connection. It carries two ids and nothing else:
 * no channel code, no credential, no provider detail. A listener that needs more reads it for itself.
 *
 * <p>It exists so that finishing a connection can switch on the things a connection implies — today, automatic
 * review checking ({@code ReviewAutoCheckConnectionListener}) — without the lifecycle learning what those
 * things are. The alternative was a background sweep over every account in the database, which would have made
 * a loop the thing that decides whose stores get read.
 */
public record SellerAccountConnectedEvent(UUID orgId, UUID sellerAccountId) {
}
