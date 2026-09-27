package com.sellerops.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>A capability the container cannot SEE is off however carefully the host env file is written.</b>
 *
 * <p>{@code docker compose --env-file} feeds compose's own variable interpolation. A backend
 * environment variable reaches the process only if its name also appears under {@code environment:}
 * in {@code docker-compose.yml}. That is not a nuance an operator can be expected to hold, and this
 * repository has now shipped the same defect three times: the Agent capabilities (Pilot Readiness
 * Closure v1 §3), the social-login client ids (Pilot Release Closure v1 §7-3), and the three inquiry
 * capabilities plus review AI triage found by the RC5 baseline audit.
 *
 * <p>Every one of them failed the same way — silently. The flag was true on the host, false in the
 * process, and the only symptom was a product that did less than it was configured to do.
 *
 * <p><b>Scope is deliberate.</b> This asserts the three names that TURN A CAPABILITY ON — flag, key,
 * organisation list — and not the model / vendor / effort overrides beside them. Those are excluded
 * from compose on purpose: written as {@code ${NAME:-}} they hand the container an empty string and
 * erase a configured default, and restating each default here would put it in a second place.
 */
class CapabilityEnvWiringTest {

    private static final Path COMPOSE = Path.of("..", "docker-compose.yml");
    private static final Path PILOT_ENV = Path.of("..", "deploy", "pilot", "pilot.env.example");
    private static final Path APPLICATION_YML = Path.of("src/main/resources/application.yml");

    /** Every capability whose switch has to be reachable for the pilot demo path. */
    private static final List<String> CAPABILITY_TRIPLES = List.of(
            "SELLEROPS_AGENT_PLAN", "SELLEROPS_AGENT_DRAFT", "SELLEROPS_AGENT_JUDGE",
            "SELLEROPS_AGENT_CONVERSE", "SELLEROPS_AGENT_REPORT",
            "SELLEROPS_KNOWLEDGE_EMBEDDING", "SELLEROPS_KNOWLEDGE_INTENT", "SELLEROPS_KNOWLEDGE_ELIGIBILITY",
            "SELLEROPS_REVIEW_MEDIA_VISION",
            // The three the audit found unplumbed. Each is an AgentCapabilityGate bean exactly like
            // the five above, and PilotConfigValidator already iterates over them — so a host could
            // not turn one on, and could not be told why.
            "SELLEROPS_INQUIRY_GOAL", "SELLEROPS_INQUIRY_DECISION", "SELLEROPS_INQUIRY_SIGNATURE");

    /** Review AI triage: the same three facts under names that do not share the triple's shape. */
    private static final List<String> AI_TRIAGE = List.of(
            "SELLEROPS_AI_TRIAGE_PILOT_ENABLED",
            "SELLEROPS_AI_TRIAGE_PILOT_ORG_IDS",
            "SELLEROPS_AI_TRIAGE_API_KEY",
            // Without this one the pilot classifies only when an operator asks: the review list never
            // marks itself, which is the half of 「AI 확인 필요」 a demo actually shows.
            "SELLEROPS_SELF_PILOT_TRIAGE_AUTO_ENABLED");

    /**
     * Unattended NAVER review export: the same three facts again, and again under names that do not share
     * the triple's shape — it calls no vendor, so it has no API key, and it names a DEVICE as well as an
     * organisation. Listed here for the reason this whole class exists: an unplumbed name means an
     * operator writes the flag, the container never sees it, and the agent's every call is refused with
     * nothing to read that says why.
     */
    private static final List<String> UNATTENDED_REVIEW_EXPORT = List.of(
            "SELLEROPS_REVIEW_IMPORT_UNATTENDED_ENABLED",
            "SELLEROPS_REVIEW_IMPORT_UNATTENDED_ORG_IDS",
            "SELLEROPS_REVIEW_IMPORT_UNATTENDED_DEVICE_IDS");

    private static List<String> requiredNames() {
        List<String> names = new ArrayList<>();
        for (String cap : CAPABILITY_TRIPLES) {
            names.add(cap + "_ENABLED");
            names.add(cap + "_API_KEY");
            names.add(cap + "_ORG_IDS");
        }
        names.addAll(AI_TRIAGE);
        names.addAll(UNATTENDED_REVIEW_EXPORT);
        return names;
    }

    @Test
    @DisplayName("every capability switch the backend reads is passed into the backend container")
    void composePassesEveryCapabilitySwitch() throws Exception {
        String compose = Files.readString(COMPOSE);
        String yml = Files.readString(APPLICATION_YML);
        for (String name : requiredNames()) {
            // First that the backend actually reads it — a name in compose that nothing binds is the
            // other half of the same confusion, and it would make this test pass by accident.
            assertThat(yml).as("application.yml binds %s", name).contains("${" + name + ":");
            assertThat(compose).as("docker-compose.yml passes %s to the backend", name)
                    .contains(name + ": ${" + name);
        }
    }

