package com.sellerops.connector.cafe24;

import com.sellerops.ingest.IngestionService;
import com.sellerops.inquiry.InquiryRepository;
import com.sellerops.inquiry.publish.InquiryExecutionRepository;
import com.sellerops.inquiry.recheck.InquiryRecheckAccountWaitRepository;
import com.sellerops.inquiry.recheck.InquiryRecheckDailyUsageRepository;
import com.sellerops.inquiry.recheck.InquiryRecheckLedger;
import com.sellerops.inquiry.recheck.InquiryRecheckRunLockRepository;
import com.sellerops.inquiry.workitem.InquiryWorkItemRepository;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the scheduled open-inquiry recheck (2026-11-08) — <b>only</b> when both the Cafe24 connector
 * ({@code sellerops.connector.cafe24.enabled}) and the recheck's own flag ({@code sellerops.inquiry.recheck.auto-enabled})
 * are {@code true}. Neither is on unless set, so by default none of these beans exists and nothing runs. Scheduled
 * collection itself needs {@code sellerops.collect.scheduler-enabled} as well.
 *
 * <p>The runner shares the connector's {@link Cafe24HttpClient} and {@link Cafe24Authorizer} beans: the token refresh
 * goes through the same per-account lock as the routine sweep's, so the single-use refresh token is never spent twice.
 */
@Configuration
@ConditionalOnProperty(name = {"sellerops.connector.cafe24.enabled", "sellerops.inquiry.recheck.auto-enabled"},
        havingValue = "true")
public class Cafe24AnswerRecheckConfiguration {

    @Bean
    InquiryRecheckLedger inquiryRecheckLedger(InquiryRecheckDailyUsageRepository usage,
                                              InquiryRecheckRunLockRepository locks,
                                              InquiryRecheckAccountWaitRepository waits) {
        return new InquiryRecheckLedger(usage, locks, waits, Clock.systemUTC());
    }

    @Bean
    Cafe24AnswerRecheckRunner cafe24AnswerRecheckRunner(Cafe24HttpClient http, Cafe24Authorizer authorizer,
                                                        InquiryRepository inquiries,
                                                        InquiryWorkItemRepository workItems,
                                                        InquiryExecutionRepository executions,
                                                        IngestionService ingestion, InquiryRecheckLedger ledger,
                                                        Cafe24RecheckProperties properties) {
        return new Cafe24AnswerRecheckRunner(http, authorizer, inquiries, workItems, executions, ingestion, ledger,
                properties.policy(), Clock.systemUTC());
    }

    @Bean
    Cafe24AnswerRecheckAfterCollection cafe24AnswerRecheckAfterCollection(Cafe24AnswerRecheckRunner runner) {
        return new Cafe24AnswerRecheckAfterCollection(runner);
    }
}
