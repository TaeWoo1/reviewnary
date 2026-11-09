package com.sellerops.agent.quota;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.organization.Organization;
import com.sellerops.organization.OrganizationRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

/** What {@link DevLlmBudget} reads, over the real schema: tokens since the start, and calls charged without tokens yet. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class DevLlmBudgetQueryTest {

    @Autowired AgentLlmUsageRepository usage;
    @Autowired OrganizationRepository organizations;

    private AgentLlmUsage row(UUID org, Integer in, Integer out) {
        AgentLlmUsage u = new AgentLlmUsage();
        u.setOrgId(org);
        u.setUsageDate(LocalDate.now());
        u.setKind(AgentUsageKind.DRAFT);
        u.setActor(AgentUsageActor.USER);
        u.setStep("FIGURE_CHECK");
        u.setInputTokens(in);
        u.setOutputTokens(out);
        return usage.saveAndFlush(u);
    }

    @Test
    @DisplayName("tokens of returned calls are summed, a call with none yet is counted apart, and another org is not read")
    void spentSince() {
        Organization o = new Organization();
        o.setName("평가 예산 QA");
        UUID org = organizations.save(o).getId();
        Organization other = new Organization();
        other.setName("다른 가게");
        UUID otherOrg = organizations.save(other).getId();
        Instant start = Instant.now().minusSeconds(5);
        row(org, 1000, 100);
        row(org, 2000, 200);
        row(org, null, null);
        row(otherOrg, 9999, 9999);

        Object[] t = usage.spentSince(org, start).get(0);
        assertThat(((Number) t[0]).longValue()).isEqualTo(3000);
        assertThat(((Number) t[1]).longValue()).isEqualTo(300);
        assertThat(((Number) t[2]).longValue()).isEqualTo(1);
        // Nothing since the start — the first call of an evaluation (2026-11-05: a null third column failed it).
        Object[] later = usage.spentSince(org, Instant.now().plusSeconds(60)).get(0);
        assertThat(((Number) later[0]).longValue()).as("nothing after the start").isZero();
        assertThat(later[2]).as("no pending count is zero, not null").isNotNull();
        assertThat(((Number) later[2]).longValue()).isZero();
        DevLlmBudget budget = new DevLlmBudget(1, Instant.now().plusSeconds(60).toString(), 4, 20, 800, 2400, 4000);
        assertThat(budget.allows(usage, org, null)).as("the first call of a fresh budget starts").isTrue();
    }
}
