package com.sellerops.intakealert;

/**
 * The default sender: the message is kept in this server's mock inbox (the delivery row itself) and nothing is sent.
 * It reports {@code MOCK_RECORDED}, never {@code ACCEPTED} — the screen labels it 「카톡 발송 예시 · 모의 알림」 and never
 * says it reached KakaoTalk.
 */
public final class MockIntakeAlertSender implements IntakeAlertSender {

    @Override
    public IntakeAlertDelivery.Mode mode() {
        return IntakeAlertDelivery.Mode.MOCK;
    }

    @Override
    public boolean needsNumber() {
        return false;
    }

    @Override
    public Outcome send(OutgoingAlert alert) {
        return Outcome.mockRecorded();
    }
}
