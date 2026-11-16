package com.sellerops.reviewissue;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.sellerops.reviewimport.ReviewSegmentIngestedEvent;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * The upload path publishes {@link ReviewSegmentIngestedEvent} with no transaction open (2026-11-16, product analysis
 * QA): the listener must still refresh the issue memory. Uses the container's own listener machinery — the property
 * under test is the annotation, which a hand-called method would not exercise.
 */
class ReviewIssueRefreshOutsideTransactionTest {

    @Configuration
    @EnableTransactionManagement
    static class Wiring {
        @Bean
        ReviewIssueRefreshService refresh() {
            return mock(ReviewIssueRefreshService.class);
        }

        @Bean
        ReviewIssueImportRefreshListener listener(ReviewIssueRefreshService refresh) {
            return new ReviewIssueImportRefreshListener(refresh);
        }

        @Bean
        PlatformTransactionManager transactionManager() {
            return new DataSourceTransactionManager(
                    new DriverManagerDataSource("jdbc:h2:mem:refresh_outside_tx;DB_CLOSE_DELAY=-1"));
        }
    }

    @Test
    @DisplayName("an event published outside any transaction (the file upload path) still refreshes the issue memory")
    void refreshesWithoutATransaction() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(Wiring.class)) {
            UUID org = UUID.randomUUID();
            ctx.publishEvent(new ReviewSegmentIngestedEvent(org, UUID.randomUUID(), LocalDate.parse("2026-11-16")));
            verify(ctx.getBean(ReviewIssueRefreshService.class))
                    .refresh(eq(org), eq(LocalDate.parse("2026-11-16")), anyInt());
        }
    }
}
