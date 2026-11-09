package com.sellerops.vendor.workspace;

import com.sellerops.vendor.Codes;
import com.sellerops.vendor.account.SellerAccounts;
import com.sellerops.vendor.install.Installs;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>The Reviewnary workspace server (backend) acting for one of its organisations</b> —
 * {@code docs/standing/server_operation_boundary_v1.md} §5 (owner decision 2026-10-31: a web seller uses one Reviewnary
 * account; they never sign in here or approve a PC).
 *
 * <p>Two things prove a call. The <b>service token</b> ({@link #PREFIX}…) proves it is the workspace server; only its
 * SHA-256 is configured here ({@code vendor.workspace.service-token-sha256}). The <b>organisation</b> it names
 * ({@link #ORG_HEADER}) is answered for only when an operator linked that organisation to one seller account
 * ({@link #link}). The link is an {@code installs} row of kind {@code WORKSPACE}, so the call is then exactly what a
 * linked PC of that account may do — that account's stores, its reply tasks, its own saved-confirmations — and nothing
 * the PC alone may do (its public IP, unlinking itself) or a seller's session may do.
 *
 * <p>One organisation ↔ one account, both ways: an account is linked to at most one organisation, so two organisations
 * never see the same stores.
 */
@Service
public class WorkspaceLinks {

    /** A workspace service token starts with this; an install token starts with {@code rvi_}. */
    public static final String PREFIX = "rvw_";
    /** Which organisation of the workspace server a call is for. */
    public static final String ORG_HEADER = "X-Reviewnary-Org";

    static final String KIND = "WORKSPACE";
    static final String DEVICE_NAME = "Reviewnary 업무 서버";
    /** A workspace link has no expiry of its own; unlinking (or suspending the account) ends it. */
    private static final long LIFETIME_YEARS = 100;

    public enum LinkFailure { NO_SUCH_ACCOUNT, ACCOUNT_LINKED_ELSEWHERE, ORGANISATION_LINKED_ELSEWHERE }

    public static final class LinkException extends RuntimeException {
        private final LinkFailure failure;

        LinkException(LinkFailure failure) {
            super(failure.name());
            this.failure = failure;
        }

        public LinkFailure failure() {
            return failure;
        }
    }

    public record Link(UUID orgId, UUID accountId, String email, UUID installId, Instant linkedAt) {}

    private final JdbcClient jdbc;
    private final SellerAccounts accounts;
    private final byte[] serviceTokenHash;
    private final Clock clock;

    @Autowired
    public WorkspaceLinks(JdbcClient jdbc, SellerAccounts accounts,
                          @Value("${vendor.workspace.service-token-sha256:}") String serviceTokenSha256) {
        this(jdbc, accounts, serviceTokenSha256, Clock.systemUTC());
    }

    public WorkspaceLinks(JdbcClient jdbc, SellerAccounts accounts, String serviceTokenSha256, Clock clock) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        String configured = serviceTokenSha256 == null ? "" : serviceTokenSha256.trim().toLowerCase();
        this.serviceTokenHash = configured.matches("[0-9a-f]{64}") ? HexFormat.of().parseHex(configured) : null;
        this.clock = clock;
    }

    public static boolean looksLikeServiceToken(String bearer) {
        return bearer != null && bearer.startsWith(PREFIX);
    }

    /** A fresh service token. The operator gives the token to the workspace server and its hash to this one. */
    public static String newServiceToken() {
        return PREFIX + Codes.newCode();
    }

    /** Whether the bearer is the configured service token. Always false when none is configured. */
    public boolean isServiceToken(String bearer) {
        if (serviceTokenHash == null || !looksLikeServiceToken(bearer)) {
            return false;
        }
        byte[] given = HexFormat.of().parseHex(Codes.hash(bearer));
        return MessageDigest.isEqual(given, serviceTokenHash);
    }

    /** The linked account for the organisation, as a PC of that account would authenticate; empty when not linked. */
    @Transactional
    public Optional<Installs.Authenticated> authenticate(UUID orgId) {
        Optional<Installs.Authenticated> found = jdbc.sql("select i.id, i.account_id from installs i "
                        + "join seller_accounts a on a.id = i.account_id "
                        + "where i.kind = ? and i.workspace_org_id = ? and i.revoked_at is null and a.status = ?")
                .params(KIND, orgId, SellerAccounts.Status.ACTIVE.name())
                .query((rs, n) -> new Installs.Authenticated(rs.getObject("id", UUID.class),
                        rs.getObject("account_id", UUID.class)))
                .optional();
        found.ifPresent(a -> jdbc.sql("update installs set last_used_at = ? where id = ?")
                .params(ts(clock.instant()), a.installId()).update());
        return found;
    }

    /** The organisations with a live link whose account is active — what the workspace server pulls for. */
    @Transactional(readOnly = true)
    public List<UUID> organisations() {
        return jdbc.sql("select i.workspace_org_id from installs i join seller_accounts a on a.id = i.account_id "
                        + "where i.kind = ? and i.workspace_org_id is not null and i.revoked_at is null "
                        + "and a.status = ? order by i.created_at")
                .params(KIND, SellerAccounts.Status.ACTIVE.name())
                .query(UUID.class).list();
    }

    /**
     * Links an organisation of the workspace server to the seller account at {@code email}, creating the account
     * (without a password — it never signs in here) when there is none. Refused when either side is already linked
     * to another; linking the same pair again is not an error.
     */
    @Transactional
    public Link link(String email, UUID orgId, boolean createAccount) {
        UUID accountId = accounts.idOf(email).orElseGet(() -> {
            if (!createAccount) {
                throw new LinkException(LinkFailure.NO_SUCH_ACCOUNT);
            }
            return accounts.createWithoutPassword(email);
        });
        Optional<Link> current = byOrg(orgId);
        if (current.isPresent()) {
            if (current.get().accountId().equals(accountId)) {
                return current.get();
            }
            throw new LinkException(LinkFailure.ORGANISATION_LINKED_ELSEWHERE);
        }
        int elsewhere = jdbc.sql("select count(*) from installs where kind = ? and account_id = ? "
                        + "and workspace_org_id is not null and revoked_at is null")
                .params(KIND, accountId).query(Integer.class).single();
        if (elsewhere > 0) {
            throw new LinkException(LinkFailure.ACCOUNT_LINKED_ELSEWHERE);
        }
        Instant now = clock.instant();
        UUID id = UUID.randomUUID();
        try {
            // The checks above give the operator a plain answer; these two unique columns are what holds when two
            // links race past them (V13 organisation, V14 account).
            jdbc.sql("insert into installs (id, account_id, kind, workspace_org_id, workspace_account_id, device_name, "
                            + "created_at, expires_at) values (?, ?, ?, ?, ?, ?, ?, ?)")
                    .params(id, accountId, KIND, orgId, accountId, DEVICE_NAME, ts(now),
                            ts(now.atOffset(java.time.ZoneOffset.UTC).plusYears(LIFETIME_YEARS).toInstant()
                                    .truncatedTo(ChronoUnit.SECONDS)))
                    .update();
        } catch (DuplicateKeyException e) {
            String said = String.valueOf(e.getMostSpecificCause().getMessage()).toLowerCase(java.util.Locale.ROOT);
            throw new LinkException(said.contains("installs_workspace_account_uq")
                    ? LinkFailure.ACCOUNT_LINKED_ELSEWHERE : LinkFailure.ORGANISATION_LINKED_ELSEWHERE);
        }
        return byOrg(orgId).orElseThrow();
    }

    /** Ends the organisation's link. The row stays (its saved-confirmations point at it) without the organisation. */
    @Transactional
    public boolean unlink(UUID orgId) {
        return jdbc.sql("update installs set revoked_at = ?, workspace_org_id = null, workspace_account_id = null "
                        + "where kind = ? and workspace_org_id = ? and revoked_at is null")
                .params(ts(clock.instant()), KIND, orgId).update() > 0;
    }

    @Transactional(readOnly = true)
    public Optional<Link> byOrg(UUID orgId) {
        return jdbc.sql("select i.id, i.account_id, i.created_at, a.email from installs i "
                        + "join seller_accounts a on a.id = i.account_id "
                        + "where i.kind = ? and i.workspace_org_id = ? and i.revoked_at is null")
                .params(KIND, orgId)
                .query((rs, n) -> new Link(orgId, rs.getObject("account_id", UUID.class), rs.getString("email"),
                        rs.getObject("id", UUID.class), rs.getTimestamp("created_at").toInstant()))
                .optional();
    }

    @Transactional(readOnly = true)
    public List<Link> all() {
        return jdbc.sql("select i.id, i.account_id, i.created_at, i.workspace_org_id, a.email from installs i "
                        + "join seller_accounts a on a.id = i.account_id "
                        + "where i.kind = ? and i.workspace_org_id is not null and i.revoked_at is null "
                        + "order by i.created_at")
                .params(KIND)
                .query((rs, n) -> new Link(rs.getObject("workspace_org_id", UUID.class),
                        rs.getObject("account_id", UUID.class), rs.getString("email"),
                        rs.getObject("id", UUID.class), rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    /** For the SHA-256 the operator configures: the same digest {@link #isServiceToken} compares against. */
    public static String hashOf(String serviceToken) {
        return Codes.hash(serviceToken);
    }

    private static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }
}
