package com.sellerops.talktalk;

import java.util.UUID;

/**
 * Told when the confirmed shipping guidance of an org changed (confirmed or taken down), inside the same transaction.
 * The guidance is the only copy of 출고 마감 and 휴무 (owner decision 2026-11-17); whoever writes those values into
 * another place listens here instead of keeping a second copy.
 */
public interface TalkTalkGuidanceListener {

    void guidanceChanged(UUID orgId);
}
