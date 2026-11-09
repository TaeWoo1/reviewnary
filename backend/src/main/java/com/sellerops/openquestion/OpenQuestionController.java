package com.sellerops.openquestion;

import com.sellerops.auth.AuthPrincipal;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The questions a draft left to the seller, and the seller's 「처리함」 on each (2026-11-01 tenth review). */
@RestController
@RequestMapping("/api/open-questions")
public class OpenQuestionController {

    public record HandleRequest(String resolution, String note) {
    }

    private final OpenQuestionService service;

    public OpenQuestionController(OpenQuestionService service) {
        this.service = service;
    }

    @GetMapping
    public List<OpenQuestionService.OpenQuestionView> list(@AuthenticationPrincipal AuthPrincipal principal,
                                                           @RequestParam String lane, @RequestParam UUID subjectId) {
        return service.list(principal.orgId(), lane, subjectId);
    }

    @PostMapping("/{id}/handle")
    public OpenQuestionService.OpenQuestionView handle(@AuthenticationPrincipal AuthPrincipal principal,
                                                       @PathVariable UUID id, @RequestBody HandleRequest request) {
        return service.handle(principal.orgId(), id, request == null ? null : request.resolution(),
                request == null ? null : request.note(), "SELLER:" + principal.userId());
    }
}
