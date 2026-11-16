package com.sellerops.agent.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The per-call line carries the request's size and output cap, never its text (2026-11-15, evaluation metering). */
class LlmCallLogFieldsTest {

    @Test
    void sizeAndCapOnly() {
        String body = "{\"model\":\"m\",\"max_tokens\": 4000,\"messages\":[{\"role\":\"user\",\"content\":\"고객 문의 원문\"}]}";
        String fields = JdkAgentLlmTransport.requestFields(body);
        assertThat(fields).isEqualTo("reqChars=" + body.length() + " maxOut=4000").doesNotContain("고객");
        assertThat(JdkAgentLlmTransport.requestFields("{\"max_completion_tokens\":2400}")).endsWith("maxOut=2400");
        assertThat(JdkAgentLlmTransport.requestFields("{\"max_output_tokens\":800}")).endsWith("maxOut=800");
        assertThat(JdkAgentLlmTransport.requestFields("{}")).endsWith("maxOut=-1");
        assertThat(JdkAgentLlmTransport.requestFields(null)).isEqualTo("reqChars=-1 maxOut=-1");
    }
}
