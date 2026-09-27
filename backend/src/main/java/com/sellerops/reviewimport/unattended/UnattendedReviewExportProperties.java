package com.sellerops.reviewimport.unattended;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The switch for the 72h shadow run's unattended NAVER review export — off by default.
 *
 * <p><b>What this widens, stated plainly.</b> Today a review-import launch is minted only by a seller's
 * own session: {@code POST /api/imports/reviews/plans/**} is not on {@code HelperDeviceAuthFilter.ALLOWED},
 * so an installed helper can ingest into a launch it was given but cannot create one. That is the human
 * checkpoint PD-7 recognises (docs/review_acquisition_aside_v2.md §12), and this capability removes it for
 * a named organisation and a named device, for one shadow run, and for nothing else.
 *
 * <p><b>Why three names and not one.</b> Each closes a different question that the other two cannot:
 * <ul>
 *   <li>{@code enabled} — does this deployment have the capability at all. Off means the route answers 403
 *       and the deployment behaves byte-for-byte as it did before this package.</li>
 *   <li>{@code orgIds} — <b>whose</b> reviews may be acquired unattended. Never {@code *}: the point of the
 *       capability is one demo organisation, and a wildcard here would authorize every seller on the host
 *       to have their seller centre driven with nobody watching.</li>
 *   <li>{@code deviceIds} — <b>which</b> paired helper may do it. The organisation's own list is not enough:
 *       a seller may pair a laptop for guided work and a VM for this run, and only the VM is the subject of
 *       an unattended authority. The id exists after pairing, so naming it is a second deploy pass — the
 *       same shape the org-id lists already have (deploy/pilot/deploy.sh §7-0 step 3).</li>
 * </ul>
 *
 * <p><b>Neither list admits {@code *}.</b> Every other org list in this repository accepts it for the
 * single-user posture; this one refuses it, because there is no posture in which "every organisation" and
 * "every device" is the right answer for driving a marketplace session without a person present. A
 * wildcard is treated as an empty list, which admits nobody, and deploy.sh refuses it before boot.
 *
 * <p>This capability authorizes <b>acquisition only</b>. It names no publish, approval or execution path, and
 * the route it guards can mint nothing but a review-import launch for the organisation's NAVER account.
 */
@Component
public class UnattendedReviewExportProperties {

    private final boolean enabled;
    private final List<UUID> orgIds;
    private final List<UUID> deviceIds;

    public UnattendedReviewExportProperties(
            @Value("${sellerops.review-import.unattended.enabled:false}") boolean enabled,
            @Value("${sellerops.review-import.unattended.org-ids:}") String orgIds,
            @Value("${sellerops.review-import.unattended.device-ids:}") String deviceIds) {
        this.enabled = enabled;
        this.orgIds = parseIds(orgIds);
        this.deviceIds = parseIds(deviceIds);
    }

    /**
     * A wildcard is not widened here, it is dropped: {@code "*"} yields an empty list, and an empty list
     * admits nobody. Unparseable entries are dropped for the same reason — a typo must not become an
     * admission, and it must not stop the boot of a deployment that has this capability off.
     */
    private static List<UUID> parseIds(String csv) {
        if (csv == null || csv.isBlank() || csv.trim().equals("*")) {
            return List.of();
        }
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty() && !s.equals("*"))
                .map(UnattendedReviewExportProperties::parseOrNull).filter(java.util.Objects::nonNull).toList();
    }

    private static UUID parseOrNull(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** All three must hold. There is no order in which two of them are enough. */
    public boolean admits(UUID orgId, UUID deviceId) {
        return enabled && orgId != null && deviceId != null
                && orgIds.contains(orgId) && deviceIds.contains(deviceId);
    }

    public List<UUID> orgIds() {
        return orgIds;
    }

    public List<UUID> deviceIds() {
        return deviceIds;
    }
}
