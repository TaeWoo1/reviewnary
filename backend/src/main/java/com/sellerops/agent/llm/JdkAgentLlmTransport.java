package com.sellerops.agent.llm;

import com.sellerops.outbound.LlmSendGate;
import com.sellerops.outbound.OutboundGate;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Production {@link AgentLlmTransport} over the JDK HTTP client, in the shape
 * {@code JdkLlmHttpClient} already established for triage.
 *
 * <p>A failure becomes {@code status 0} and a marker rather than an exception, because the caller's
 * job is to turn it into a fail-closed "no draft" and an exception escaping here would have to be
 * caught somewhere less obvious to do the same thing.
 *
 * <p>The marker carries the exception's <b>type</b> and not its text: a timeout message can contain
 * the URI, and some clients put request material in it.
 *
 * <p>On a seller's PC the body first passes {@link LlmSendGate}: identifiers are removed in place, and a held body
 * is not sent — it comes back as {@code status 0} with the finding labels, which every caller already turns into
 * its fail-closed "no draft".
 */
public class JdkAgentLlmTransport implements AgentLlmTransport {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(JdkAgentLlmTransport.class);

    /**
     * One metadata line per call, whichever capability made it (2026-11-15): the host, the status and the vendor's
     * token counts — never a path, a prompt or an answer. Every capability logs its own line too, but not all of them,
     * so a cost cap that counts calls counts these.
     */
    static void logCall(URI uri, Response response, String jsonBody) {
        log.info("llm_call host={} status={} {} {}", uri == null ? "-" : uri.getHost(), response.status(),
                AgentLlmCallMetrics.of(response).toLogFields(), requestFields(jsonBody));
    }

    private static final java.util.regex.Pattern MAX_OUT =
            java.util.regex.Pattern.compile("\"(?:max_tokens|max_completion_tokens|max_output_tokens)\"\\s*:\\s*(\\d+)");

    /**
     * The request's size and output cap — two numbers, never its text (2026-11-15): a dry run on the fake model measures
     * how large each real call's input will be, and an evaluation prices a call whose usage was not reported at its cap.
     */
    static String requestFields(String jsonBody) {
        if (jsonBody == null) {
            return "reqChars=-1 maxOut=-1";
        }
        java.util.regex.Matcher m = MAX_OUT.matcher(jsonBody);
        return "reqChars=" + jsonBody.length() + " maxOut=" + (m.find() ? m.group(1) : "-1");
    }

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private final java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    @Override
    public Response post(URI uri, Map<String, String> headers, String jsonBody) {
        return send(uri, headers, jsonBody, LlmSendGate.Images.NONE);
    }

    @Override
    public Response postProductImages(URI uri, Map<String, String> headers, String jsonBody) {
        return send(uri, headers, jsonBody, LlmSendGate.Images.PRODUCT);
    }

    private Response send(URI uri, Map<String, String> headers, String jsonBody, LlmSendGate.Images images) {
        OutboundGate.check(uri, OutboundGate.Destination.LLM);
        LlmSendGate.Prepared prepared = LlmSendGate.prepare(jsonBody, images);
        if (!prepared.sendable()) {
            Response held = new Response(0, prepared.heldMarker(), 0L);
            logCall(uri, held, jsonBody);
            return held;
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json;charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(prepared.body(), StandardCharsets.UTF_8));
        headers.forEach(builder::header);
        long started = System.nanoTime();
        try {
            HttpResponse<String> response =
                    client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            Response out = new Response(response.statusCode(), response.body(), elapsedMs(started));
            logCall(uri, out, jsonBody);
            return out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Response out = new Response(0, "interrupted", elapsedMs(started));
            logCall(uri, out, jsonBody);
            return out;
        } catch (Exception e) {
            Response out = new Response(0, e.getClass().getSimpleName(), elapsedMs(started));
            logCall(uri, out, jsonBody);
            return out;
        }
    }

    /** A failed call is timed too: a slow failure and a fast one are different problems. */
    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }
}
