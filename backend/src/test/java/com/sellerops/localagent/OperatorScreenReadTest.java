package com.sellerops.localagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sellerops.auth.device.HelperDevice;
import com.sellerops.auth.device.HelperDeviceRepository;
import com.sellerops.channel.Channel;
import com.sellerops.channel.ChannelRepository;
import com.sellerops.channel.ChannelStatus;
import com.sellerops.common.ApiException;
import com.sellerops.responsibility.aside.AsideDispatch;
import com.sellerops.responsibility.aside.AsideHelperDevices;
import com.sellerops.responsibility.aside.AsideHelperUnavailableException;
import com.sellerops.responsibility.aside.AsideMarketplaceAccess;
import com.sellerops.responsibility.aside.AsideMarketplaceTarget;
import com.sellerops.responsibility.aside.AsideRecipe;
import com.sellerops.responsibility.aside.AsideTrigger;
import com.sellerops.responsibility.aside.ScheduledAsideJob;
import com.sellerops.responsibility.aside.ScheduledAsideJobRepository;
import com.sellerops.responsibility.aside.ScheduledAsideJobService;
import com.sellerops.responsibility.aside.ScheduledAsideJobStatus;
import com.sellerops.selleraccount.SellerAccount;
import com.sellerops.selleraccount.SellerAccountRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>지금 수집 — a seller reading their own channel screen, with nothing but the press.</b>
 *
 * <p>What this pins is the thing that was structurally impossible before: a job existing because a person
 * asked for one. The producer of a job row was a responsibility run, so reading a seller's own store required
 * an unattended lane the deployment had armed in advance — and the account was never the caller's to name.
 *
 * <p>Every test here runs with the unattended lane <b>off</b> and its allow-lists <b>empty</b>, which is the
 * shipped posture. That is deliberate: if any of these passed only with the Responsibility lane armed, the
 * operator door would not be a door, it would be a second handle on the same one. The last two tests guard the
 * other direction — that opening this door widened neither the unattended lane nor what a recipe may do.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:operator_screen_read;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class OperatorScreenReadTest {

    @Autowired ScheduledAsideJobRepository jobs;
    @Autowired SellerAccountRepository accounts;
    @Autowired ChannelRepository channels;
    @Autowired HelperDeviceRepository devices;

    private static final Instant T0 = Instant.parse("2026-10-07T04:00:00Z");

    private UUID org;
    private UUID naverAccount;
    private UUID coupangAccount;
    private UUID cafe24Account;
    private ScreenReadService service;
    private ScheduledAsideJobService dispatcher;

    @BeforeEach
    void setUp() {
        jobs.deleteAll();
        devices.deleteAll();
        accounts.deleteAll();
        org = UUID.randomUUID();
        naverAccount = account(channel("NAVER", "네이버 스마트스토어"), org).getId();
        coupangAccount = account(channel("COUPANG", "쿠팡"), org).getId();
        cafe24Account = account(channel("CAFE24", "카페24 자사몰"), org).getId();
        link(org);
        wire(new AsideMarketplaceAccess(false, Set.of(), Set.of()));
    }

    /**
     * The service under test, built on the real primitive.
     *
     * <p>The resolver stands in for the per-channel store lookups and does what they do: answers for an account
     * of this organisation on the recipe's own channel, and for no other. It does not consult an allow-list,
     * because {@link AsideMarketplaceTarget#resolveFor} is the method the operator lane uses and that is its
     * documented contract.
     */
    private void wire(AsideMarketplaceAccess access) {
        AsideMarketplaceTarget resolver = new AsideMarketplaceTarget() {
            @Override
            public Optional<AsideMarketplaceTarget.Target> resolve(UUID orgId, AsideRecipe recipe) {
                return Optional.empty(); // the deployment named nobody — the shipped posture
            }

            @Override
            public Optional<AsideMarketplaceTarget.Target> resolveFor(UUID orgId, UUID sellerAccountId,
                                                                      AsideRecipe recipe) {
                return accounts.findByIdAndOrgId(sellerAccountId, orgId)
                        .filter(a -> channels.findById(a.getChannelId())
                                .map(Channel::getCode)
                                .filter(code -> recipe.channelCode().map(code::equals).orElse(false))
                                .isPresent())
                        .map(a -> new AsideMarketplaceTarget.Target(a.getId(), "slot-1", "digest"));
            }
        };
        AsideHelperDevices helpers = new AsideHelperDevices(devices, Clock.fixed(T0, ZoneOffset.UTC));
        dispatcher = new ScheduledAsideJobService(jobs, Clock.fixed(T0, ZoneOffset.UTC), access, resolver, helpers);
        service = new ScreenReadService(dispatcher, helpers, accounts, channels);
    }

    @Test
    @DisplayName("a seller can read their own NAVER review screen, with the unattended lane off")
    void operatorStartsANaverReviewRead() {
        ScreenReadView view = service.start(org, naverAccount, "REVIEW", "press-1");

        assertThat(view.state()).isEqualTo(LocalAgentRunState.RUNNING);
        ScheduledAsideJob job = jobs.findById(view.jobId()).orElseThrow();
        assertThat(job.getRecipe()).isEqualTo(AsideRecipe.NAVER_REVIEW_OBSERVE_V1);
        assertThat(job.getTrigger()).isEqualTo(AsideTrigger.OPERATOR);
        assertThat(job.getSellerAccountId()).isEqualTo(naverAccount);
        assertThat(job.getStatus()).isEqualTo(ScheduledAsideJobStatus.QUEUED);
        // No run, because no run asked; one page, because no recipe can turn one.
        assertThat(job.getRunId()).isNull();
        assertThat(job.getMaxPages()).isEqualTo(1);
    }

    @Test
    @DisplayName("the same press reads the Coupang WING screen through the same primitive")
    void operatorStartsACoupangReviewRead() {
        ScreenReadView view = service.start(org, coupangAccount, "REVIEW", "press-1");

        ScheduledAsideJob job = jobs.findById(view.jobId()).orElseThrow();
        assertThat(job.getRecipe()).isEqualTo(AsideRecipe.COUPANG_REVIEW_OBSERVE_V1);
        assertThat(job.getTrigger()).isEqualTo(AsideTrigger.OPERATOR);
        assertThat(job.getSellerAccountId()).isEqualTo(coupangAccount);
    }

    @Test
    @DisplayName("nothing beyond the press: no scheduler, no grant, no approval id, no typed phrase")
    void thePressIsTheWholeAuthorisation() {
        // This context arms nothing: the marketplace lane is off, both allow-lists are empty, no live-approval
        // or read-grant property exists, and no token was passed to `start`. The read is dispatched anyway,
        // which is the product decision this lane embodies — and a regression here would be a ceremony
        // appearing on a seller's button.
        assertThat(service.start(org, naverAccount, "REVIEW", null).state())
                .isEqualTo(LocalAgentRunState.RUNNING);
    }

    @Test
    @DisplayName("the claimed job carries the store the seller pressed on, not the one a deployment named")
    void theClaimedJobNamesThePressedStore() {
        ScreenReadView view = service.start(org, coupangAccount, "REVIEW", "press-1");

        ScheduledAsideJobService.ClaimedJob claimed =
                dispatcher.claim(org, devices.findAll().get(0).getId()).orElseThrow();
        assertThat(claimed.jobId()).isEqualTo(view.jobId());
        assertThat(claimed.target()).isNotNull();
        assertThat(claimed.target().sellerAccountId()).isEqualTo(coupangAccount);
        // The helper gets a slot to hand the reading back through and a digest to refuse a foreign store with.
        assertThat(claimed.target().accountSlot()).isNotBlank();
        assertThat(claimed.target().expectedStoreFingerprint()).isNotBlank();
    }

    @Test
    @DisplayName("a second press with the same request id is the same job, not a second one on the desk")
    void aRepeatedPressIsIdempotent() {
        ScreenReadView first = service.start(org, naverAccount, "REVIEW", "press-1");
        ScreenReadView again = service.start(org, naverAccount, "REVIEW", "press-1");

        assertThat(again.jobId()).isEqualTo(first.jobId());
        assertThat(jobs.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("another organisation's account is not found — not forbidden, not read")
    void anotherOrgsAccountIsRefused() {
        UUID stranger = UUID.randomUUID();
        SellerAccount theirs = account(channels.findByCode("NAVER").orElseThrow(), stranger);

        assertThatThrownBy(() -> service.start(org, theirs.getId(), "REVIEW", "press-1"))
                .isInstanceOf(ApiException.class);
        assertThat(jobs.count()).isZero();
    }

    @Test
    @DisplayName("a channel with no screen read for that data refuses, and says it is a capability")
    void aChannelWithoutAScreenReadIsRefused() {
        // Cafe24 reviews arrive by official API; there is no seller-centre screen recipe, and inventing one
        // here would be the product promising a read it cannot perform.
        assertThatThrownBy(() -> service.start(org, cafe24Account, "REVIEW", "press-1"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("아직 없습니다");
        assertThat(jobs.count()).isZero();
    }

    @Test
    @DisplayName("with no helper linked the seller is told to link one, and nothing is queued")
    void withNoHelperLinkedItAsksForOne() {
        devices.deleteAll();

        assertThatThrownBy(() -> service.start(org, naverAccount, "REVIEW", "press-1"))
                .isInstanceOf(AsideHelperUnavailableException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(AsideHelperUnavailableException.CODE);
        assertThat(jobs.count()).isZero();
        assertThat(service.readiness(org, naverAccount, "REVIEW").state())
                .isEqualTo(LocalAgentRunState.UNPAIRED);
    }

    @Test
    @DisplayName("readiness answers both questions separately: can this channel be read, and is a helper here")
    void readinessSeparatesCapabilityFromTheDesk() {
        assertThat(service.readiness(org, naverAccount, "REVIEW"))
                .isEqualTo(new ScreenReadReadinessView(true, LocalAgentRunState.READY));
        assertThat(service.readiness(org, cafe24Account, "REVIEW"))
                .isEqualTo(new ScreenReadReadinessView(false, LocalAgentRunState.READY));
    }

    @Test
    @DisplayName("the unattended lane is not widened: a responsibility dispatch still needs the deployment")
    void theResponsibilityLaneStillNeedsTheDeployment() {
        // Same primitive, same process, same shipped posture — and refused, because nobody is watching that
        // lane and nobody has vouched for the store. The operator door did not become a way around this.
        assertThatThrownBy(() -> dispatcher.dispatch(
                AsideDispatch.responsibility(org, UUID.randomUUID(), "run-job-1",
                        AsideRecipe.NAVER_REVIEW_OBSERVE_V1)))
                .isInstanceOf(ApiException.class);
        assertThat(jobs.count()).isZero();
    }

    @Test
    @DisplayName("a press cannot carry a run id, and a press without a store cannot be built at all")
    void thePressShapeIsEnforced() {
        assertThatThrownBy(() -> new AsideDispatch(org, naverAccount, AsideRecipe.NAVER_REVIEW_OBSERVE_V1,
                AsideTrigger.OPERATOR, null, UUID.randomUUID(), "press-1"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> new AsideDispatch(org, null, AsideRecipe.NAVER_REVIEW_OBSERVE_V1,
                AsideTrigger.OPERATOR, null, null, "press-1"))
                .isInstanceOf(ApiException.class);
    }

    private Channel channel(String code, String nameKo) {
        return channels.findByCode(code).orElseGet(() -> {
            Channel ch = new Channel();
            ch.setCode(code);
            ch.setNameKo(nameKo);
            ch.setStatus(ChannelStatus.AVAILABLE);
            ch.setSupportsReview(true);
            ch.setSupportsInquiry(true);
            ch.setSupportsOrder(true);
            ch.setSupportsSales(true);
            ch.setSupportsProduct(true);
            ch.setSortOrder(0);
            return channels.save(ch);
        });
    }

    private SellerAccount account(Channel channel, UUID orgId) {
        SellerAccount account = new SellerAccount();
        account.setOrgId(orgId);
        account.setChannelId(channel.getId());
        account.setConnectionStatus(ChannelStatus.CONNECTED);
        account.setFileUpload(false);
        return accounts.save(account);
    }

    /** One live device grant — the thing a seller creates once by linking their installed helper. */
    private void link(UUID orgId) {
        HelperDevice device = new HelperDevice();
        device.setOrgId(orgId);
        device.setUserId(UUID.randomUUID());
        device.setTokenHash("hash-" + UUID.randomUUID());
        device.setDeviceName("Mac (arm64)");
        device.setHelperVersion("0.3.0");
        device.setExpiresAt(T0.plusSeconds(60 * 60 * 24 * 30));
        devices.save(device);
    }
}
