package com.sellerops.knowledge.currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sellerops.knowledge.claim.ClaimAttribute;
import com.sellerops.knowledge.claim.ClaimCondition;
import com.sellerops.knowledge.org.OrgKnowledgeSource;
import com.sellerops.knowledge.org.OrgKnowledgeSourceRepository;
import com.sellerops.knowledge.schedule.KnowledgeScheduleRepository;
import com.sellerops.product.ProductRepository;
import com.sellerops.product.library.ProductKnowledgeSource;
import com.sellerops.product.library.ProductKnowledgeSourceRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 「이 자료가 현행」 as the seller presses it, in any order (2026-11-15 review: 세 자료의 대체 순환 · 연쇄). The latest choice
 * wins and what would lead back round to it is removed — for that attribute and condition only — so no stored set of
 * choices can call two documents current over each other.
 */
class SupersedeChoiceTest {

    private final UUID org = UUID.randomUUID();
    private final OrgKnowledgeSourceRepository orgSources = mock(OrgKnowledgeSourceRepository.class);
    private final ProductKnowledgeSourceRepository productSources = mock(ProductKnowledgeSourceRepository.class);
    private final KnowledgeSupersessionRepository repo = mock(KnowledgeSupersessionRepository.class);
    private final List<KnowledgeSupersession> stored = new ArrayList<>();
    private KnowledgeCurrencyService service;
    private final UUID a = doc();
    private final UUID b = doc();
    private final UUID c = doc();

    private UUID doc() {
        return UUID.randomUUID();
    }

    @BeforeEach
    void setUp() {
        service = new KnowledgeCurrencyService(orgSources, productSources, mock(KnowledgeSourceCurrencyRepository.class),
                repo, mock(KnowledgeScheduleRepository.class), mock(ProductRepository.class));
        for (UUID id : List.of(a, b, c)) {
            OrgKnowledgeSource s = new OrgKnowledgeSource();
            s.setId(id);
            s.setOrgId(org);
            when(orgSources.findByIdAndOrgId(id, org)).thenReturn(Optional.of(s));
            when(productSources.findByIdAndOrgId(id, org)).thenReturn(Optional.empty());
        }
        when(repo.findAllByOrgId(org)).thenAnswer(inv -> List.copyOf(stored));
        when(repo.save(any(KnowledgeSupersession.class))).thenAnswer(inv -> {
            KnowledgeSupersession s = inv.getArgument(0);
            if (s.getId() == null) {
                s.setId(UUID.randomUUID());
            }
            stored.add(s);
            return s;
        });
        when(repo.findByIdAndOrgId(any(), any())).thenAnswer(inv -> stored.stream()
                .filter(s -> s.getId().equals(inv.getArgument(0))).findFirst());
        doAnswer(inv -> stored.remove((KnowledgeSupersession) inv.getArgument(0))).when(repo)
                .delete(any(KnowledgeSupersession.class));
    }

    private void pick(UUID newer, UUID older, ClaimAttribute attribute) {
        service.supersede(org, newer, older, attribute, ClaimCondition.GENERAL, "판매자");
    }

    private List<String> edges(ClaimAttribute attribute) {
        return stored.stream().filter(s -> s.getAttribute() == attribute)
                .map(s -> name(s.getNewerSourceId()) + ">" + name(s.getOlderSourceId())).sorted().toList();
    }

    private String name(UUID id) {
        return id.equals(a) ? "A" : id.equals(b) ? "B" : "C";
    }

    @Test
    @DisplayName("A>B, B>C, then C>A: B>C is removed, the rest stands, and no cycle is ever stored")
    void theLatestChoiceBreaksTheLoop() {
        pick(a, b, ClaimAttribute.EXCHANGE_WINDOW);
        pick(b, c, ClaimAttribute.EXCHANGE_WINDOW);
        pick(c, a, ClaimAttribute.EXCHANGE_WINDOW);
        assertThat(edges(ClaimAttribute.EXCHANGE_WINDOW)).containsExactly("A>B", "C>A");
    }

    @Test
    @DisplayName("the other side of a direct pair replaces it, as before; the same pick twice is one row")
    void theOppositeAndTheSame() {
        pick(a, b, ClaimAttribute.EXCHANGE_WINDOW);
        pick(a, b, ClaimAttribute.EXCHANGE_WINDOW);
        assertThat(edges(ClaimAttribute.EXCHANGE_WINDOW)).containsExactly("A>B");
        pick(b, a, ClaimAttribute.EXCHANGE_WINDOW);
        assertThat(edges(ClaimAttribute.EXCHANGE_WINDOW)).containsExactly("B>A");
    }

    @Test
    @DisplayName("another attribute keeps its choices: a loop in the exchange window touches no return-window row")
    void perAttribute() {
        pick(b, c, ClaimAttribute.RETURN_WINDOW);
        pick(a, b, ClaimAttribute.EXCHANGE_WINDOW);
        pick(b, c, ClaimAttribute.EXCHANGE_WINDOW);
        pick(c, a, ClaimAttribute.EXCHANGE_WINDOW);
        assertThat(edges(ClaimAttribute.RETURN_WINDOW)).containsExactly("B>C");
    }

    @Test
    @DisplayName("a company document and a product document do not replace each other")
    void scopes() {
        UUID product = UUID.randomUUID();
        ProductKnowledgeSource p = new ProductKnowledgeSource();
        p.setId(product);
        p.setOrgId(org);
        p.setProductId(UUID.randomUUID());
        when(productSources.findByIdAndOrgId(product, org)).thenReturn(Optional.of(p));
        assertThatThrownBy(() -> pick(product, a, ClaimAttribute.EXCHANGE_WINDOW))
                .hasMessageContaining("서로 대신하지 않아요");
    }
}
