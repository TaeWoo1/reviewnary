package com.sellerops.agent.llm.operator;

import com.sellerops.agent.llm.provider.LlmRequest;
import com.sellerops.agent.llm.provider.LlmVendor;
import com.sellerops.agent.llm.provider.LlmWire;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;

/**
 * The vendor wire format, and NOTHING about what is being asked.
 *
 * <p>Factored out because the Operator adds two more LLM capabilities (plan, judge) beside the
 * existing draft one, and each must keep its OWN payload floor, its own prompt and its own
 * assertable {@code requestBody}. Duplicating the Anthropic/OpenAI envelope three times would have
 * meant three places to get {@code max_tokens} vs {@code max_completion_tokens} wrong; folding the
 * prompts in here would have meant one method holding three payload floors, which is exactly the
 * merge {@code AgentDraftBoundaryTest} exists to prevent.
 *
 * <p>So the split is: this class knows how to phrase a system turn and a user turn for a vendor, and
 * knows nothing about inquiries, plans or findings. Each generator still owns the strings it passes
 * and still builds its own request body, so each payload floor is still asserted on its own bytes.
 *
 * <p>Pure: no transport, no key, no clock. The API key lives in the header map a generator builds.
 */
public final class AgentLlmWireFormat {

    /** Which wire format to speak — the shared model connection's vendor, under the name these callers use. */
    public enum Vendor {
        ANTHROPIC(LlmVendor.ANTHROPIC),
        OPENAI(LlmVendor.OPENAI);

        private final LlmVendor vendor;

        Vendor(LlmVendor vendor) {
            this.vendor = vendor;
        }

        public java.net.URI endpoint() {
            return LlmWire.of(vendor).endpoint();
        }

        public LlmVendor vendor() {
            return vendor;
        }

        /**
         * The configured name; blank is OpenAI. <b>An unknown name refuses</b> (2026-10-29) — it used to become OpenAI,
         * which would send a capability's payload to a vendor nobody chose the day a third one is named.
         */
        public static Vendor of(String name) {
            return LlmVendor.parse(name) == LlmVendor.ANTHROPIC ? ANTHROPIC : OPENAI;
        }
    }

    private AgentLlmWireFormat() {
    }

    /**
     * A chat request carrying exactly one system turn and one user turn.
     *
     * <p>{@code json_object} response format is requested from OpenAI as the draft capability does:
     * it guarantees SYNTAX, never the schema, so every caller still validates every field itself.
     */
    public static String body(Vendor vendor, String modelId, String system, String user,
                              int maxOutputTokens, String reasoningEffort) {
        return body(vendor, modelId, system, user, maxOutputTokens, reasoningEffort, null);
    }

    /**
     * The same body, with the caller's own response format.
     *
     * <p>Most capabilities ask for {@code json_object} and read the answer strictly themselves. One asks for
     * <b>strict Structured Outputs</b> — a json_schema the vendor enforces — because its contract is a closed
     * vocabulary and «the model may not say PROCEDURE» is worth making structural rather than checking afterwards.
     * A null format keeps the historical bytes exactly, so nothing that did not ask for this changed.
     */
    public static String body(Vendor vendor, String modelId, String system, String user,
                              int maxOutputTokens, String reasoningEffort, ObjectNode responseFormat) {
        return LlmWire.of(vendor.vendor()).body(LlmRequest.of(modelId, system, user, maxOutputTokens, reasoningEffort,
                LlmRequest.Format.fromOpenAiResponseFormat(responseFormat)));
    }

    /**
     * The configured effort, in Anthropic's spelling. Kept for callers that write their own Anthropic body; the shared
     * wire ({@code AnthropicMessagesWire}) applies the same rule.
     */
    public static void putAnthropicEffort(ObjectNode root, String reasoningEffort) {
        String effort = com.sellerops.agent.llm.provider.AnthropicMessagesWire.effortOf(reasoningEffort);
        if (effort != null) {
            root.putObject("output_config").put("effort", effort);
        }
    }

    /** Auth headers for the vendor. The key is never logged and never reaches a result object. */
    public static Map<String, String> headers(Vendor vendor, String apiKey) {
        return LlmWire.of(vendor.vendor()).headers(apiKey);
    }
}
