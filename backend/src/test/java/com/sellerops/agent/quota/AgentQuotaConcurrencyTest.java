package com.sellerops.agent.quota;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * <b>Two requests cannot both take the last call</b> (outside review, 2026-10-29: with a ceiling of 1, two concurrent
 * requests were both allowed and wrote two rows). The usage repository is wrapped so that each request, once it has
 * counted, waits for the other to count too — exactly the interleaving the review reproduced. Without the day lock both
 * meet there and both pass; with it the second cannot count until the first has recorded, so it sees the call taken.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import(AgentDailyBudgetOpener.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AgentQuotaConcurrencyTest {

    @Autowired AgentLlmUsageRepository usage;
    @Autowired AgentDailyBudgetRepository budgets;
    @Autowired AgentDailyBudgetOpener opener;
    @Autowired PlatformTransactionManager txManager;

    private final ExecutorService pool = Executors.newFixedThreadPool(2);

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
        usage.deleteAll();
        budgets.deleteAll();
    }

    /** The real repository, except that a count waits briefly for the other request to count as well. */
    private AgentLlmUsageRepository meetingAtTheCount(CyclicBarrier barrier) {
        return (AgentLlmUsageRepository) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {AgentLlmUsageRepository.class}, (proxy, method, args) -> {
                    Object result;
                    try {
                        result = method.invoke(usage, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                    if (method.getName().equals("countSellerAndRunCalls")) {
                        try {
                            barrier.await(300, TimeUnit.MILLISECONDS);
                        } catch (TimeoutException | BrokenBarrierException alone) {
                            // The other request is held at the lock: this one goes on alone.
                        }
                    }
                    return result;
                });
    }

    private List<Boolean> twoAtOnce(AgentQuotaService quota, UUID org) throws Exception {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        Callable<Boolean> charge = () -> tx.execute(s -> quota.consumeStep(org, null, "CLASSIFY", "INGEST",
                AgentUsageActor.RUN).decision().allowed());
        List<Future<Boolean>> both = List.of(pool.submit(charge), pool.submit(charge));
        List<Boolean> out = new ArrayList<>();
        for (Future<Boolean> f : both) {
            out.add(f.get(10, TimeUnit.SECONDS));
        }
        return out;
    }

    @Test
    @DisplayName("with the day lock, a ceiling of one lets exactly one of two concurrent requests through")
    void theLockHoldsTheCeiling() throws Exception {
        UUID org = UUID.randomUUID();
        AgentQuotaService quota = new AgentQuotaService(meetingAtTheCount(new CyclicBarrier(2)),
                new AgentQuotaProperties(true, true, true, 100, 1, 100));
        quota.setBudgets(budgets, opener);

        assertThat(twoAtOnce(quota, org)).containsExactlyInAnyOrder(true, false);
        assertThat(usage.findAll()).filteredOn(u -> u.getOrgId().equals(org)).hasSize(1);
    }

    @Test
    @DisplayName("without it, the same interleaving lets both through — the defect the lock closes")
    void withoutTheLockBothPass() throws Exception {
        UUID org = UUID.randomUUID();
        AgentQuotaService quota = new AgentQuotaService(meetingAtTheCount(new CyclicBarrier(2)),
                new AgentQuotaProperties(true, true, true, 100, 1, 100));

        assertThat(twoAtOnce(quota, org)).containsExactly(true, true);
        assertThat(usage.findAll()).filteredOn(u -> u.getOrgId().equals(org)).hasSize(2);
    }
}
