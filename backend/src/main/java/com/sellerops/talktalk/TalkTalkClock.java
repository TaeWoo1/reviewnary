package com.sellerops.talktalk;

import java.time.Instant;
import org.springframework.stereotype.Component;

/** The lane's notion of now — a component so tests can move it without a global Clock bean. */
@Component
public class TalkTalkClock {

    private volatile Instant fixed;

    public Instant now() {
        Instant f = fixed;
        return f != null ? f : Instant.now();
    }

    /** Tests only. */
    public void set(Instant at) {
        this.fixed = at;
    }
}
