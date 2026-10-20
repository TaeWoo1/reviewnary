package com.sellerops.vendor.task;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * A task refusal as a fixed code. 404 for anything the caller may not see (a task in someone else's store and a
 * task that does not exist answer alike), 409 when the task is not where the caller thought, 400 for a malformed
 * request, 403 for a delegate acting on a task another delegate holds.
 */
@RestControllerAdvice(assignableTypes = {TaskController.class, DelegateTaskController.class})
class TaskErrors {

    @ExceptionHandler(ReplyTasks.TaskException.class)
    ResponseEntity<Map<String, String>> refused(ReplyTasks.TaskException e) {
        HttpStatus status = switch (e.failure()) {
            case NOT_FOUND, NOT_ASSIGNED -> HttpStatus.NOT_FOUND;
            case INVALID_BODY, INVALID_META -> HttpStatus.BAD_REQUEST;
            case NOT_CLAIMER -> HttpStatus.FORBIDDEN;
            default -> HttpStatus.CONFLICT;
        };
        return ResponseEntity.status(status).body(Map.of("error", e.failure().name().toLowerCase()));
    }
}
