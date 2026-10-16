package com.sellerops.vendor;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * <b>The vendor server carries no channel client, no channel credential store and no AI path</b> —
 * {@code docs/vendor_server_contract_v1.md} D1 · §1 · §10-1.
 *
 * <p>A separate build already keeps backend classes off the classpath; this keeps their substance out of the source
 * — a copied client, a channel host, an analytics SDK. It reads every file under {@code src/main} and the build
 * script, recursively and including resources (FINDINGS §B10), and first proves it is reading the right place by
 * finding the files it knows exist (rule 3). Proven both ways when written: a channel host put into a source string
 * turns it red; removing it turns it green.
 *
 * <p>{@code cafe24api.com} is the one planned exception (the token relay, contract §5) and will be allowed only
 * under {@code relay/cafe24/}; that package does not exist yet, so today it is allowed nowhere.
 */
class VendorServerScopeFenceTest {

    private static final Path MAIN = Path.of("src", "main");
    private static final Path BUILD = Path.of("build.gradle");
    private static final Path CAFE24_RELAY = Path.of("src", "main", "java", "com", "sellerops", "vendor", "relay", "cafe24");

    /** Channel API hosts and the backend packages that hold channel clients and credentials. */
    private static final List<String> FORBIDDEN = List.of(
            "api.commerce.naver.com", "commerce.naver.com", "api-gateway.coupang.com", "coupang.com",
            "esmplus.com", "11st.co.kr", "ssg.com",
            "com.sellerops.connector", "com.sellerops.credential", "credentialvault",
            // No model calls on this server: §17.9 keeps drafting on the seller's PC.
            "api.openai.com", "api.anthropic.com",
            // No analytics, error or session SDKs (§17.9 경계 5). Named by package or key, not by bare word:
            // a bare "sentry" matches Spring's "HttpStatusEntryPoint" (statu-s-entry), which is how this list was
            // first written and why it is not anymore.
            "io.sentry", "sentry.io", "sentry_dsn", "sentry.dsn", "posthog", "googletagmanager",
            "google-analytics", "com.datadoghq", "com.newrelic");

    @Test
    void theServerNamesNoChannelApiCredentialStoreModelOrAnalytics() throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(MAIN)) {
            walk.filter(Files::isRegularFile).forEach(files::add);
        }
        files.add(BUILD);
        assertThat(files.stream().map(p -> p.getFileName().toString()))
                .as("the scan is reading the vendor server")
                .contains("VendorServerApplication.java", "SecurityConfig.java", "application.yml", "build.gradle");

        List<String> offenders = new ArrayList<>();
        for (Path file : files) {
            String text = Files.readString(file).toLowerCase(Locale.ROOT);
            for (String forbidden : FORBIDDEN) {
                if (text.contains(forbidden)) {
                    offenders.add(file + " names " + forbidden);
                }
            }
            if (text.contains("cafe24api.com") && !file.startsWith(CAFE24_RELAY)) {
                offenders.add(file + " names cafe24api.com outside the token relay");
            }
            if (file.equals(BUILD) && (text.contains("project(") || text.contains("../backend"))) {
                offenders.add("build.gradle depends on another project");
            }
        }
        assertThat(offenders).isEmpty();
    }
}
