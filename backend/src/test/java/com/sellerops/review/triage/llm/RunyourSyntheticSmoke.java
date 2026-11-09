package com.sellerops.review.triage.llm;

import java.util.concurrent.atomic.AtomicInteger;

/** One synthetic review, no Spring context or database, and no retry. Test source only. */
final class RunyourSyntheticSmoke {
    private RunyourSyntheticSmoke() {
    }

    /**
     * {@code failureReason} is safe to print: every value the classifier and parser produce is a fixed phrase, an
     * HTTP status, a vendor stop enum or a transport exception's class name, never the vendor body or the request.
     */
    record Report(ReviewTriageClassifier.Status status, String tier, String reasonCode, String failureReason,
            int calls) {
    }

    static Report run(boolean approved, String model, String key, LlmHttpClient transport) {
        if (!approved) {
            throw new IllegalStateException("Synthetic smoke is not enabled");
        }
        if (model == null || !model.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,119}")) {
            throw new IllegalArgumentException("Set the explicitly reviewed smoke model");
        }
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Runyour key is missing");
        }
        AtomicInteger calls = new AtomicInteger();
        LlmHttpClient once = (uri, headers, body) -> {
            if (calls.incrementAndGet() > 1) {
                throw new IllegalStateException("Synthetic smoke permits only one call");
            }
            return transport.post(uri, headers, body);
        };
        var classifier = new ApiTriageClassifier(once, ApiTriageClassifier.Vendor.RUNYOUR, model, key,
                new ApiTriageClassifier.Tuning(false, 300, null));
        var result = classifier.classify(new ReviewTriageClassifier.Input(2, "포장이 찌그러져서 왔어요"));
        // No vendor text, key, headers, request or free-form failure message reaches the report.
        return new Report(result.status(), result.tier() == null ? null : result.tier().name(),
                result.reasonCode(), result.failureReason(), calls.get());
    }
}
