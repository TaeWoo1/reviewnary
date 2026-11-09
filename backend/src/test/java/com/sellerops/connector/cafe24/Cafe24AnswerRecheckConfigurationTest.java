package com.sellerops.connector.cafe24;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.sellerops.collect.ScheduledCollectionFollowUp;
import com.sellerops.ingest.IngestionService;
import com.sellerops.inquiry.InquiryRepository;
import com.sellerops.inquiry.publish.InquiryExecutionRepository;
import com.sellerops.inquiry.recheck.InquiryRecheckAccountWaitRepository;
import com.sellerops.inquiry.recheck.InquiryRecheckDailyUsageRepository;
import com.sellerops.inquiry.recheck.InquiryRecheckLedger;
import com.sellerops.inquiry.recheck.InquiryRecheckRunLockRepository;
import com.sellerops.inquiry.workitem.InquiryWorkItemRepository;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

/**
 * The scheduled open-inquiry recheck is off unless it is turned on (2026-11-08): without both the Cafe24 connector flag and
 * {@code sellerops.inquiry.recheck.auto-enabled}, no runner and no follow-up exists, so nothing can run it. The shipped
 * settings keep it off and carry the provisional numbers.
 */
class Cafe24AnswerRecheckConfigurationTest {

    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
            .withUserConfiguration(Cafe24AnswerRecheckConfiguration.class)
            .withBean(Cafe24HttpClient.class, () -> mock(Cafe24HttpClient.class))
            .withBean(Cafe24Authorizer.class, () -> mock(Cafe24Authorizer.class))
            .withBean(InquiryRepository.class, () -> mock(InquiryRepository.class))
            .withBean(InquiryWorkItemRepository.class, () -> mock(InquiryWorkItemRepository.class))
            .withBean(InquiryExecutionRepository.class, () -> mock(InquiryExecutionRepository.class))
            .withBean(IngestionService.class, () -> mock(IngestionService.class))
            .withBean(InquiryRecheckDailyUsageRepository.class, () -> mock(InquiryRecheckDailyUsageRepository.class))
            .withBean(InquiryRecheckRunLockRepository.class, () -> mock(InquiryRecheckRunLockRepository.class))
            .withBean(InquiryRecheckAccountWaitRepository.class, () -> mock(InquiryRecheckAccountWaitRepository.class))
            .withBean(Cafe24RecheckProperties.class,
                    () -> new Cafe24RecheckProperties(20, "1,2,4,7", 10, "120,240,480"));

    @Test
    void nothingCanRunTheRecheckUnlessBothFlagsAreOn() {
        for (String[] props : List.of(
                new String[] {},
                new String[] {"sellerops.inquiry.recheck.auto-enabled=true"},
                new String[] {"sellerops.connector.cafe24.enabled=true"},
                new String[] {"sellerops.connector.cafe24.enabled=true", "sellerops.inquiry.recheck.auto-enabled=false"},
                new String[] {"sellerops.connector.cafe24.enabled=false", "sellerops.inquiry.recheck.auto-enabled=true"})) {
            contexts.withPropertyValues(props).run(context -> assertThat(context)
                    .as(String.join(" ", props))
                    .doesNotHaveBean(ScheduledCollectionFollowUp.class)
                    .doesNotHaveBean(Cafe24AnswerRecheckRunner.class)
                    .doesNotHaveBean(InquiryRecheckLedger.class));
        }
        contexts.withPropertyValues("sellerops.connector.cafe24.enabled=true",
                        "sellerops.inquiry.recheck.auto-enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(Cafe24AnswerRecheckAfterCollection.class);
                    assertThat(context).hasSingleBean(Cafe24AnswerRecheckRunner.class);
                    assertThat(context.getBean(ScheduledCollectionFollowUp.class))
                            .isSameAs(context.getBean(Cafe24AnswerRecheckAfterCollection.class));
                });
    }

    @Test
    void theShippedSettingsKeepItOffAndCarryTheProvisionalNumbers() throws Exception {
        MockEnvironment env = new MockEnvironment();
        for (PropertySource<?> source : new YamlPropertySourceLoader().load("application",
                new ClassPathResource("application.yml"))) {
            env.getPropertySources().addLast(source);
        }
        assertThat(env.getProperty("sellerops.inquiry.recheck.auto-enabled", Boolean.class)).isFalse();

        Cafe24RecheckPolicy shipped = new Cafe24RecheckProperties(
                env.getRequiredProperty("sellerops.inquiry.recheck.daily-request-limit", Integer.class),
                env.getRequiredProperty("sellerops.inquiry.recheck.retry-after-days"),
                env.getRequiredProperty("sellerops.inquiry.recheck.run-lease-minutes", Long.class),
                env.getRequiredProperty("sellerops.inquiry.recheck.account-wait-minutes")).policy();
        assertThat(shipped).as("the provisional pilot values, not operating ones")
                .isEqualTo(Cafe24RecheckPolicy.PROVISIONAL);
        assertThat(shipped.accountWaitAfter(1)).isEqualTo(Duration.ofHours(2));
        assertThat(shipped.accountWaitAfter(9)).as("the last value repeats").isEqualTo(Duration.ofHours(8));
    }
}
