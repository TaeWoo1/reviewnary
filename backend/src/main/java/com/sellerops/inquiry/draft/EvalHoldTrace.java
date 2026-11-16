package com.sellerops.inquiry.draft;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sellerops.agent.llm.AgentDraftGenerator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * <b>Why an evaluation draft was refused, kept for the evaluator — and nowhere else</b> (2026-11-16 review B172 · §7.48:
 * q14 S1's held draft was not stored, so its cause stayed a guess).
 *
 * <p>Off unless BOTH are set: {@code sellerops.dev.fake-channel=true} (the synthetic board the evaluation runs on — no
 * real channel is connected) and {@code sellerops.dev.eval-hold-trace-dir} (a directory the evaluation names). Then each
 * refusal and hold of an inquiry draft appends one JSON line to {@code hold-trace.jsonl} there, file mode 600. The fields,
 * exactly: {@code at} (the time), {@code workItemId} (SellerOps' own internal id of the synthetic work item — the key that
 * ties a line to its evaluation row), {@code attempt}, {@code rewriteBecause} (why the attempt was a rewrite),
 * {@code stage}, {@code codes}, {@code matched} (what the check matched or did not confirm), {@code unavailable},
 * {@code draft} (the refused text), {@code checkedAgainst} (each passage's scope, heading, and — since §7.57 — the
 * sha256 and length of the exact text the drafter and the check were given, its appended mark line included) and
 * {@code scope} (the need list with the received and product lines, the conditions the check was given). Not written:
 * passage text, passage or source ids, and any seller, customer, order or channel id (2026-11-16 review §7.50 corrected
 * an earlier 「no ids」 summary — the internal work item id is there on purpose). Nothing is written to the application log,
 * and a write that fails changes nothing the composer does.
 */
@Component
public class EvalHoldTrace {

    private static final Logger log = LoggerFactory.getLogger(EvalHoldTrace.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    static final String FILE = "hold-trace.jsonl";

    private final Path file;

    public EvalHoldTrace(@Value("${sellerops.dev.fake-channel:false}") boolean fakeChannel,
                         @Value("${sellerops.dev.eval-hold-trace-dir:}") String dir) {
        this.file = fakeChannel && dir != null && !dir.isBlank() ? Path.of(dir.strip()).resolve(FILE) : null;
    }

    public boolean enabled() {
        return file != null;
    }

    /**
     * One refusal or hold.
     *
     * @param stage    where it stopped: GUARD · STYLE · BLANK · COMPLETION · UNREQUESTED_ASK · CLAIM_CHECK · HELD_FOR_BUDGET
     * @param codes    the reason codes (guard kinds, or the check's refusal kind)
     * @param matched  what the check matched or did not confirm, as the draft wrote it
     */
    public void record(UUID workItemId, int attempt, String rewriteBecause, String stage, List<String> codes,
                       List<String> matched, String unavailable, String draft, List<AgentDraftGenerator.Passage> shown) {
        record(workItemId, attempt, rewriteBecause, stage, codes, matched, unavailable, draft, shown, null);
    }

    /** The same, with the scope block the drafter and the check were given. */
    public void record(UUID workItemId, int attempt, String rewriteBecause, String stage, List<String> codes,
                       List<String> matched, String unavailable, String draft, List<AgentDraftGenerator.Passage> shown,
                       String scope) {
        if (file == null) {
            return;
        }
        try {
            ObjectNode line = MAPPER.createObjectNode();
            line.put("at", Instant.now().toString());
            line.put("workItemId", workItemId == null ? null : workItemId.toString());
            line.put("attempt", attempt);
            line.put("rewriteBecause", rewriteBecause);
            line.put("stage", stage);
            ArrayNode c = line.putArray("codes");
            (codes == null ? List.<String>of() : codes).forEach(c::add);
            ArrayNode m = line.putArray("matched");
            (matched == null ? List.<String>of() : matched).forEach(m::add);
            line.put("unavailable", unavailable);
            line.put("draft", draft);
            ArrayNode grounds = line.putArray("checkedAgainst");
            for (AgentDraftGenerator.Passage p : shown == null ? List.<AgentDraftGenerator.Passage>of() : shown) {
                ObjectNode g = grounds.addObject();
                g.put("scope", p.scopeLabel());
                g.put("heading", p.heading());
                g.put("textSha256", com.sellerops.inquiry.decision.EvalDecisionTrace.sha256(p.text()));
                g.put("textChars", p.text() == null ? -1 : p.text().length());
            }
            line.put("scope", scope);
            Files.createDirectories(file.getParent());
            boolean fresh = !Files.exists(file);
            Files.writeString(file, MAPPER.writeValueAsString(line) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            if (fresh) {
                try {
                    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
                } catch (UnsupportedOperationException ignored) {
                    // not a POSIX file system: the directory the evaluation named is its own to protect
                }
            }
        } catch (IOException | RuntimeException e) {
            log.warn("eval_hold_trace_unwritten reason={}", e.getClass().getSimpleName());
        }
    }
}