    @Test
    @DisplayName("the pilot template names every flag and key, so an operator has a name to copy")
    void pilotTemplateNamesThem() throws Exception {
        String env = Files.readString(PILOT_ENV);
        for (String cap : CAPABILITY_TRIPLES) {
            assertThat(env).as("pilot.env.example names %s_ENABLED", cap)
                    .containsPattern("(?m)^" + cap + "_ENABLED=");
            assertThat(env).as("pilot.env.example names %s_API_KEY", cap)
                    .containsPattern("(?m)^" + cap + "_API_KEY=");
        }
        for (String name : AI_TRIAGE) {
            assertThat(env).as("pilot.env.example names %s", name).containsPattern("(?m)^" + name + "=");
        }
        for (String name : UNATTENDED_REVIEW_EXPORT) {
            assertThat(env).as("pilot.env.example names %s", name).containsPattern("(?m)^" + name + "=");
        }
        // The organisation list is required of the template only where the capability REQUIRES one:
        // PLAN / DRAFT / JUDGE / CONVERSE are admitted by SELLEROPS_AGENT_ACCESS_SCOPE=CONNECTED_SELLERS,
        // which the template sets and explains, and listing an empty per-capability list beside it would
        // invite an operator to narrow the thing that makes a new pilot seller usable with no env edit.
        // The ones below decline that widening (or, for REPORT and SIGNATURE, were already listed), so
        // the name has to be there for the step that follows the first signup.
        for (String cap : List.of("SELLEROPS_KNOWLEDGE_EMBEDDING", "SELLEROPS_KNOWLEDGE_INTENT",
                "SELLEROPS_KNOWLEDGE_ELIGIBILITY", "SELLEROPS_REVIEW_MEDIA_VISION",
                "SELLEROPS_INQUIRY_GOAL", "SELLEROPS_INQUIRY_DECISION",
                "SELLEROPS_AGENT_REPORT", "SELLEROPS_INQUIRY_SIGNATURE")) {
            assertThat(env).as("pilot.env.example names %s_ORG_IDS", cap)
                    .containsPattern("(?m)^" + cap + "_ORG_IDS=");
        }
    }

    @Test
    @DisplayName("the shipped pilot template can complete a FIRST deploy — nothing is on with no org named")
    void shippedTemplateIsDeployableBeforeAnyOrganisationExists() throws Exception {
        // The ordering trap the RC5 baseline audit found: the three retrieval capabilities shipped
        // `true` with blank organisation lists, and `on + keyed + blank list` is refused by deploy.sh
        // AND by PilotConfigValidator.agentProblems(). The UUID that would satisfy it belongs to an
        // organisation that cannot be created until this stack is serving — so the template could not
        // complete its own first deploy. Neither refusal was relaxed; the template starts off and the
        // runbook turns it on at step 3, with the restart it already budgets for.
        var values = new java.util.LinkedHashMap<String, String>();
        for (String line : Files.readAllLines(PILOT_ENV)) {
            String t = line.strip();
            int eq = t.indexOf('=');
            if (t.startsWith("#") || eq <= 0) {
                continue;
            }
            values.put(t.substring(0, eq), t.substring(eq + 1).strip());
        }
        for (String cap : CAPABILITY_TRIPLES) {
            boolean on = "true".equals(values.get(cap + "_ENABLED"));
            boolean orgNamed = !values.getOrDefault(cap + "_ORG_IDS", "").isBlank();
            assertThat(on && !orgNamed)
                    .as("%s_ENABLED=true with %s_ORG_IDS blank — that template cannot complete a first deploy",
                            cap, cap)
                    .isFalse();
        }
        assertThat("true".equals(values.get("SELLEROPS_AI_TRIAGE_PILOT_ENABLED"))
                && values.getOrDefault("SELLEROPS_AI_TRIAGE_PILOT_ORG_IDS", "").isBlank())
                .as("AI triage ships on with no organisation named")
                .isFalse();
    }

    @Test
    @DisplayName("the review-reply provider is NOT passed through, and the reason is written down")
    void providerStaysOutOfCompose() throws Exception {
        // Its property default is non-blank (`rule_based`) and an unknown value leaves no provider
        // bean, so the application fails to start — deliberately. `${...:-}` would hand every silent
        // deployment an empty string and turn that deliberate refusal into a boot failure nobody asked
        // for. This is the one capability-shaped name whose absence from compose is the correct wiring.
        String compose = Files.readString(COMPOSE);
        assertThat(compose).doesNotContain("SELLEROPS_REVIEW_REPLY_PROVIDER: $");
        assertThat(compose).contains("SELLEROPS_REVIEW_REPLY_PROVIDER is deliberately NOT passed through");
    }
}
