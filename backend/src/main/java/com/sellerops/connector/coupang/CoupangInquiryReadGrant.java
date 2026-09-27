package com.sellerops.connector.coupang;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * <b>A standing Coupang INQUIRY read grant that belongs to named organisations rather than to the process.</b>
 *
 * <p>The grant that already exists — {@code sellerops.self-pilot.read-grant-id} — is disarmed unless the
 * Self-Pilot Runtime itself is on ({@code CoupangConnectorConfiguration.effectiveReadGrant}), and a
 * pilot deploy cannot turn Self-Pilot on without also turning on the collect poller ({@code deploy.sh}
 * refuses the pair split). That coupling is correct for what it was written for — one operator, one local
 * backend, routine collection — and wrong for a cloud host whose only Coupang reader is the Responsibility
 * runtime. This grant is the other shape: <b>no Self-Pilot, no collect scheduler, and an explicit list of
 * organisations instead of «whatever this process does».</b>
 *
 * <p><b>Why the org list is not optional.</b> The existing grant's docblock argues its process scope is
 * safe because there is one operator and one local backend. On a shared cloud host that argument fails: a
 * second organisation that connects Coupang and presses 「지금 동기화」 would ride the same process-wide
 * grant. So this grant is resolved per call, against the organisation the call is for, and
 * {@code forOrg} of an unnamed organisation returns {@code ""} — which the read gate treats exactly as no
 * grant at all.
 *
 * <p><b>{@code *} is refused, not widened.</b> Every other org list in this repository accepts it for the
 * single-user posture. There is no posture in which «every organisation on this host may have its Coupang
 * account read with a standing grant» is the right answer, so a wildcard is parsed as an empty list, which
 * admits nobody.
 *
 * <p><b>READ only, and structurally so.</b> This class is reachable from {@code CoupangInquiriesClient}'s
 * read gate and from nowhere else. {@code CoupangLiveCallGuard.ensureLiveWriteAllowed(baseUrl,
 * liveApprovalId)} takes no grant parameter at all, so no value produced here can open a write — that is
 * not a rule this class enforces, it is a shape the write gate does not have.
 *
 * <p>Off by default: with a blank grant id or an empty org list nothing is admitted, and a deployment that
 * sets neither behaves byte-for-byte as it did before this class existed.
 */
public class CoupangInquiryReadGrant {

    /**
     * The same shape {@code SelfPilotProperties} validates for the grant it owns — one vocabulary for
     * one kind of token, so an operator who has minted one knows what this one looks like.
     */
    static final Pattern GRANT_SHAPE = Pattern.compile("^spr-[0-9a-f]{8,32}$");

    private final String grantId;
    private final List<UUID> orgIds;

    /**
     * Not a Spring bean on purpose: it is constructed inside the one {@code @Bean} method that needs it
     * ({@code CoupangConnectorConfiguration.coupangInquiriesClient}), so the connector bean graph the six
     * {@code *ConnectorConfigurationTest} slices assert about does not gain a dependency, and nothing else
     * in the application can inject a Coupang read grant.
     */
    public CoupangInquiryReadGrant(String grantId, String orgIds) {
        String trimmed = grantId == null ? "" : grantId.trim();
        // A malformed grant is a boot refusal, not a quiet disarm: an operator who typed it meant to arm
        // something, and a deployment that silently reads nothing is the failure this is here to avoid.
        if (!trimmed.isEmpty() && !GRANT_SHAPE.matcher(trimmed).matches()) {
            throw new IllegalStateException(
                    "SELLEROPS_CONNECTOR_COUPANG_INQUIRY_READ_GRANT_ID is not a read grant id "
                            + "(expected spr-<8..32 hex>)");
        }
        this.grantId = trimmed;
        this.orgIds = parseOrgIds(orgIds);
    }

    /** {@code *} yields an empty list; an unparseable entry is dropped rather than admitted. */
    private static List<UUID> parseOrgIds(String csv) {
        if (csv == null || csv.isBlank() || csv.trim().equals("*")) {
            return List.of();
        }
        return Arrays.stream(csv.split(",")).map(String::trim)
                .filter(s -> !s.isEmpty() && !s.equals("*"))
                .map(CoupangInquiryReadGrant::parseOrNull).filter(Objects::nonNull).toList();
    }

    private static UUID parseOrNull(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * The grant for this organisation, or {@code ""} — which the read gate reads as «no grant».
     * A null organisation is not admitted: a call site that lost track of whose data it is asking for
     * must fail closed rather than inherit somebody's grant.
     */
    public String forOrg(UUID orgId) {
        if (grantId.isEmpty() || orgId == null || !orgIds.contains(orgId)) {
            return "";
        }
        return grantId;
    }

    /** For diagnostics and tests; never the grant value. */
    public boolean isArmed() {
        return !grantId.isEmpty() && !orgIds.isEmpty();
    }

    public List<UUID> orgIds() {
        return orgIds;
    }
}
