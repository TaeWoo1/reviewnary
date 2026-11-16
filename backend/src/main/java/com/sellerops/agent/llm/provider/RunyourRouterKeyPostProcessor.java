package com.sellerops.agent.llm.provider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.Profiles;

/**
 * The Runyour router key, handed to a routed capability in memory — and only there.
 *
 * <p>{@code application-runyour.yml} sets every routed capability's {@code api-key} to {@code ${RUNYOUR_API_KEY}}. A
 * launcher that passes each capability's own {@code SELLEROPS_<CAP>_API_KEY} as a blank OS variable (the root
 * {@code docker-compose.yml} does, {@code ${VAR:-}}) outranks that file: the key resolves to "" and the capability
 * boots with no key. Filling those variables from {@code RUNYOUR_API_KEY} in compose would have made the router key a
 * direct vendor's key on every plain {@code docker compose up} without the profile (review, 2026-11-16).
 *
 * <p>So the fill happens here, after the config files are read, under three conditions that must all hold:
 * <ol>
 *   <li>the {@code runyour} profile is active — without it nothing is read or written;</li>
 *   <li>the capability's vendor RESOLVED to {@code RUNYOUR} — a capability an operator (or compose's investigator
 *       default {@code OPENAI}) left on a direct vendor never receives the router key;</li>
 *   <li>its own key resolved blank — an explicit key is never replaced.</li>
 * </ol>
 * Nothing is enabled, no organisation is admitted and nothing is logged; the key stays a property in this process.
 */
public class RunyourRouterKeyPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String SOURCE_NAME = "runyourRouterKey";

    /** Every capability application-runyour.yml routes (RunyourProfileTest#CAPABILITIES). */
    static final List<String> ROUTED = List.of(
            "reply-agent", "triage.ai-pilot", "agent.draft", "agent.report", "agent.plan", "agent.judge", "agent.converse",
            "inquiry.signature", "inquiry-goal", "product.image-knowledge", "knowledge.embedding", "knowledge.intent",
            "knowledge.eligibility", "responsibility.investigation", "inquiry-decision", "review.media-vision");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.acceptsProfiles(Profiles.of("runyour"))) return;
        String routerKey = environment.getProperty("RUNYOUR_API_KEY");
        if (routerKey == null || routerKey.isBlank()) return;
        Map<String, Object> fill = new LinkedHashMap<>();
        for (String capability : ROUTED) {
            String prefix = "sellerops." + capability;
            if (!"RUNYOUR".equalsIgnoreCase(String.valueOf(environment.getProperty(prefix + ".vendor")).trim())) continue;
            String own = environment.getProperty(prefix + ".api-key");
            if (own != null && !own.isBlank()) continue;
            fill.put(prefix + ".api-key", routerKey);
        }
        if (!fill.isEmpty()) environment.getPropertySources().addFirst(new MapPropertySource(SOURCE_NAME, fill));
    }

    /** After the config files (and so the profile and its vendors) are known. */
    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 10;
    }
}
