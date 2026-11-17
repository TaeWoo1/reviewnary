package com.sellerops.agent.llm.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * Boots a real {@link SpringApplication} (no beans, no database, no transport, no model call) with the OS
 * environment shaped the way the root docker-compose.yml shapes it: every capability's own
 * {@code SELLEROPS_<CAP>_API_KEY} present and blank, and the investigator's vendor defaulted to OPENAI.
 */
class RunyourRouterKeyPostProcessorTest {

    private static final String ROUTER = "synthetic-router-key";

    @Configuration(proxyBeanMethods = false)
    static class Empty {
    }

    /** Compose's backend environment for these keys, verbatim in shape (values blank unless given). */
    private static Map<String, Object> composeEnv() {
        Map<String, Object> env = new HashMap<>();
        for (String cap : List.of("AGENT_PLAN", "AGENT_DRAFT", "AGENT_JUDGE", "AGENT_CONVERSE", "AGENT_REPORT",
                "KNOWLEDGE_EMBEDDING", "KNOWLEDGE_INTENT", "KNOWLEDGE_ELIGIBILITY", "REVIEW_MEDIA_VISION", "INQUIRY_GOAL",
                "INQUIRY_DECISION", "INQUIRY_SIGNATURE", "RESPONSIBILITY_INVESTIGATION", "AGENT_REPLY", "AI_TRIAGE",
                "REVIEW_ASPECT")) {
            env.put("SELLEROPS_" + cap + "_API_KEY", "");
        }
        env.put("SELLEROPS_RESPONSIBILITY_INVESTIGATION_VENDOR", "OPENAI");
        env.put("SELLEROPS_RESPONSIBILITY_INVESTIGATION_MODEL", "gpt-5-2025-08-07");
        return env;
    }

    private static ConfigurableEnvironment boot(Map<String, Object> osEnv) {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, osEnv));
        SpringApplication app = new SpringApplication(Empty.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setEnvironment(env);
        app.setLogStartupInfo(false);
        try (ConfigurableApplicationContext ctx = app.run()) {
            return ctx.getEnvironment();
        }
    }

    private static String key(ConfigurableEnvironment env, String capability) {
        return env.getProperty("sellerops." + capability + ".api-key");
    }

    @Test
    void underTheProfileTheRouterKeyReachesEveryRoutedCapabilityWhoseVendorIsRunyour() {
        Map<String, Object> os = composeEnv();
        os.put("SPRING_PROFILES_INCLUDE", "runyour");
        os.put("RUNYOUR_API_KEY", ROUTER);
        ConfigurableEnvironment env = boot(os);
        for (String capability : RunyourRouterKeyPostProcessor.ROUTED) {
            if (capability.equals("responsibility.investigation")) continue;
            assertThat(env.getProperty("sellerops." + capability + ".vendor")).as(capability).isEqualTo("RUNYOUR");
            assertThat(key(env, capability)).as(capability).isEqualTo(ROUTER);
        }
    }

    /** Compose's OPENAI default for the investigator outranks the profile: it must NOT receive the router key. */
    @Test
    void aCapabilityLeftOnADirectVendorNeverReceivesTheRouterKey() {
        Map<String, Object> os = composeEnv();
        os.put("SPRING_PROFILES_INCLUDE", "runyour");
        os.put("RUNYOUR_API_KEY", ROUTER);
        ConfigurableEnvironment env = boot(os);
        assertThat(env.getProperty("sellerops.responsibility.investigation.vendor")).isEqualTo("OPENAI");
        assertThat(key(env, "responsibility.investigation")).isEmpty();

        os.put("SELLEROPS_RESPONSIBILITY_INVESTIGATION_VENDOR", "RUNYOUR");
        assertThat(key(boot(os), "responsibility.investigation")).isEqualTo(ROUTER);
    }

    /** A plain `docker compose up` with a router key but no profile: every key stays blank (fail closed). */
    @Test
    void withoutTheProfileTheRouterKeyGoesNowhere() {
        Map<String, Object> os = composeEnv();
        os.put("RUNYOUR_API_KEY", ROUTER);
        ConfigurableEnvironment env = boot(os);
        for (String capability : RunyourRouterKeyPostProcessor.ROUTED) {
            assertThat(key(env, capability)).as(capability).isNotEqualTo(ROUTER);
            assertThat(env.getProperty("sellerops." + capability + ".vendor")).as(capability).isNotEqualTo("RUNYOUR");
        }
        assertThat(env.getPropertySources().contains(RunyourRouterKeyPostProcessor.SOURCE_NAME)).isFalse();

        // A vendor named RUNYOUR by hand is still not the profile: only the profile opts into the shared key.
        os.put("SELLEROPS_AGENT_DRAFT_VENDOR", "RUNYOUR");
        assertThat(key(boot(os), "agent.draft")).isEmpty();
    }

    @Test
    void anExplicitKeyIsNeverReplaced() {
        Map<String, Object> os = composeEnv();
        os.put("SPRING_PROFILES_INCLUDE", "runyour");
        os.put("RUNYOUR_API_KEY", ROUTER);
        os.put("SELLEROPS_AGENT_DRAFT_API_KEY", "explicit-not-a-real-key");
        assertThat(key(boot(os), "agent.draft")).isEqualTo("explicit-not-a-real-key");
    }

    @Test
    void theProfileWithoutARouterKeyAddsNothing() {
        Map<String, Object> os = composeEnv();
        os.put("SPRING_PROFILES_INCLUDE", "runyour");
        ConfigurableEnvironment env = boot(os);
        assertThat(key(env, "agent.draft")).isEmpty();
        assertThat(env.getPropertySources().contains(RunyourRouterKeyPostProcessor.SOURCE_NAME)).isFalse();
    }

    /**
     * The list is written by hand, and a capability added to the profile but not to the list boots a pilot that refuses
     * to start («키가 없습니다») the moment it is switched on — measured 2026-11-17 with review.aspect (B206). So the list is
     * held to the profile file itself: every capability the profile routes to RUNYOUR is in it, and nothing else is.
     */
    @Test
    void theRoutedListIsExactlyWhatTheProfileRoutes() throws Exception {
        Pattern vendor = Pattern.compile("^sellerops\\.([\\w.-]+)\\.vendor:\\s*RUNYOUR\\s*$");
        Set<String> routedByProfile = new TreeSet<>();
        for (String line : Files.readAllLines(Path.of("src/main/resources/application-runyour.yml"))) {
            Matcher m = vendor.matcher(line);
            if (m.matches()) {
                routedByProfile.add(m.group(1));
            }
        }
        assertThat(routedByProfile).isNotEmpty().contains("agent.draft", "review.aspect");
        assertThat(new TreeSet<>(RunyourRouterKeyPostProcessor.ROUTED)).isEqualTo(routedByProfile);
    }
}
