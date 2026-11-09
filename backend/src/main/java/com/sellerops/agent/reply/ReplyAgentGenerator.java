package com.sellerops.agent.reply;

import com.sellerops.agent.llm.AgentLlmCallMetrics;
import com.sellerops.agent.llm.AgentLlmTransport;
import com.sellerops.agent.llm.provider.LlmReply;
import com.sellerops.agent.llm.provider.LlmWire;
import com.sellerops.agent.llm.operator.AgentLlmWireFormat;
import java.util.Optional;

/**
 * The reply agent's one vendor call — the only holder of the transport for this capability
 * ({@code AgentDraftBoundaryTest}). It sends what {@link ReplyAgentPrompt} rendered and returns the assistant text with
 * the call's token counts; reading the JSON inside is the service's job.
 */
public class ReplyAgentGenerator {


    private final AgentLlmTransport http;
    private final AgentLlmWireFormat.Vendor vendor;
    private final String modelId;
    private final String apiKey;
    private final int maxOutputTokens;
    private final String reasoningEffort;
    private final String version;

    public ReplyAgentGenerator(AgentLlmTransport http, AgentLlmWireFormat.Vendor vendor, String modelId, String apiKey,
                               int maxOutputTokens, String reasoningEffort, String standardVersion) {
        this.http = http;
        this.vendor = vendor;
        this.modelId = modelId;
        this.apiKey = apiKey;
        this.maxOutputTokens = maxOutputTokens;
        this.reasoningEffort = reasoningEffort;
        this.version = "reply-agent/v1+" + vendor.name().toLowerCase() + ":" + modelId + "+"
                + ReplyAgentPrompt.PROMPT_VERSION + "+" + standardVersion + "+out" + maxOutputTokens
                + (reasoningEffort == null ? "" : "+effort:" + reasoningEffort);
    }

    public String version() {
        return version;
    }

    /** The assistant text, or why there is none; and the tokens the vendor reported (null when it did not). */
    public record Result(Optional<String> text, String reason, Integer inputTokens, Integer outputTokens) {
    }

    public Result call(String system, String user) {
        AgentLlmTransport.Response response = http.post(vendor.endpoint(), AgentLlmWireFormat.headers(vendor, apiKey),
                AgentLlmWireFormat.body(vendor, modelId, system, user, maxOutputTokens, reasoningEffort));
        if (response.status() == 0) {
            return new Result(Optional.empty(), "transport", null, null);
        }
        LlmReply reply = LlmWire.of(vendor.vendor()).read(response);
        if (!response.ok()) {
            // The vendor's own error code (e.g. insufficient_quota vs rate_limit_exceeded) — a closed, content-free word
            // that says whether to wait or to top up. Nothing else from the body is kept.
            return new Result(Optional.empty(), "http:" + response.status()
                    + (reply.errorCode() == null ? "" : ":" + reply.errorCode()), null, null);
        }
        AgentLlmCallMetrics metrics = AgentLlmCallMetrics.of(response);
        Integer in = metrics.promptTokens() < 0 ? null : metrics.promptTokens();
        Integer out = metrics.completionTokens() < 0 ? null : metrics.completionTokens();
        if (reply.answered()) {
            return new Result(Optional.of(reply.content()), "ok", in, out);
        }
        return new Result(Optional.empty(), switch (reply.failure()) {
            case "TRUNCATED" -> "budget_exhausted";
            case "REFUSAL" -> "refusal";
            case "UNPARSEABLE" -> "unreadable_envelope";
            default -> "no_message_text";
        }, in, out);
    }
}
