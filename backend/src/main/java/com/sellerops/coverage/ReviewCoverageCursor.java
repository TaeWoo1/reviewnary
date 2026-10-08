package com.sellerops.coverage;

import com.sellerops.channel.Channel;
import com.sellerops.channel.ChannelRepository;
import com.sellerops.responsibility.IdentityVerdict;
import com.sellerops.responsibility.SourceCompleteness;
import com.sellerops.responsibility.aside.AsideJobOutcome;
import com.sellerops.responsibility.aside.AsideRecipe;
import com.sellerops.responsibility.aside.ScheduledAsideJob;
import com.sellerops.responsibility.aside.ScheduledAsideJobRepository;
import com.sellerops.reviewimport.ReviewImportSegment;
import com.sellerops.reviewimport.ReviewImportSegmentRepository;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>Where the reviews of one channel are actually read through — from evidence only.</b>
 *
 * <p>Two lanes leave proof of having read a period, and this reads both. Neither is copied into a third table:
 * a coverage number kept beside the thing it summarises drifts from it, and «one field meaning two things» is
 * the defect this whole package exists because of.
 *
 * <ul>
 *   <li><b>The guided period import.</b> {@code review_import_segment} already carries exactly this: a date
 *   range and a {@code coverage_state} that says whether its rows reconciled. Those segments are why this store
 *   can claim 07-01 … 09-02 at all, and nothing new had to be invented for them.</li>
 *   <li><b>The screen read.</b> It states the period it saw, which is now kept on the job
 *   ({@code window_start} / {@code window_end}).</li>
 * </ul>
 *
 * <h2>What may advance a boundary</h2>
 *
 * <p>A screen read counts only when all four hold, and each one is a way the 2026-10-08 run could have been
 * wrong without looking wrong:
 *
 * <ol>
 *   <li><b>{@code OBSERVED}</b> — AUTH_REQUIRED and SURFACE_UNREADABLE read nothing.</li>
 *   <li><b>the store was proved</b> ({@code MATCH}) — rows from a page nobody could place are not this
 *   seller's days.</li>
 *   <li><b>a period is on the row</b> — a read that cannot say what it covered has not covered anything that
 *   can be recorded.</li>
 *   <li><b>the read did not reach its own ceiling</b> — 45 rows under 500 excludes 「더 있을 수 있음」; 500
 *   under 500 does not, and a boundary moved on an ambiguous page is the quiet version of losing data.</li>
 * </ol>
 *
 * <p>Nothing here synthesises a date. A channel with no evidence returns {@link ReviewCoverage#NONE}, and the
 * planner refuses to plan from it rather than starting «a year ago» — a guessed start would be indistinguishable,
 * downstream, from a measured one.
 */
@Component
public class ReviewCoverageCursor {

    /** The seller's days. A boundary kept in UTC moves by nine hours crossing this seam, twice. */
    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final ReviewImportSegmentRepository segments;
    private final ChannelRepository channels;
    /** Optional for the same reason {@code AcquisitionHistory} keeps it optional — a deployment may have no lane. */
    private final ScheduledAsideJobRepository screenReads;

    @Autowired
    public ReviewCoverageCursor(ReviewImportSegmentRepository segments, ChannelRepository channels,
                                @Autowired(required = false) ScheduledAsideJobRepository screenReads) {
        this.segments = segments;
        this.channels = channels;
        this.screenReads = screenReads;
    }

    /** The coverage answer for one channel's reviews, as of the seller's today. */
    @Transactional(readOnly = true)
    public ReviewCoverage of(UUID orgId, UUID channelId, LocalDate asOf) {
        if (orgId == null || channelId == null || asOf == null) {
            return ReviewCoverage.NONE;
        }
        List<CoveredWindow> evidence = new ArrayList<>();
        for (ReviewImportSegment s : segments.findCoveredForChannel(orgId, channelId)) {
            evidence.add(new CoveredWindow(s.getSegmentStart(), s.getSegmentEnd(),
                    CoveredWindow.CoverageSource.GUIDED_IMPORT));
        }
        evidence.addAll(screenReadWindows(orgId, channelId));
        return ReviewCoverage.of(evidence, asOf);
    }

    /**
     * Whether one settled screen read proved the days it names.
     *
     * <p>Package-visible and static so the rule can be tested as a rule, without a database behind it.
     */
    static boolean provesItsWindow(ScheduledAsideJob job) {
        if (job.getOutcome() != AsideJobOutcome.OBSERVED
                || job.getIdentityVerdict() != IdentityVerdict.MATCH
                || job.getWindowStart() == null || job.getWindowEnd() == null) {
            return false;
        }
        Integer observed = job.getObservedCount();
        if (observed == null) {
            return false;
        }
        // <b>The verdict the delivery took, not a comparison re-done here.</b>
        //
        // This used to be `observed < observedCapacity` — our own ceiling, and nothing else. That test is real
        // and it survives, inside the verdict; but on its own it says only that one way of losing rows did not
        // happen. It cannot see a list set to 50 rows per page, and it cannot see a row model that loaded 42 of
        // 46. On 2026-10-08 it called seven days covered on exactly that basis.
        //
        // NaverReviewObservationService.incompleteReason is where the four facts are weighed, because that is
        // where the screen's own statements arrive and can be stored beside the answer. A read whose
        // completeness was never judged — a helper older than that evidence — leaves this null and moves no
        // boundary. Fail closed: the rows are kept either way.
        return job.getDeliveryCompleteness() == SourceCompleteness.COMPLETE;
    }

    private List<CoveredWindow> screenReadWindows(UUID orgId, UUID channelId) {
        if (screenReads == null) {
            return List.of();
        }
        Channel channel = channels.findById(channelId).orElse(null);
        if (channel == null) {
            return List.of();
        }
        List<AsideRecipe> recipes = AsideRecipe.forChannelDataType(channel.getCode(), "REVIEW");
        if (recipes.isEmpty()) {
            return List.of();
        }
        List<CoveredWindow> out = new ArrayList<>();
        for (ScheduledAsideJob job : screenReads.findWindowedReads(orgId, recipes)) {
            if (provesItsWindow(job)) {
                out.add(new CoveredWindow(job.getWindowStart(), job.getWindowEnd(),
                        CoveredWindow.CoverageSource.SCREEN_READ));
            }
        }
        return out;
    }
}
