package com.sellerops.talktalk;

import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public webhook TalkTalk calls (lane contract §3). Unauthenticated by design — the protocol documents no request
 * signature — so the path token, the connection state and (when enforced) the documented source ranges are the
 * boundary. The body is read from the stream with a hard cap <b>before</b> anything is allocated for it, so a
 * declared or chunked body larger than the cap is refused without being buffered. The answer is an empty 200 only
 * after the event row has committed; it never carries a reply.
 */
@RestController
public class TalkTalkWebhookController {

    private static final Logger log = LoggerFactory.getLogger(TalkTalkWebhookController.class);

    private final TalkTalkIntakeService intake;
    private final TalkTalkSettings settings;

    public TalkTalkWebhookController(TalkTalkIntakeService intake, TalkTalkSettings settings) {
        this.intake = intake;
        this.settings = settings;
    }

    @PostMapping("/api/talktalk/webhook/{token}")
    public ResponseEntity<Void> receive(@PathVariable String token, HttpServletRequest request) {
        int cap = settings.maxBodyBytes();
        long declared = request.getContentLengthLong();
        if (declared > cap) {
            return ResponseEntity.status(413).build();
        }
        byte[] body;
        try {
            body = readBounded(request.getInputStream(), cap);
        } catch (IOException e) {
            return ResponseEntity.status(400).build();
        }
        if (body == null) {
            return ResponseEntity.status(413).build();
        }
        TalkTalkIntakeService.Result result;
        try {
            result = intake.accept(token, request.getRemoteAddr(), request.getHeader("X-Forwarded-For"), body);
        } catch (RuntimeException e) {
            // Not stored: TalkTalk logs a failure. The protocol promises no redelivery, so this is a lost event.
            log.warn("talktalk_webhook store_failed error={}", e.getClass().getSimpleName());
            return ResponseEntity.status(503).build();
        }
        return ResponseEntity.status(result.status).build();
    }

    /** Up to {@code cap} bytes, or null when the stream holds more. Never allocates more than {@code cap + 1}. */
    static byte[] readBounded(InputStream in, int cap) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(cap, 8192));
        byte[] buf = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(buf, 0, Math.min(buf.length, cap + 1 - total))) > 0) {
            total += n;
            if (total > cap) {
                return null;
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
