package com.sellerops.vendor.task;

import com.sellerops.vendor.auth.BearerAuthFilter.Principal;
import com.sellerops.vendor.store.Stores;
import com.sellerops.vendor.task.ReplyTasks.Failure;
import com.sellerops.vendor.task.ReplyTasks.TaskException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The seller's side of a reply task — contract §4.3. The PC (install token) submits drafts, syncs changes and
 * reports its policy; the seller (session, typically the phone) or the PC reads, edits, approves and cancels. Only
 * tasks in the caller's own account's stores; anything else is the same 404 as nothing.
 */
@RestController
public class TaskController {

    public record MetaRequest(@NotBlank String reviewBodyFingerprint, @NotNull Instant generatedAt,
                              @NotBlank String knowledgeFp, @NotBlank String policyFp, @NotBlank String voiceFp) {}

    /**
     * {@code baseVersion} (2026-10-25, optional): the version the PC last saw — {@code 0} for "no open task". Given, a
     * draft that would replace a version the PC never saw (a phone edit) is refused as {@code stale}.
     */
    public record DraftRequest(@NotNull UUID reviewId, @NotBlank String body, @NotNull @Valid MetaRequest meta,
                               Integer baseVersion) {}

    public record EditRequest(@NotNull Integer baseVersion, @NotBlank String body) {}

    public record RecheckRequest(@NotNull Integer draftVersion, @NotNull Boolean passed, List<String> codes,
                                 ReplyTasks.BasisNote note) {}

    public record ApprovedCheckRequest(@NotNull Integer approvedVersion, @NotNull Boolean hold,
                                       @NotNull ReplyTasks.BasisNote note, @NotBlank String commandId) {}

    public record PolicyRequest(@NotBlank String knowledgeFp, @NotBlank String policyFp, @NotBlank String voiceFp) {}

    public record ApproveRequest(@NotNull Integer version, @NotBlank String fingerprint, @NotBlank String commandId) {}

    public record CancelRequest(@NotBlank String commandId) {}

    private final ReplyTasks tasks;
    private final Stores stores;

    public TaskController(ReplyTasks tasks, Stores stores) {
        this.tasks = tasks;
        this.stores = stores;
    }

    @PostMapping("/v1/stores/{storeId}/tasks")
    public ResponseEntity<ReplyTasks.TaskView> submit(@AuthenticationPrincipal Principal pc, @PathVariable UUID storeId,
                                                      @RequestBody @Valid DraftRequest request) {
        ownStore(pc, storeId);
        MetaRequest m = request.meta();
        return ResponseEntity.status(HttpStatus.CREATED).body(tasks.submitDraft(storeId, request.reviewId(),
                request.body(), new ReplyTasks.Meta(m.reviewBodyFingerprint(), m.generatedAt(), m.knowledgeFp(),
                        m.policyFp(), m.voiceFp()), request.baseVersion()));
    }

    @GetMapping("/v1/stores/{storeId}/tasks/changes")
    public ReplyTasks.Changes changes(@AuthenticationPrincipal Principal pc, @PathVariable UUID storeId,
                                      @RequestParam(defaultValue = "0") long cursor,
                                      @RequestParam(defaultValue = "100") int limit) {
        ownStore(pc, storeId);
        if (cursor < 0) {
            throw new TaskException(Failure.INVALID_META);
        }
        return tasks.changes(storeId, cursor, limit);
    }

    @PostMapping("/v1/stores/{storeId}/tasks/policy-check")
    public ReplyTasks.PolicyCheck policyCheck(@AuthenticationPrincipal Principal pc, @PathVariable UUID storeId,
                                              @RequestBody @Valid PolicyRequest request) {
        ownStore(pc, storeId);
        return tasks.policyCheck(storeId, request.knowledgeFp(), request.policyFp(), request.voiceFp());
    }

    @GetMapping("/v1/stores/{storeId}/tasks")
    public List<ReplyTasks.TaskView> list(@AuthenticationPrincipal Principal caller, @PathVariable UUID storeId,
                                          @RequestParam(required = false) ReplyTasks.State state) {
        ownStore(caller, storeId);
        return tasks.list(storeId, state);
    }

    @GetMapping("/v1/tasks/{taskId}")
    public ReplyTasks.TaskView get(@AuthenticationPrincipal Principal caller, @PathVariable UUID taskId) {
        ownTask(caller, taskId);
        return tasks.get(taskId).orElseThrow(() -> new TaskException(Failure.NOT_FOUND));
    }

    /** A new version. Written through a seller's session it is marked as edited on the phone (§4.1). */
    @PutMapping("/v1/tasks/{taskId}/draft")
    public ReplyTasks.TaskView edit(@AuthenticationPrincipal Principal caller, @PathVariable UUID taskId,
                                    @RequestBody @Valid EditRequest request) {
        ownTask(caller, taskId);
        return tasks.editDraft(taskId, request.baseVersion(), request.body(), caller.installId() == null);
    }

    @PostMapping("/v1/tasks/{taskId}/pc-recheck")
    public ReplyTasks.TaskView recheck(@AuthenticationPrincipal Principal pc, @PathVariable UUID taskId,
                                       @RequestBody @Valid RecheckRequest request) {
        ownTask(pc, taskId);
        return tasks.recordPcRecheck(taskId, request.draftVersion(), request.passed(),
                request.codes() == null ? List.of() : request.codes(), request.note());
    }

    /**
     * The PC's check of an approved text after the basis changed (owner decision 2026-10-23). Only an installed PC
     * makes this finding — it alone holds the seller's knowledge — so a seller's session is answered as if the task
     * were not there.
     */
    @PostMapping("/v1/tasks/{taskId}/approved-check")
    public ReplyTasks.TaskView approvedCheck(@AuthenticationPrincipal Principal pc, @PathVariable UUID taskId,
                                             @RequestBody @Valid ApprovedCheckRequest request) {
        ownTask(pc, taskId);
        if (pc.installId() == null) {
            throw new TaskException(Failure.NOT_FOUND);
        }
        return tasks.checkApproved(taskId, request.approvedVersion(), request.hold(), request.note(),
                request.commandId());
    }

    @PostMapping("/v1/tasks/{taskId}/approve")
    public ReplyTasks.TaskView approve(@AuthenticationPrincipal Principal caller, @PathVariable UUID taskId,
                                       @RequestBody @Valid ApproveRequest request) {
        ownTask(caller, taskId);
        return tasks.approve(taskId, caller.accountId(), request.version(), request.fingerprint(),
                request.commandId());
    }

    @PostMapping("/v1/tasks/{taskId}/cancel")
    public ReplyTasks.TaskView cancel(@AuthenticationPrincipal Principal caller, @PathVariable UUID taskId,
                                      @RequestBody @Valid CancelRequest request) {
        ownTask(caller, taskId);
        return tasks.cancel(taskId, request.commandId());
    }

    private void ownStore(Principal caller, UUID storeId) {
        if (stores.accountOf(storeId).filter(caller.accountId()::equals).isEmpty()) {
            throw new TaskException(Failure.NOT_FOUND);
        }
    }

    private void ownTask(Principal caller, UUID taskId) {
        ownStore(caller, tasks.storeOf(taskId).orElseThrow(() -> new TaskException(Failure.NOT_FOUND)));
    }
}
