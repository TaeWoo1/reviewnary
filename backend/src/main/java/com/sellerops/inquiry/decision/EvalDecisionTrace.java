package com.sellerops.inquiry.decision;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * <b>What a decision call sent and, when it failed, what came back — for the evaluator, and nowhere else</b> (2026-11-16
 * review B178 · §7.55: the jar h run's first plan call came back UNPARSEABLE, and nothing kept which of the parser's
 * refusals it was, or what the model wrote).
 *
 * <p>Off unless BOTH are set, exactly as {@code EvalHoldTrace}: {@code sellerops.dev.fake-channel=true} (the synthetic
 * board an evaluation runs on — no real channel is connected) and {@code sellerops.dev.eval-hold-trace-dir} (the
 * evaluation's diagnostics directory). Then every plan and judge call appends one JSON line to
 * {@code decision-trace.jsonl} there, file mode 600. The fields, exactly: {@code at}, {@code phase} (plan · judge),
 * {@code failure} (the word the caller received, unchanged), {@code detail} (which of the parser's refusals it was),
 * {@code finish}, {@code inTok}, {@code outTok}, {@code requestSha256} and {@code requestChars} (the request body as sent —
 * the API key travels in a header, never in the body), {@code responseSha256} and {@code responseChars}, and — only when
 * the call failed — {@code response}: what the model wrote (or, for a refusal or a cut-off, what the vendor said).
 * Nothing about a seller, a customer, an order or a channel is written beyond what the synthetic question carries, and
 * nothing is written to the application log; a write that fails changes nothing the decision does.
 */
@Component
public class EvalDecisionTrace {

    private static final Logger log = LoggerFactory.getLogger(EvalDecisionTrace.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    static final String FILE = "decision-trace.jsonl";

    private final Path file;

    public EvalDecisionTrace(@Value("${sellerops.dev.fake-channel:false}") boolean fakeChannel,
                             @Value("${sellerops.dev.eval-hold-trace-dir:}") String dir) {
        this.file = fakeChannel && dir != null && !dir.isBlank() ? Path.of(dir.strip()).resolve(FILE) : null;
    }

    public boolean enabled() {
        return file != null;
    }

    /** One call. {@code response} is kept only when {@code failure} is not null. */
    public void record(String phase, String requestBody, String failure, String detail, String finish, long inTok,
                       long outTok, String response) {
        if (file == null) {
            return;
        }
        try {
            ObjectNode line = MAPPER.createObjectNode();
            line.put("at", Instant.now().toString());
            line.put("phase", phase);
            line.put("failure", failure);
            line.put("detail", detail);
            line.put("finish", finish);
            line.put("inTok", inTok);
            line.put("outTok", outTok);
            line.put("requestSha256", sha256(requestBody));
            line.put("requestChars", requestBody == null ? -1 : requestBody.length());
            line.put("responseSha256", sha256(response));
            line.put("responseChars", response == null ? -1 : response.length());
            if (failure != null) {
                line.put("response", response);
            }
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
            log.warn("eval_decision_trace_unwritten reason={}", e.getClass().getSimpleName());
        }
    }

    public static String sha256(String text) {
        if (text == null) {
            return null;
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
