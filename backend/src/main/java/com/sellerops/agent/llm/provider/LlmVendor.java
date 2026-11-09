package com.sellerops.agent.llm.provider;

import java.util.Locale;

/**
 * <b>Which model vendor a capability speaks to</b> (owner, 2026-10-29: 「백엔드의 공통 모델 연결 구조를 완성한다」).
 *
 * <p>A capability keeps its own flag, key, prompt, payload floor and parser; the vendor decides only how the request is
 * written and how the answer is read ({@link LlmWire}). A blank setting is OpenAI, the deployment's long-standing
 * default. <b>An unknown name refuses</b> rather than quietly becoming OpenAI — the day a third vendor is named in a
 * setting before its wire exists, the backend must not send that capability's payload to a vendor nobody chose.
 */
public enum LlmVendor {
    OPENAI,
    ANTHROPIC;

    public static LlmVendor parse(String name) {
        if (name == null || name.isBlank()) {
            return OPENAI;
        }
        try {
            return valueOf(name.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown model vendor: " + name.strip());
        }
    }
}
