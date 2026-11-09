package com.sellerops.agent.llm.provider;

/**
 * <b>What came back, before any capability reads it</b> — the same facts whatever the vendor.
 *
 * @param content   the answer that may be read: non-null only when {@code failure} is null
 * @param said      what the vendor actually sent, kept whenever something came back (a truncated prefix, a refusal
 *                  sentence, an error body) — evidence, never an input
 * @param finish    the vendor's own stop word ({@code stop}, {@code end_turn}, {@code length}, {@code max_tokens}, …)
 * @param failure   null, or one closed word: {@code TRANSPORT}, {@code HTTP_<status>}, {@code REFUSAL},
 *                  {@code TRUNCATED}, {@code EMPTY}, {@code UNPARSEABLE}
 * @param errorCode the vendor's error code on an HTTP failure ({@code insufficient_quota}, {@code rate_limit_error}, …)
 *                  when it is a closed word, else null — content-free, it says whether to wait or to top up
 */
public record LlmReply(String content, String said, String finish, String failure, String errorCode) {

    public boolean answered() {
        return failure == null;
    }
}
