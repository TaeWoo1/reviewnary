package com.sellerops.agent.llm.provider;

import com.sellerops.agent.llm.AgentLlmTransport;
import java.net.URI;
import java.util.Map;

/**
 * <b>One vendor's spelling of a model call</b> — where it goes, how it authenticates, how the request is written and how
 * the answer is read. Nothing else: no flag, no key storage, no prompt, no parsing of the answer's content, and no
 * transport. The capability's generator still holds the transport and posts ({@code AgentDraftBoundaryTest}: only
 * generators post); it asks the wire for the bytes and for the reading.
 */
public interface LlmWire {

    LlmVendor vendor();

    URI endpoint();

    /** Auth and version headers. The key is never logged and never reaches a result object. */
    Map<String, String> headers(String apiKey);

    String body(LlmRequest request);

    LlmReply read(AgentLlmTransport.Response response);

    static LlmWire of(LlmVendor vendor) {
        return switch (vendor) {
            case OPENAI -> OpenAiChatWire.INSTANCE;
            case ANTHROPIC -> AnthropicMessagesWire.INSTANCE;
        };
    }

    static LlmWire of(String vendorName) {
        return of(LlmVendor.parse(vendorName));
    }
}
