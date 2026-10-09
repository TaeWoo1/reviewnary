package com.sellerops.coverage.catchup;

import com.sellerops.responsibility.aside.ScheduledAsideJobService;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * <b>Where the catch-up is attached to the one event that moves it.</b>
 *
 * <p>Separate from both classes on purpose. The orchestrator needs the job service to queue a child; the job
 * service needs to tell someone a child settled. Wiring that as two constructor dependencies would be a cycle,
 * and making either class know the other's package would give the system's one choke point an opinion about a
 * lane. So the knot is tied here, in a file whose whole content is the knot.
 */
@Component
public class ReviewCatchUpWiring {

    private final ScheduledAsideJobService jobs;
    private final ReviewCatchUpOrchestrator orchestrator;

    public ReviewCatchUpWiring(ScheduledAsideJobService jobs, ReviewCatchUpOrchestrator orchestrator) {
        this.jobs = jobs;
        this.orchestrator = orchestrator;
    }

    @PostConstruct
    void attach() {
        jobs.addSettledListener(orchestrator::advance);
    }
}
