package com.sellerops.talktalk;

import com.sellerops.auth.AuthPrincipal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in lane API. Org and actor come only from the principal; every body field is the seller's choice. */
@RestController
@RequestMapping("/api/talktalk")
public class TalkTalkController {

    private final TalkTalkQueryService query;
    private final TalkTalkConnectionService connections;
    private final TalkTalkGuidanceService guidance;
    private final TalkTalkDraftService drafts;
    private final TalkTalkSendService sends;

    public TalkTalkController(TalkTalkQueryService query, TalkTalkConnectionService connections,
                              TalkTalkGuidanceService guidance, TalkTalkDraftService drafts,
                              TalkTalkSendService sends) {
        this.query = query;
        this.connections = connections;
        this.guidance = guidance;
        this.drafts = drafts;
        this.sends = sends;
    }

    public record ConnectRequest(String partnerId, String sendKey) {
        @Override
        public String toString() {
            return "ConnectRequest(partnerId=<masked>, sendKey=<masked>)";
        }
    }

    public record ClosedDateRequest(LocalDate date, String note) {
    }

    public record GuidanceRequest(Integer cutoffHour, Integer cutoffMinute, List<String> dispatchDays,
                                  List<ClosedDateRequest> closedDates, LocalDate confirmedThrough) {
    }

    public record ConfirmRequest(String expectedFingerprint) {
    }

    public record DelegationRequest(boolean enabled, UUID guidanceId, String guidanceFingerprint,
                                    String previewFingerprint) {
    }

    public record DraftRequest(String mode) {
    }

    public record EditRequest(String text, Integer baseVersion) {
    }

    public record SendRequest(String commandId, Integer draftVersion, String expectedFingerprint,
                              UUID acknowledgeUnknownOf, Boolean confirmHolds) {
    }

    public record HandoverRequest(String commandId, String direction) {
    }

    @GetMapping("/status")
    public TalkTalkViews.StatusView status(@AuthenticationPrincipal AuthPrincipal p) {
        return query.status(p.orgId(), p.userId());
    }

    @GetMapping("/summary")
    public TalkTalkViews.SummaryView summary(@AuthenticationPrincipal AuthPrincipal p) {
        return query.summary(p.orgId(), p.userId());
    }

    @PostMapping("/connection")
    public TalkTalkViews.ConnectResponse connect(@AuthenticationPrincipal AuthPrincipal p,
                                                 @RequestBody ConnectRequest r) {
        TalkTalkConnectionService.ConnectResult result = connections.connect(p.orgId(), p.userId(), r.partnerId(),
                r.sendKey());
        return new TalkTalkViews.ConnectResponse(query.status(p.orgId(), p.userId()), result.webhookUrl(),
                "이 주소는 지금 한 번만 보여요. 톡톡 파트너센터 > 개발자도구 > 챗봇 API 설정의 「이벤트 받을 URL」에 넣어 주세요.");
    }

    @PostMapping("/connection/rotate-webhook")
    public TalkTalkViews.ConnectResponse rotate(@AuthenticationPrincipal AuthPrincipal p) {
        TalkTalkConnectionService.ConnectResult result = connections.rotateWebhook(p.orgId(), p.userId());
        return new TalkTalkViews.ConnectResponse(query.status(p.orgId(), p.userId()), result.webhookUrl(),
                "새 주소로 바꿨어요. 예전 주소로 오는 이벤트는 받지 않아요. 파트너센터의 주소도 바꿔 주세요.");
    }

    @DeleteMapping("/connection")
    public TalkTalkViews.StatusView disconnect(@AuthenticationPrincipal AuthPrincipal p) {
        connections.disconnect(p.orgId(), p.userId());
        return query.status(p.orgId(), p.userId());
    }

    @PutMapping("/guidance/draft")
    public TalkTalkViews.GuidanceView saveGuidance(@AuthenticationPrincipal AuthPrincipal p,
                                                   @RequestBody GuidanceRequest r) {
        List<TalkTalkGuidanceRules.ClosedDate> closed = r.closedDates() == null ? List.of()
                : r.closedDates().stream().map(c -> new TalkTalkGuidanceRules.ClosedDate(c.date(), c.note())).toList();
        return query.view(guidance.saveDraft(p.orgId(), p.userId(), new TalkTalkGuidanceService.Input(
                r.cutoffHour(), r.cutoffMinute(), r.dispatchDays(), closed, r.confirmedThrough())));
    }

    @PostMapping("/guidance/{id}/confirm")
    public TalkTalkViews.StatusView confirmGuidance(@AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID id,
                                                    @RequestBody ConfirmRequest r) {
        guidance.confirm(p.orgId(), p.userId(), id, r.expectedFingerprint());
        return query.status(p.orgId(), p.userId());
    }

    @PostMapping("/guidance/{id}/retire")
    public TalkTalkViews.StatusView retireGuidance(@AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID id) {
        guidance.retire(p.orgId(), p.userId(), id);
        return query.status(p.orgId(), p.userId());
    }

    @PostMapping("/delegation")
    public TalkTalkViews.StatusView delegate(@AuthenticationPrincipal AuthPrincipal p,
                                             @RequestBody DelegationRequest r) {
        guidance.delegate(p.orgId(), p.userId(), r.enabled(), r.guidanceId(), r.guidanceFingerprint(),
                r.previewFingerprint());
        return query.status(p.orgId(), p.userId());
    }

    @GetMapping("/conversations")
    public List<TalkTalkViews.ConversationRow> conversations(@AuthenticationPrincipal AuthPrincipal p) {
        return query.conversations(p.orgId(), p.userId());
    }

    @GetMapping("/conversations/{id}")
    public TalkTalkViews.ConversationDetail conversation(@AuthenticationPrincipal AuthPrincipal p,
                                                         @PathVariable UUID id) {
        return query.detail(p.orgId(), p.userId(), id);
    }

    @PostMapping("/conversations/{id}/draft")
    public TalkTalkViews.ConversationDetail prepare(@AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID id,
                                                    @RequestBody DraftRequest r) {
        drafts.prepare(p.orgId(), p.userId(), id, r.mode());
        return query.detail(p.orgId(), p.userId(), id);
    }

    @PostMapping("/conversations/{id}/draft/edit")
    public TalkTalkViews.ConversationDetail edit(@AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID id,
                                                 @RequestBody EditRequest r) {
        drafts.edit(p.orgId(), p.userId(), id, r.text(), r.baseVersion());
        return query.detail(p.orgId(), p.userId(), id);
    }

    @PostMapping("/conversations/{id}/send")
    public TalkTalkViews.ConversationDetail send(@AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID id,
                                                 @RequestBody SendRequest r) {
        sends.send(p.orgId(), p.userId(), id, r.commandId(), r.draftVersion(), r.expectedFingerprint(),
                r.acknowledgeUnknownOf(), Boolean.TRUE.equals(r.confirmHolds()));
        return query.detail(p.orgId(), p.userId(), id);
    }

    @PostMapping("/conversations/{id}/handover")
    public TalkTalkViews.ConversationDetail handover(@AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID id,
                                                     @RequestBody HandoverRequest r) {
        sends.handover(p.orgId(), p.userId(), id, r.commandId(), r.direction());
        return query.detail(p.orgId(), p.userId(), id);
    }
}
