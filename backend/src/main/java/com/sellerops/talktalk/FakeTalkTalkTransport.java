package com.sellerops.talktalk;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>The fake.</b> Nothing leaves this server: a send is kept in memory and answered {@code FAKE_RECORDED}, a status
 * no screen, count or report may read as 「톡톡 접수」. Tests may script the next outcomes to drive failure paths.
 */
public class FakeTalkTalkTransport implements TalkTalkTransport {

    private static final Logger log = LoggerFactory.getLogger(FakeTalkTalkTransport.class);

    public record Recorded(String jsonBody) {
        @Override
        public String toString() {
            return "Recorded(<body " + jsonBody.length() + " chars>)";
        }
    }

    private final List<Recorded> recorded = new ArrayList<>();
    private final Deque<Outcome> scripted = new ArrayDeque<>();

    @Override
    public TalkTalkConnection.Mode mode() {
        return TalkTalkConnection.Mode.FAKE;
    }

    @Override
    public synchronized Outcome send(String sendKey, String jsonBody) {
        recorded.add(new Recorded(jsonBody));
        while (recorded.size() > 200) {
            recorded.remove(0);
        }
        Outcome next = scripted.pollFirst();
        log.info("talktalk_fake_send recorded=true scripted={}", next != null);
        return next != null ? next : new Outcome(TalkTalkOutbound.Status.FAKE_RECORDED, null, null);
    }

    public synchronized List<Recorded> recorded() {
        return List.copyOf(recorded);
    }

    public synchronized void script(Outcome outcome) {
        scripted.addLast(outcome);
    }

    public synchronized void clear() {
        recorded.clear();
        scripted.clear();
    }
}
