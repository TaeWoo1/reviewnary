package com.sellerops.agent.reply;

import com.sellerops.auth.AuthPrincipal;
import com.sellerops.auth.runtoken.ReplyRunTokenAuthFilter;
import com.sellerops.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * <b>The reply agent's steps, reached only with a run token</b> (2026-10-28, {@code docs/standing/reply_agent_v1.md}).
 * The run is read from the token ({@link ReplyRunTokenAuthFilter#RUN_ID_ATTRIBUTE}), never from the request, so a run
 * cannot be pointed at another review. A seller's login does not reach these routes: no run id rides on it.
 */
@RestController
@RequestMapping("/api/agent/reply/run")
public class ReplyRunController {

    private final ReplyRunService runs;

    public ReplyRunController(ReplyRunService runs) {
        this.runs = runs;
    }

    private static UUID runId(HttpServletRequest request) {
        Object id = request.getAttribute(ReplyRunTokenAuthFilter.RUN_ID_ATTRIBUTE);
        if (!(id instanceof UUID runId)) {
            throw ApiException.notFound("실행을 찾을 수 없어요.");
        }
        return runId;
    }

    @PostMapping("/classify")
    public ReplyRunService.Judged classify(@AuthenticationPrincipal AuthPrincipal principal, HttpServletRequest request) {
        return runs.classify(principal.orgId(), runId(request));
    }

    public record DraftRequest(Boolean revise) {
    }

    @PostMapping("/draft")
    public ReplyRunService.Drafted draft(@AuthenticationPrincipal AuthPrincipal principal, HttpServletRequest request,
                                         @RequestBody(required = false) DraftRequest body) {
        return runs.draft(principal.orgId(), runId(request), body != null && Boolean.TRUE.equals(body.revise()));
    }

    @PostMapping("/critique")
    public ReplyRunService.Critiqued critique(@AuthenticationPrincipal AuthPrincipal principal, HttpServletRequest request) {
        return runs.critique(principal.orgId(), runId(request));
    }

    @PostMapping("/save")
    public ReplyRunService.Saved save(@AuthenticationPrincipal AuthPrincipal principal, HttpServletRequest request) {
        return runs.save(principal.orgId(), runId(request));
    }

    public record FinishRequest(String outcome, String reason) {
    }

    @PostMapping("/finish")
    public void finish(@AuthenticationPrincipal AuthPrincipal principal, HttpServletRequest request,
                       @RequestBody(required = false) FinishRequest body) {
        runs.finish(principal.orgId(), runId(request), body == null ? null : body.outcome(),
                body == null ? null : body.reason());
    }
}
