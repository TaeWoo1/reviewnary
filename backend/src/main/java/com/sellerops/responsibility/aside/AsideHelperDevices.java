package com.sellerops.responsibility.aside;

import com.sellerops.auth.device.HelperDevice;
import com.sellerops.auth.device.HelperDeviceRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * <b>«Does this organisation have a helper that can be given work right now?»</b>
 *
 * <p>One question, asked the same way by both lanes, which is why it stopped being a private method on the
 * responsibility observer. The answer is a device GRANT — a {@code helper_devices} row the seller created once
 * by linking their installed helper to this account, valid for months and surviving every restart of the helper
 * and of this backend.
 *
 * <p><b>This is not the browser pairing, and conflating the two has cost a session already.</b> A helper has two
 * independent relationships: this grant, which lets it talk to the backend and claim work on its own; and a
 * per-browser bridge pairing, which lets a tab on the seller's screen drive an Action Window walk. A dispatched
 * job needs only the grant — the helper polls for work with its device token and no browser is involved — so a
 * seller whose browser shows 「이 브라우저와 연결해 주세요」 may still be perfectly able to run a dispatched read,
 * and a seller with a paired browser but a revoked grant cannot.
 */
@Component
public class AsideHelperDevices {

    private final HelperDeviceRepository devices;
    private final Clock clock;

    /**
     * Annotated because there are two. With more than one candidate and none marked, Spring looks for a no-arg
     * constructor and the context fails to start — the same trap {@link AsideMarketplaceAccess} documents, and
     * it cost a full-context suite once already.
     */
    @Autowired
    public AsideHelperDevices(HelperDeviceRepository devices) {
        this(devices, Clock.systemUTC());
    }

    public AsideHelperDevices(HelperDeviceRepository devices, Clock clock) {
        this.devices = devices;
        this.clock = clock;
    }

    /**
     * The organisation's newest live grant, or empty.
     *
     * <p>Empty covers every way there can be nobody to ask: never linked, unlinked since, revoked, or expired.
     * They are one answer on purpose — what a caller does about it is the same in each case (ask the seller to
     * link the helper), and the row that says which it was is not this method's business.
     */
    /**
     * This one device, if it is still this organisation's and still live.
     *
     * <p>Asked when something remembers a desk — the one that last read a given store — and has to find out
     * whether remembering it still means anything. Empty for revoked, expired, unknown, or belonging to another
     * organisation, and those are one answer here for the same reason they are one in {@link #linked}: the
     * caller's next move is the same.
     */
    public Optional<HelperDevice> live(UUID orgId, UUID deviceId) {
        if (orgId == null || deviceId == null) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        return devices.findByIdAndOrgId(deviceId, orgId)
                .filter(d -> d.getRevokedAt() == null)
                .filter(d -> d.getExpiresAt() != null && d.getExpiresAt().isAfter(now));
    }

    public Optional<HelperDevice> linked(UUID orgId) {
        if (orgId == null) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        return devices.findByOrgIdAndRevokedAtIsNullOrderByCreatedAtDesc(orgId).stream()
                .filter(d -> d.getExpiresAt() != null && d.getExpiresAt().isAfter(now))
                .findFirst();
    }
}
