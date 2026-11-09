package com.sellerops.channel.activation;

import com.sellerops.channel.ChannelRepository;
import com.sellerops.common.ApiException;
import com.sellerops.selleraccount.SellerAccount;
import com.sellerops.selleraccount.SellerAccountRepository;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * <b>A channel's official connection opens only where the qualification for it exists</b> — proposal §19.4 T1,
 * {@code docs/standing/server_operation_boundary_v1.md} §6 (owner decision 2026-10-31: channels turn on only through
 * qualified official paths; the final goal is a corporate entity on official connections).
 *
 * <p>Per channel, one level:
 * <ul>
 *   <li>{@code OFF} — no organisation connects, collects or sends through the channel's official API.</li>
 *   <li>{@code SELF} — only the operator's own organisations ({@code self-org-ids}): the seller's own direct use of
 *       their own store, which needs no partner qualification (NAVER #2283; Coupang's self-developed path).</li>
 *   <li>{@code ALL} — every organisation: after the platform approved the service (NAVER solution or agency, Coupang
 *       integrator agreement, Cafe24 commercial review).</li>
 * </ul>
 *
 * <p><b>Where it is held.</b> At the vault — every credential the official API is reached with is stored, rotated and
 * opened there ({@code CredentialVault}), so connecting, collecting and sending all pass it — and before a seller is
 * sent to a marketplace's consent page or given an API account to fill. A file upload is not an official connection
 * and is not gated here.
 *
 * <p><b>Off unless {@code sellerops.channel-activation.enforced=true}</b>, which the server deployment sets: a local
 * or demo backend keeps every connection it had. Enforced, every channel not named is {@code OFF}.
 */
@Component
public class ChannelActivation {

    public enum Level { OFF, SELF, ALL }

    /** What a seller is told; the reason is the platform's, not theirs to fix. */
    public static final String NOT_YET = "이 채널의 공식 연동은 아직 준비 중이에요. 준비되면 이 화면에서 연결할 수 있어요.";

    private final boolean enforced;
    private final Set<UUID> selfOrgs;
    private final Map<String, Level> levels;
    private final SellerAccountRepository accounts;
    private final ChannelRepository channels;

    @Autowired
    public ChannelActivation(@Value("${sellerops.channel-activation.enforced:false}") boolean enforced,
                             @Value("${sellerops.channel-activation.self-org-ids:}") String selfOrgIds,
                             @Value("${sellerops.channel-activation.naver:OFF}") String naver,
                             @Value("${sellerops.channel-activation.coupang:OFF}") String coupang,
                             @Value("${sellerops.channel-activation.cafe24:OFF}") String cafe24,
                             SellerAccountRepository accounts, ChannelRepository channels) {
        this(enforced, parseOrgs(selfOrgIds), Map.of("NAVER", parse(naver), "COUPANG", parse(coupang),
                "CAFE24", parse(cafe24)), accounts, channels);
    }

    public ChannelActivation(boolean enforced, Set<UUID> selfOrgs, Map<String, Level> levels,
                             SellerAccountRepository accounts, ChannelRepository channels) {
        this.enforced = enforced;
        this.selfOrgs = Set.copyOf(selfOrgs);
        Map<String, Level> copy = new HashMap<>();
        levels.forEach((k, v) -> copy.put(k.toUpperCase(Locale.ROOT), v));
        this.levels = Map.copyOf(copy);
        this.accounts = accounts;
        this.channels = channels;
    }

    /** Not enforced: everything is as it was. Enforced: as the channel's level says; an unnamed channel is OFF. */
    public boolean allows(UUID orgId, String channelCode) {
        if (!enforced) {
            return true;
        }
        Level level = channelCode == null ? Level.OFF
                : levels.getOrDefault(channelCode.toUpperCase(Locale.ROOT), Level.OFF);
        return switch (level) {
            case ALL -> true;
            case SELF -> orgId != null && selfOrgs.contains(orgId);
            case OFF -> false;
        };
    }

    public Level level(String channelCode) {
        if (!enforced) {
            return Level.ALL;
        }
        return channelCode == null ? Level.OFF : levels.getOrDefault(channelCode.toUpperCase(Locale.ROOT), Level.OFF);
    }

    /** Refuses before anything reaches the channel — the same answer at every gate. */
    public void require(UUID orgId, String channelCode) {
        if (!allows(orgId, channelCode)) {
            throw new ApiException(HttpStatus.CONFLICT, NOT_YET);
        }
    }

    /** {@link #require} for the account's channel. A file-upload account is not an official connection. */
    public void requireForAccount(UUID orgId, UUID sellerAccountId) {
        if (!enforced) {
            return;
        }
        SellerAccount account = accounts.findById(sellerAccountId).filter(a -> a.getOrgId().equals(orgId))
                .orElse(null);
        if (account == null) {
            // Not this organisation's account: refused here as the vault would refuse it — nothing is opened.
            throw new ApiException(HttpStatus.CONFLICT, NOT_YET);
        }
        if (account.isFileUpload()) {
            return;
        }
        String code = channels.findById(account.getChannelId()).map(c -> c.getCode()).orElse(null);
        require(orgId, code);
    }

    private static Level parse(String raw) {
        try {
            return Level.valueOf(raw == null ? "OFF" : raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            // A level the deployment mistyped opens nothing.
            return Level.OFF;
        }
    }

    private static Set<UUID> parseOrgs(String raw) {
        Set<UUID> out = new HashSet<>();
        if (raw == null) {
            return out;
        }
        for (String part : raw.split(",")) {
            if (!part.isBlank()) {
                out.add(UUID.fromString(part.trim()));
            }
        }
        return out;
    }
}
