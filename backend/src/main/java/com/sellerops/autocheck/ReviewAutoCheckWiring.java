package com.sellerops.autocheck;

import com.sellerops.responsibility.aside.ScheduledAsideJobService;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * <b>Where the setting is attached to the one event that can disprove its pause.</b>
 *
 * <p>The same knot {@code ReviewCatchUpWiring} ties, for the same reason and in its own file: the setting's
 * service is what the dispatcher asks for permission, so the dispatcher cannot depend on it in a constructor.
 * Two lanes now listen to one report and neither knows about the other — which is why the choke point keeps a
 * list rather than a slot.
 */
@Component
public class ReviewAutoCheckWiring {

    private final ScheduledAsideJobService jobs;
    private final ReviewAutoCheckService settings;

    public ReviewAutoCheckWiring(ScheduledAsideJobService jobs, ReviewAutoCheckService settings) {
        this.jobs = jobs;
        this.settings = settings;
    }

    @PostConstruct
    void attach() {
        jobs.addSettledListener(settings::noteSettled);
    }
}
