package com.sellerops.auth.runtoken;

import com.sellerops.auth.social.AuthCodes;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>Mints, checks and closes the token one reply-agent run uses</b> (owner, 2026-10-28, {@code reply_agent_v1.md} §2).
 *
 * <p>The token is not a system principal: it names one run, one org and one review or inquiry, opens only the reply-run
 * routes ({@link ReplyRunTokenAuthFilter#ALLOWED}), lives {@link #LIFETIME}, and stops working the moment the run is
 * finished. Only its hash is stored; the runtime carries the token in that run's requests and keeps it nowhere.
 */
@Service
public class ReplyRunTokenService {

    public static final String PREFIX = "rvr_";

    /** Long enough for judge · gather · draft · critique · revise · save on a slow model; short enough to be one run. */
    public static final Duration LIFETIME = Duration.ofMinutes(10);

    private final ReplyAgentRunRepository runs;
    private final Clock clock;

    @Autowired
    public ReplyRunTokenService(ReplyAgentRunRepository runs) {
        this(runs, Clock.systemUTC());
    }

    ReplyRunTokenService(ReplyAgentRunRepository runs, Clock clock) {
        this.runs = runs;
        this.clock = clock;
    }

    /** A run just created, and the token it will use — the token appears here once and is never stored. */
    public record Minted(UUID runId, String token, Instant expiresAt) {
    }

    @Transactional
    public Minted mint(UUID orgId, String subjectKind, UUID subjectId, String trigger) {
        return mint(orgId, subjectKind, subjectId, trigger, null);
    }

    /** The same, naming the seller who pressed for it ({@code trigger = SELLER}). */
    @Transactional
    public Minted mint(UUID orgId, String subjectKind, UUID subjectId, String trigger, UUID startedBy) {
        String token = PREFIX + AuthCodes.newCode();
        ReplyAgentRun run = new ReplyAgentRun();
        run.setOrgId(orgId);
        run.setStartedBy(startedBy);
        run.setSubjectKind(subjectKind);
        run.setSubjectId(subjectId);
        run.setTriggerKind(trigger);
        run.setTokenHash(AuthCodes.hash(token));
        run.setExpiresAt(clock.instant().plus(LIFETIME));
        ReplyAgentRun saved = runs.save(run);
        return new Minted(saved.getId(), token, saved.getExpiresAt());
    }

    public static boolean looksLikeRunToken(String bearer) {
        return bearer != null && bearer.startsWith(PREFIX);
    }

    /** The run this token opens, if it is still open: known, not expired, not finished. */
    @Transactional(readOnly = true)
    public Optional<ReplyAgentRun> authenticate(String token) {
        if (!looksLikeRunToken(token)) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        return runs.findByTokenHash(AuthCodes.hash(token))
                .filter(run -> run.getFinishedAt() == null && run.getExpiresAt().isAfter(now));
    }

    /** Close a run: its token stops working, whatever it was for. Idempotent — a closed run keeps its first outcome. */
    @Transactional
    public void finish(UUID runId, String outcome) {
        runs.findById(runId).filter(run -> run.getFinishedAt() == null).ifPresent(run -> {
            run.setFinishedAt(clock.instant());
            run.setOutcome(outcome);
            runs.save(run);
        });
    }
}
