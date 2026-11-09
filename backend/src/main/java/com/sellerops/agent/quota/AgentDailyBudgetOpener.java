package com.sellerops.agent.quota;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the day's budget row in its own transaction, so two first charges of a day that race each other both end up
 * locking the one row: the loser of the insert sees the unique constraint and goes on to lock the winner's.
 */
@Component
public class AgentDailyBudgetOpener {

    private final AgentDailyBudgetRepository budgets;

    public AgentDailyBudgetOpener(AgentDailyBudgetRepository budgets) {
        this.budgets = budgets;
    }

    /**
     * Insert the day's row. A charge that loses the race gets the unique-constraint failure out of this transaction (it
     * must not be swallowed in here — the transaction would still roll back and say so) and goes on to lock the row the
     * winner opened ({@code AgentQuotaService#lockDay}).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void open(UUID orgId, LocalDate day) {
        AgentDailyBudget row = new AgentDailyBudget();
        row.setOrgId(orgId);
        row.setUsageDate(day);
        budgets.saveAndFlush(row);
    }
}
