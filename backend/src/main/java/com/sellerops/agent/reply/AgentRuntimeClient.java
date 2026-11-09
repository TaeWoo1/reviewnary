package com.sellerops.agent.reply;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * <b>The backend's one call to the agent runtime</b>: start the reply graph for one run, handing it the run's token
 * (2026-10-28, {@code docs/standing/reply_agent_v1.md} §2). The token goes in the request body to the runtime's internal
 * route and nowhere else; it is never logged. Unconfigured ({@code sellerops.agent-runtime.base-url} blank) = no run.
 *
 * <p><b>Required on an install</b> (owner, 2026-10-29: 「설치본에서 agent-runtime 누락이 기존 생성기로 조용히 대체되지 않게」).
 * {@code sellerops.agent-runtime.required=true} — the seller-PC launcher sets it — refuses to boot without an address, and
 * the review drafting paths then never fall back to the single-call drafter ({@link ReplyRunStarter#required()}).
 */
@Component
public class AgentRuntimeClient {

    private static final Logger log = LoggerFactory.getLogger(AgentRuntimeClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /**
     * The runtime's five model steps at sixty seconds each, plus the save and the finish — and short of the run token's ten
     * minutes. Less than this would fail runs the runtime is still finishing, after their calls were paid for.
     */
    static final Duration TIMEOUT = Duration.ofSeconds(330);

    private final String baseUrl;
    private final boolean required;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public AgentRuntimeClient(String baseUrl) {
        this(baseUrl, false);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AgentRuntimeClient(@Value("${sellerops.agent-runtime.base-url:}") String baseUrl,
                              @Value("${sellerops.agent-runtime.required:false}") boolean required) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.strip().replaceAll("/+$", "");
        this.required = required;
        if (required && this.baseUrl.isEmpty()) {
            throw new IllegalStateException("sellerops.agent-runtime.required is set but sellerops.agent-runtime.base-url "
                    + "is blank — an install drafts review replies only through the reply agent");
        }
    }

    /** Whether this deployment drafts review replies only through the runtime — no single-call drafter behind it. */
    public boolean isRequired() {
        return required;
    }

    public boolean isConfigured() {
        return !baseUrl.isEmpty();
    }

    /** What the graph reported: how the run ended, its category, the rules still failed, the saved version. */
    public record RunReport(String outcome, String category, String reason, Integer version) {
    }

    public Optional<RunReport> runReply(UUID runId, String token) {
        if (!isConfigured()) {
            return Optional.empty();
        }
        try {
            String body = MAPPER.createObjectNode().put("runId", runId.toString()).put("token", token).toString();
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/reply-runs"))
                    .timeout(TIMEOUT).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            com.sellerops.outbound.OutboundGate.check(request.uri(), com.sellerops.outbound.OutboundGate.Destination.AGENT_RUNTIME);
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.info("reply_agent runtime status={} run={}", response.statusCode(), runId);
                return Optional.empty();
            }
            JsonNode root = MAPPER.readTree(response.body());
            return Optional.of(new RunReport(root.path("outcome").asText(null), root.path("category").asText(null),
                    root.path("reason").asText(null), root.path("version").isInt() ? root.path("version").asInt() : null));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            log.info("reply_agent runtime unreachable run={} reason={}", runId, e.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
