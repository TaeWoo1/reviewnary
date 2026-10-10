package com.sellerops.connector.coupang;

import com.sellerops.credential.CredentialVault;
import java.time.Clock;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the real Coupang connector strictly behind the feature flag. With
 * {@code sellerops.connector.coupang.enabled=false} (the default) none of these beans exist:
 * the registry sees only the mock connector for COUPANG and runtime behavior is byte-identical
 * to before. Flipping the flag is a deliberate operator act — and even then a live call needs a
 * valid stored credential AND the deployment's egress IP registered in the seller's Coupang app.
 *
 * <p>The deployment-global setup endpoint ({@code CoupangSetupController}) and the account
 * connection lifecycle ({@code CoupangConnectionLifecycle}) are {@code @Component}s that exist
 * regardless of this flag — both are read-only / no-op until a real Coupang account is present,
 * so they are safe to always expose.
 */
@Configuration
@ConditionalOnProperty(name = "sellerops.connector.coupang.enabled", havingValue = "true")
public class CoupangConnectorConfiguration {

    @Bean
    CoupangHttpClient coupangHttpClient() {
        return new JdkCoupangHttpClient();
    }

    @Bean
    CoupangSigner coupangSigner() {
        return new CoupangSigner(Clock.systemUTC());
    }

    @Bean
    CoupangOrdersClient coupangOrdersClient(
            CoupangHttpClient http, CoupangSigner signer,
            @Value("${sellerops.connector.coupang.base-url:https://api-gateway.coupang.com}") String baseUrl,
            @Value("${sellerops.connector.coupang.live-approval-id:}") String liveApprovalId,
            @Value("${sellerops.self-pilot.enabled:false}") boolean selfPilotEnabled,
            @Value("${sellerops.self-pilot.read-grant-id:}") String standingReadGrantId,
            @Value("${sellerops.connector.coupang.order-wire-shape:false}") boolean observeWireShape) {
        // liveApprovalId arms the backend live-call interlock (CoupangLiveCallGuard). Empty by default →
        // a real-gateway call fails closed; an operator-approved run injects the bootstrapped id. The
        // Self-Pilot standing READ grant is the second key for this READ-only client (never for a write);
        // its shape is validated at boot by SelfPilotProperties, read here as the same property.
        //
        // The order wire-shape observer is its own flag, separate from the product one, because the two
        // are two observations with two key lists and turning on the one you are not running costs a
        // parse per page for nothing. It records key names, kinds and counts — never a value
        // (CoupangWireShapeObserver). Off unless a deployment turns it on for a specific observation:
        // instrumentation that stays armed by default is instrumentation nobody decided to run.
        if (observeWireShape) {
            LoggerFactory.getLogger(CoupangConnectorConfiguration.class)
                    .warn("Coupang 주문 wire-shape 관측이 켜져 있습니다 (키 이름·종류·개수만 기록, 값 없음).");
        }
        return new CoupangOrdersClient(http, signer, Clock.systemUTC(), baseUrl, liveApprovalId,
                effectiveReadGrant(selfPilotEnabled, standingReadGrantId), observeWireShape);
    }

    /**
     * The grant arms nothing unless the Self-Pilot Runtime itself is on (independent review, 2026-08-18: a
     * leftover grant with the runtime disabled must not stay a global READ key). It is process-scoped by
     * design — one operator, one local backend — and {@code sellerops.self-pilot.org-ids} scopes what the
     * reconciler acts on, not which org's manual sync the gate accepts (docs/self_pilot_runtime_v1.md §7).
     */
    /**
     * The precedence between the two standing READ grants that can open the inquiry gate, in one named
     * place: <b>narrow first</b>. The organisation-scoped grant answers for the organisations this
     * deployment named; where it has nothing to say the process-wide Self-Pilot grant answers exactly as it
     * did before this seam existed, so a deployment that sets neither new property is byte-identical.
     *
     * <p>Neither is a parameter of {@code ensureLiveWriteAllowed}, so neither can open a write.
     */
    static CoupangInquiriesClient.ReadGrant inquiryReadGrantOf(CoupangInquiryReadGrant scoped,
                                                               String selfPilotGrant) {
        String fallback = selfPilotGrant == null ? "" : selfPilotGrant;
        return orgId -> {
            String forOrg = scoped == null ? "" : scoped.forOrg(orgId);
            return forOrg.isEmpty() ? fallback : forOrg;
        };
    }

