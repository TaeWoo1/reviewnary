package com.sellerops.coverage;

import com.sellerops.channel.Channel;
import com.sellerops.channel.ChannelRepository;
import com.sellerops.responsibility.aside.AsideRecipe;
import com.sellerops.responsibility.aside.ScheduledAsideJobRepository;
import com.sellerops.responsibility.aside.ScheduledAsideJobStatus;
import com.sellerops.reviewimport.ReviewImportSegmentAttemptRepository;
import com.sellerops.sync.SyncJob;
import com.sellerops.sync.SyncJobRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>Two facts about one channel × data type: when it was last actually read, and how the last attempt ended.</b>
 *
 * <p>One definition, because two surfaces ask. The coverage row answers it for the whole organisation, and the
 * collect-now readiness answers it for the row a seller is standing in front of — and if those two computed it
 * separately they would disagree eventually, with nobody able to say which was right. The 2026-10-07 defect was
 * exactly one field meaning two things; the fix is not worth repeating as two implementations.
 *
 * <p><b>Why both facts and not one.</b> «Read on 09-14» and «asked for a sign-in tonight» are both true at
 * once. A field that held only the first erased it the moment the second happened; a field that held only the
 * second could not tell a seller what they still have. They go together and they never overwrite each other.
 */
@Component
public class AcquisitionHistory {

    private final SyncJobRepository syncJobs;
    private final ReviewImportSegmentAttemptRepository acquisitions;
    private final ChannelRepository channels;
    /**
     * Screen reads. Optional so a context without the Aside substrate answers exactly as it did before this
     * class existed — absent means «this deployment has no screen-read lane», never «nothing was attempted».
     */
    private final ScheduledAsideJobRepository screenReads;

    @Autowired
    public AcquisitionHistory(SyncJobRepository syncJobs, ReviewImportSegmentAttemptRepository acquisitions,
                              ChannelRepository channels,
                              @Autowired(required = false) ScheduledAsideJobRepository screenReads) {
        this.syncJobs = syncJobs;
        this.acquisitions = acquisitions;
        this.channels = channels;
        this.screenReads = screenReads;
    }

    /** One attempt, reduced to what a surface says about it. Both members are null when none is on record. */
    public record Attempt(Instant at, AcquisitionAttemptOutcome outcome) {

        public static final Attempt NONE = new Attempt(null, null);

        public boolean present() {
            return at != null || outcome != null;
        }
    }

    /**
     * When this channel × type last <b>read rows</b> — success evidence, and nothing later removes it.
     *
     * <p>Two record shapes can prove it and the later of them is the fact: a connector pull writes a sync run,
     * a guided acquisition writes the review-import attempt that is its real provenance. Reading only the
     * first made a channel exported this morning report 「아직 확인한 적이 없어요」.
     */
    @Transactional(readOnly = true)
    public Instant lastSuccessAt(UUID orgId, UUID channelId, String dataType) {
        if (orgId == null || channelId == null || dataType == null) {
            return null;
        }
        Instant collected = syncJobs.findLatestSuccessfulRun(orgId, channelId, dataType)
                .map(SyncJob::getFinishedAt)
                .orElse(null);
        if (!"REVIEW".equals(dataType)) {
            return collected;
        }
        Instant acquired = acquisitions.lastSucceededAt(orgId, channelId);
        if (collected == null) {
            return acquired;
        }
        return acquired == null || acquired.isBefore(collected) ? collected : acquired;
    }

    /**
     * The most recent attempt on this channel × type, and how it ended.
     *
     * <p>A run that never asked the channel is not an attempt on it: a deployment with a connector switched
     * off would otherwise report «시도: 실패» for every channel, which is a fact about this process rather
     * than about the marketplace.
     */
    @Transactional(readOnly = true)
    public Attempt latestAttempt(UUID orgId, UUID channelId, String dataType) {
        if (orgId == null || channelId == null || dataType == null) {
            return Attempt.NONE;
        }
        Attempt pull = syncJobs.findLatestRunReachingChannel(orgId, channelId, dataType)
                .map(j -> new Attempt(j.getFinishedAt() != null ? j.getFinishedAt() : j.getCreatedAt(),
                        AcquisitionAttemptOutcome.of(j)))
                .orElse(Attempt.NONE);
        Attempt screen = latestScreenRead(orgId, channelId, dataType);
        if (!pull.present()) {
            return screen;
        }
        if (!screen.present() || screen.at() == null || pull.at() == null) {
            return pull.at() != null ? pull : screen;
        }
        return screen.at().isAfter(pull.at()) ? screen : pull;
    }

    /**
     * The newest settled screen read for this channel × type.
     *
     * <p>This lane leaves <b>no sync run at all</b>: the 2026-10-08 NAVER 리뷰 OPERATOR read settled
     * AUTH_REQUIRED and wrote nothing to {@code sync_jobs}, so a field fed only from there is blank exactly
     * when this is the lane being used.
     */
    private Attempt latestScreenRead(UUID orgId, UUID channelId, String dataType) {
        if (screenReads == null) {
            return Attempt.NONE;
        }
        Channel channel = channels.findById(channelId).orElse(null);
        if (channel == null) {
            return Attempt.NONE;
        }
        List<AsideRecipe> recipes = AsideRecipe.forChannelDataType(channel.getCode(), dataType);
        if (recipes.isEmpty()) {
            return Attempt.NONE;
        }
        return screenReads
                .findFirstByOrgIdAndRecipeInAndStatusOrderBySettledAtDesc(
                        orgId, recipes, ScheduledAsideJobStatus.SETTLED)
                .map(j -> new Attempt(j.getSettledAt(), AcquisitionAttemptOutcome.of(j.getOutcome())))
                .orElse(Attempt.NONE);
    }
}
