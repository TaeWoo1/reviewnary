package com.sellerops.live;

import com.sellerops.auth.AuthPrincipal;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

/**
 * The open screen's question: "has anything changed since {@code after}?" ({@link ScreenChanges}). Answered at once
 * when the organisation's number is already elsewhere — or when the screen has no number yet — otherwise held until it
 * moves or {@link ScreenChanges#HOLD} runs out, and then answered with the number as it is. The organisation is the
 * token's, never the caller's to name.
 */
@RestController
public class ScreenChangesController {

    public record Changes(long version) {}

    private final ScreenChanges changes;

    public ScreenChangesController(ScreenChanges changes) {
        this.changes = changes;
    }

    @GetMapping("/api/live/changes")
    public DeferredResult<Changes> changes(@AuthenticationPrincipal AuthPrincipal principal,
                                           @RequestParam(required = false) Long after) {
        UUID orgId = principal.orgId();
        DeferredResult<Changes> answer = new DeferredResult<>(ScreenChanges.HOLD.toMillis());
        answer.onTimeout(() -> answer.setResult(new Changes(changes.version(orgId))));
        Runnable waiter = () -> answer.setResult(new Changes(changes.version(orgId)));
        answer.onCompletion(() -> changes.forget(orgId, waiter));
        // Listening before looking: a change between the look and the listen would otherwise wait out the hold.
        changes.listen(orgId, waiter);
        if (after == null || after != changes.version(orgId)) {
            answer.setResult(new Changes(changes.version(orgId)));
        }
        return answer;
    }
}