    static String effectiveReadGrant(boolean selfPilotEnabled, String standingReadGrantId) {
        return selfPilotEnabled ? standingReadGrantId : "";
    }

    @Bean
    CoupangInquiriesClient coupangInquiriesClient(
            CoupangHttpClient http, CoupangSigner signer,
            @Value("${sellerops.connector.coupang.base-url:https://api-gateway.coupang.com}") String baseUrl,
            @Value("${sellerops.connector.coupang.live-approval-id:}") String liveApprovalId,
            @Value("${sellerops.self-pilot.enabled:false}") boolean selfPilotEnabled,
            @Value("${sellerops.self-pilot.read-grant-id:}") String standingReadGrantId,
            @Value("${sellerops.connector.coupang.inquiry-read-grant-id:}") String inquiryGrantId,
            @Value("${sellerops.connector.coupang.inquiry-read-grant-org-ids:}") String inquiryGrantOrgIds) {
        // Same base URL and same live-call interlock as the order client — one armed approval (or a
        // standing READ grant) covers the account's read-only collection; neither stream can reach a real
        // host without one of them.
        //
        // TWO grants feed one gate parameter, and the order is narrow-first: the organisation-scoped grant
        // (CoupangInquiryReadGrant) answers for the organisations a deployment named, and the process-wide
        // Self-Pilot grant answers exactly as it did before — so a deployment that sets neither new
        // property behaves byte-for-byte as it did. Both are READ; neither is a parameter of the write gate.
        String selfPilot = effectiveReadGrant(selfPilotEnabled, standingReadGrantId);
        // A malformed grant id throws here, which fails the bean and therefore the boot — the same
        // fail-closed posture SelfPilotProperties has for the grant it owns.
        CoupangInquiryReadGrant inquiryReadGrant = new CoupangInquiryReadGrant(inquiryGrantId, inquiryGrantOrgIds);
        return new CoupangInquiriesClient(http, signer, Clock.systemUTC(), baseUrl, liveApprovalId,
                inquiryReadGrantOf(inquiryReadGrant, selfPilot), CoupangInquiriesClient.SLEEPING_PACER);
    }

    @Bean
    CoupangSellerProductsClient coupangSellerProductsClient(
            CoupangHttpClient http, CoupangSigner signer,
            @Value("${sellerops.connector.coupang.base-url:https://api-gateway.coupang.com}") String baseUrl,
            @Value("${sellerops.connector.coupang.live-approval-id:}") String liveApprovalId,
            @Value("${sellerops.self-pilot.enabled:false}") boolean selfPilotEnabled,
            @Value("${sellerops.self-pilot.read-grant-id:}") String standingReadGrantId,
            @Value("${sellerops.connector.coupang.product-wire-shape:false}") boolean observeWireShape) {
        // Same base URL and same live-call interlock as the order and inquiry clients. The catalogue is
        // a READ, so the standing READ grant covers it exactly as it covers the other two — and with the
        // runtime off it arms nothing, per effectiveReadGrant.
        //
        // The wire-shape observer is off unless a deployment turns it on for a specific observation. It
        // records key names and counts, never a value (CoupangWireShapeObserver) — but instrumentation
        // that stays armed by default is instrumentation nobody decided to run.
        if (observeWireShape) {
            LoggerFactory.getLogger(CoupangConnectorConfiguration.class)
                    .warn("Coupang 상품 wire-shape 관측이 켜져 있습니다 (키 이름·개수만 기록).");
        }
        return new CoupangSellerProductsClient(http, signer, Clock.systemUTC(), baseUrl, liveApprovalId,
                effectiveReadGrant(selfPilotEnabled, standingReadGrantId),
                // The ceiling is not configurable. A bound a deployment can raise from a properties
                // file is not a bound; raising it should be a change someone reviews.
                CoupangSellerProductsClient.DEFAULT_REQUEST_BUDGET, observeWireShape);
    }

    @Bean
    CoupangApiConnector coupangApiConnector(CoupangOrdersClient ordersClient,
                                            CoupangInquiriesClient inquiriesClient,
                                            CoupangSellerProductsClient sellerProductsClient,
                                            CredentialVault vault) {
        return new CoupangApiConnector(ordersClient, inquiriesClient, sellerProductsClient, vault);
    }
}
