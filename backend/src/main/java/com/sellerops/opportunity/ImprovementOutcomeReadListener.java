package com.sellerops.opportunity;

import com.sellerops.reviewimport.ReviewSegmentIngestedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Reads the closed outcome windows after a review-import segment lands.
 *
 * <p><b>Why a listener here and not a call inside the issue memory's own pass.</b> The automatic lifecycle pass
 * lives in {@code reviewissue}, which must not know this package exists — {@code opportunity} depends on
 * {@code reviewissue}, and pointing that arrow both ways is the cycle {@code RepeatedIssueWorkspaceService} was
 * moved out of {@code reviewissue} to avoid. Both passes hang off the same event instead, which also means they
 * fail independently: an outcome reading that throws cannot stop the issue memory from coming current.
 *
 * <p><b>Why this event and not a schedule.</b> A window closes on a calendar day, but what makes it READABLE is
 * reviews having arrived — and this is the moment they did. Reading after an ingest means the numbers a seller
 * sees were computed over the data that produced them, and it adds no new timer to a product whose analysis is
 * already driven by collection. A window whose days passed with no ingest is simply read at the next one; the
 * verdict is anchored, so it does not change for having been read later.
 *
 * <p>AFTER_COMMIT, {@link Propagation#REQUIRES_NEW}, best-effort, exactly as
 * {@code ReviewIssueImportRefreshListener} is and for the same reason: the collection result is the durable
 * truth and this is a follow-on that may never roll it back.
 */
@Component
public class ImprovementOutcomeReadListener {

    private static final Logger log = LoggerFactory.getLogger(ImprovementOutcomeReadListener.class);

    private final ImprovementOutcomeService outcomes;

    public ImprovementOutcomeReadListener(ImprovementOutcomeService outcomes) {
        this.outcomes = outcomes;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onSegmentIngested(ReviewSegmentIngestedEvent event) {
        try {
            outcomes.read(event.orgId(), event.referenceDate());
        } catch (RuntimeException e) {
            // Best-effort. A verdict is anchored on frozen columns, so a reading that failed today produces the
            // same verdict tomorrow — nothing is lost by not retrying here.
            log.warn("Improvement outcome reading after ingest failed (best-effort; collection unaffected): {}",
                    e.toString());
        }
    }
}
