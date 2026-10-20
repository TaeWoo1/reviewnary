package com.sellerops.vendor.task;

import com.sellerops.vendor.auth.BearerAuthFilter.DelegatePrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The delegate's side of a reply task — contract §4.3: list what is approved in their stores, claim one, look
 * again just before saving, report what Seller Center shows, or hand it back. This server never posts anything:
 * the delegate pastes the approved text into Seller Center by hand.
 */
@RestController
public class DelegateTaskController {

    public record ClaimRequest(@NotNull Integer stateVersion) {}

    public record ReportRequest(@NotNull ReplyTasks.Outcome outcome, String postedFingerprint,
                                @NotNull Instant checkedAt, @NotNull ReplyTasks.ReportMethod method,
                                @NotBlank String commandId) {}

    private final ReplyTasks tasks;

    public DelegateTaskController(ReplyTasks tasks) {
        this.tasks = tasks;
    }

    @GetMapping("/v1/delegate/tasks")
    public List<ReplyTasks.DelegateTask> list(@AuthenticationPrincipal DelegatePrincipal delegate,
                                              @RequestParam(defaultValue = "APPROVED") ReplyTasks.State state) {
        return tasks.forDelegate(delegate.delegateId(), state);
    }

    @PostMapping("/v1/delegate/tasks/{taskId}/claim")
    public ReplyTasks.TaskView claim(@AuthenticationPrincipal DelegatePrincipal delegate, @PathVariable UUID taskId,
                                     @RequestBody @Valid ClaimRequest request) {
        return tasks.claim(taskId, delegate.delegateId(), request.stateVersion());
    }

    @GetMapping("/v1/delegate/tasks/{taskId}/preflight")
    public ReplyTasks.Preflight preflight(@AuthenticationPrincipal DelegatePrincipal delegate,
                                          @PathVariable UUID taskId) {
        return tasks.preflight(taskId, delegate.delegateId());
    }

    @PostMapping("/v1/delegate/tasks/{taskId}/report")
    public ReplyTasks.TaskView report(@AuthenticationPrincipal DelegatePrincipal delegate, @PathVariable UUID taskId,
                                      @RequestBody @Valid ReportRequest request) {
        return tasks.report(taskId, delegate.delegateId(), request.outcome(), request.postedFingerprint(),
                request.checkedAt(), request.method(), request.commandId());
    }

    @PostMapping("/v1/delegate/tasks/{taskId}/release")
    public ReplyTasks.TaskView release(@AuthenticationPrincipal DelegatePrincipal delegate,
                                       @PathVariable UUID taskId) {
        return tasks.release(taskId, delegate.delegateId());
    }
}
