package com.sellerops.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * <b>The workspace server must not boot with its protections left at a local backend's defaults</b> —
 * {@code docs/standing/server_operation_boundary_v1.md} §4 · §6 (external review 2026-10-31: 「배포 설정이 빠지면 제한이
 * 적용되지 않습니다 — 운영 환경에서는 잘못된 설정으로 시작하지 못하게」).
 *
 * <p>The channel activation gate and the model send check default to what a local or demo backend needs: the gate
 * off, so a developer's backend keeps every connection it had. A server deployment that forgets to set them starts,
 * serves every screen, and opens official connections nobody is qualified for — nothing on any screen says so. That
 * is the same failure class {@link PilotConfigValidator} and {@link LocalSellerConfigValidator} name, so it is said
 * the same way: at boot, by refusing.
 *
 * <p><b>The workspace server is the backend in {@code sellerops.vendor-link.mode=WORKSPACE}</b> — the one shape in
 * which a web seller works with no PC. Every other mode boots exactly as before. Like the other validators it names
 * the setting to change and never a value.
 *
 * <p>It runs as the bean initialises, as {@link LocalSellerConfigValidator} does, so a misconfigured server never
 * answers a single request.
 */
@Component
public class WorkspaceServerConfigValidator implements InitializingBean {

    private final String vendorLinkMode;
    private final boolean channelActivationEnforced;
    private final boolean llmSendGateEnabled;
    private final String serviceToken;
    private final boolean reviewPullEnabled;

    public WorkspaceServerConfigValidator(
            @Value("${sellerops.vendor-link.mode:INSTALL}") String vendorLinkMode,
            @Value("${sellerops.channel-activation.enforced:false}") boolean channelActivationEnforced,
            @Value("${sellerops.llm.send-gate.enabled:true}") boolean llmSendGateEnabled,
            @Value("${sellerops.vendor-link.service-token:}") String serviceToken,
            @Value("${sellerops.vendor-link.review-pull.enabled:false}") boolean reviewPullEnabled) {
        this.vendorLinkMode = vendorLinkMode;
        this.channelActivationEnforced = channelActivationEnforced;
        this.llmSendGateEnabled = llmSendGateEnabled;
        this.serviceToken = serviceToken;
        this.reviewPullEnabled = reviewPullEnabled;
    }

    @Override
    public void afterPropertiesSet() {
        List<String> problems = problems();
        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "업무 서버(vendor-link WORKSPACE)로 시작할 수 없는 설정입니다. "
                            + "아래를 고친 뒤 다시 시작하세요:\n  - "
                            + String.join("\n  - ", problems));
        }
    }

    /** The conditions, in one list, so a test can read them without starting a context. */
    List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (!isWorkspace(vendorLinkMode)) {
            return problems;
        }
        if (!channelActivationEnforced) {
            problems.add("SELLEROPS_CHANNEL_ACTIVATION_ENFORCED — 업무 서버에서는 채널 활성화 제한이 켜져 있어야 합니다. "
                    + "꺼져 있으면 자격이 없는 공식 연결까지 모두 열립니다(채널별 수준은 SELLEROPS_CHANNEL_ACTIVATION_*).");
        }
        if (!llmSendGateEnabled) {
            problems.add("SELLEROPS_LLM_SEND_GATE_ENABLED — 업무 서버에서는 모델 전송 전 검사를 끌 수 없습니다.");
        }
        if (serviceToken == null || serviceToken.isBlank()) {
            problems.add("SELLEROPS_VENDOR_SERVICE_TOKEN — 업무 서버가 공급자 서버에 조직 대신 묻는 서비스 토큰이 없습니다. "
                    + "리뷰가 한 건도 들어오지 않습니다.");
        }
        if (!reviewPullEnabled) {
            problems.add("SELLEROPS_VENDOR_REVIEW_PULL_ENABLED — 업무 서버인데 리뷰 받기가 꺼져 있어 리뷰가 들어오지 않습니다.");
        }
        return problems;
    }

    private static boolean isWorkspace(String mode) {
        return mode != null && mode.trim().toUpperCase(Locale.ROOT).equals("WORKSPACE");
    }
}
