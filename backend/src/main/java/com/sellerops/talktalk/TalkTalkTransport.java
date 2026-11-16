package com.sellerops.talktalk;

/**
 * How one request reaches TalkTalk's send API. {@link #send} never throws: every way it can end is an {@link Outcome},
 * and an outcome that cannot be read is UNKNOWN — never a success and never retried by the caller.
 */
public interface TalkTalkTransport {

    TalkTalkConnection.Mode mode();

    Outcome send(String sendKey, String jsonBody);

    /**
     * @param status      FAKE_RECORDED · API_ACCEPTED · FAILED · UNKNOWN · BLOCKED
     * @param resultCode  the send API's {@code resultCode} when one was read
     * @param failureCode why it is not accepted, a label only — never the response body
     */
    record Outcome(TalkTalkOutbound.Status status, String resultCode, String failureCode) {
    }
}
