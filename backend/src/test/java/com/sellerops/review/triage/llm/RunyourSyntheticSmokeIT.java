package com.sellerops.review.triage.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Explicit paid connection check. Never enable during normal tests or without the scoped call approval. */
@EnabledIfEnvironmentVariable(named = "RUN_RUNYOUR_SYNTHETIC_SMOKE", matches = "true")
class RunyourSyntheticSmokeIT {
    @Test
    void classifyOneSyntheticReview() {
        var report = RunyourSyntheticSmoke.run(
                "true".equals(System.getenv("RUN_RUNYOUR_SYNTHETIC_SMOKE")),
                System.getenv("RUNYOUR_SMOKE_MODEL"), System.getenv("RUNYOUR_API_KEY"),
                new JdkLlmHttpClient());
        System.out.println("Runyour synthetic smoke: " + report);
        assertThat(report.status()).as("Synthetic connection result: %s", report)
                .isEqualTo(ReviewTriageClassifier.Status.OK);
    }
}
