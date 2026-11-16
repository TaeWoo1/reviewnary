package com.sellerops.knowledge.defaults;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sellerops.common.ApiException;
import com.sellerops.knowledge.claim.ClaimAttribute;
import com.sellerops.knowledge.currency.KnowledgeCurrencyService;
import com.sellerops.knowledge.currency.KnowledgeSourceCurrencyRepository;
import com.sellerops.knowledge.currency.KnowledgeSupersessionRepository;
import com.sellerops.knowledge.org.OrgKnowledgeChunkRepository;
import com.sellerops.knowledge.org.OrgKnowledgeRevisionRepository;
import com.sellerops.knowledge.org.OrgKnowledgeSource;
import com.sellerops.knowledge.org.OrgKnowledgeSourceRepository;
import com.sellerops.knowledge.org.OrgKnowledgeType;
import com.sellerops.knowledge.org.SellerOperationsKnowledgeService;
import com.sellerops.knowledge.org.dto.OrgKnowledgeRequest;
import com.sellerops.knowledge.schedule.KnowledgeScheduleRepository;
import com.sellerops.organization.Organization;
import com.sellerops.organization.OrganizationRepository;
import com.sellerops.product.ProductRepository;
import com.sellerops.product.library.ProductKnowledgeSourceRepository;
import com.sellerops.talktalk.TalkTalkAccess;
import com.sellerops.talktalk.TalkTalkClock;
import com.sellerops.talktalk.TalkTalkConnectionRepository;
import com.sellerops.talktalk.TalkTalkDelegationRepository;
import com.sellerops.talktalk.TalkTalkGuidance;
import com.sellerops.talktalk.TalkTalkGuidanceRepository;
import com.sellerops.talktalk.TalkTalkGuidanceRules;
import com.sellerops.talktalk.TalkTalkGuidanceService;
import com.sellerops.talktalk.TalkTalkOutboundRepository;
import com.sellerops.user.User;
import com.sellerops.user.UserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 기본 설정 질문지 (owner decision 2026-11-17): a typed answer answers nothing; a confirmed one becomes the shop's word
 * through the ordinary org knowledge path; 출고 마감·휴무 come from the TalkTalk guidance and nowhere else.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class DefaultPolicyServiceTest {

    @Autowired DefaultPolicyAnswerRepository answers;
    @Autowired DefaultPolicySourceRepository mapped;
    @Autowired OrgKnowledgeSourceRepository orgSources;
    @Autowired OrgKnowledgeChunkRepository orgChunks;
    @Autowired OrgKnowledgeRevisionRepository orgRevisions;
    @Autowired TalkTalkGuidanceRepository guidanceRepo;
    @Autowired TalkTalkDelegationRepository delegations;
    @Autowired TalkTalkOutboundRepository outbound;
    @Autowired TalkTalkConnectionRepository connections;
    @Autowired OrganizationRepository organizations;
    @Autowired UserRepository users;
    @Autowired ProductKnowledgeSourceRepository productSources;
    @Autowired KnowledgeSourceCurrencyRepository currencyRepo;
    @Autowired KnowledgeSupersessionRepository supersessions;
    @Autowired KnowledgeScheduleRepository schedules;
    @Autowired ProductRepository products;

    private final TalkTalkClock clock = new TalkTalkClock();
    private SellerOperationsKnowledgeService orgKnowledge;
    private DefaultPolicyService service;
    private TalkTalkGuidanceService guidance;
    private KnowledgeCurrencyService currency;
    private UUID org;
    private UUID owner;

    @BeforeEach
    void setUp() {
        clock.set(Instant.parse("2026-11-17T01:00:00Z"));
        orgKnowledge = new SellerOperationsKnowledgeService(orgSources, orgChunks);
        orgKnowledge.setRevisions(orgRevisions);
        TalkTalkAccess access = new TalkTalkAccess(users);
        service = new DefaultPolicyService(answers, mapped, orgSources, orgKnowledge, guidanceRepo, access, clock);
        guidance = new TalkTalkGuidanceService(guidanceRepo, delegations, outbound, access, clock, connections);
        guidance.setListeners(List.of(service));
        currency = new KnowledgeCurrencyService(orgSources, productSources, currencyRepo, supersessions, schedules,
                products);
        org = org("기본 설정 시험 가게");
        owner = user(org, "OWNER");
    }

    private UUID org(String name) {
        Organization o = new Organization();
        o.setName(name);
        return organizations.save(o).getId();
    }

    private UUID user(UUID orgId, String role) {
        User u = new User();
        u.setOrgId(orgId);
        u.setEmail("dp-" + UUID.randomUUID() + "@example.invalid");
        u.setName("담당자");
        u.setRole(role);
        return users.save(u).getId();
    }

    private DefaultPolicyViews.Item item(DefaultPolicyViews.Overview o, DefaultPolicyItem key) {
        return o.items().stream().filter(i -> i.key().equals(key.name())).findFirst().orElseThrow();
    }

    private OrgKnowledgeSource doc(DefaultPolicyGroup group) {
        UUID id = mapped.findByOrgIdAndGroupKey(org, group.name()).orElseThrow().getSourceId();
        return orgSources.findByIdAndOrgId(id, org).orElseThrow();
    }

    @Test
    @DisplayName("열두 질문이 다 나오고, 적기만 한 답은 어떤 자료도 만들지 않는다")
    void draftAnswersNothing() {
        DefaultPolicyViews.Overview o = service.overview(org);
        assertThat(o.total()).isEqualTo(12);
        assertThat(o.confirmed()).isZero();

        o = service.saveDraft(org, owner, "COURIER", null, " CJ대한통운 ");
        assertThat(item(o, DefaultPolicyItem.COURIER).status()).isEqualTo("DRAFT");
        assertThat(item(o, DefaultPolicyItem.COURIER).draftSentence()).isEqualTo("택배사: CJ대한통운");
        assertThat(orgSources.findAllByOrgIdOrderByCreatedAtAsc(org)).isEmpty();
        assertThat(orgKnowledge.search(org, "택배사 어디 쓰세요?", 5).passages()).isEmpty();
    }

    @Test
    @DisplayName("확정하면 그 주제의 가게 기준 문서가 생기고 검색에 걸린다")
    void confirmWritesTheTopicDocument() {
        service.saveDraft(org, owner, "COURIER", null, "CJ대한통운");
        DefaultPolicyViews.Overview o = service.confirm(org, owner, "COURIER", null, "CJ대한통운");

        assertThat(item(o, DefaultPolicyItem.COURIER).status()).isEqualTo("CONFIRMED");
        assertThat(o.confirmed()).isEqualTo(1);
        OrgKnowledgeSource shipping = doc(DefaultPolicyGroup.SHIPPING);
        assertThat(shipping.getKnowledgeType()).isEqualTo(OrgKnowledgeType.SHIPPING_POLICY);
        assertThat(shipping.getTitle()).isEqualTo("배송 안내 (기본 설정)");
        assertThat(shipping.getBody()).isEqualTo("택배사: CJ대한통운");
        assertThat(shipping.isActive()).isTrue();
        assertThat(shipping.getDocumentName()).isNull();
        assertThat(orgKnowledge.search(org, "택배사 어디 쓰세요?", 5).passages())
                .anySatisfy(p -> assertThat(p.content()).contains("CJ대한통운"));
        // Other groups have nothing confirmed and get no document.
        assertThat(mapped.findAllByOrgId(org)).hasSize(1);
    }

    @Test
    @DisplayName("확정한 답을 고치면 확정 값이 계속 쓰이고, 다시 확정해야 바뀐다 (이전 판은 남는다)")
    void editingKeepsTheConfirmedValueUntilReconfirmed() {
        service.saveDraft(org, owner, "RETURN_WINDOW", 7, null);
        service.confirm(org, owner, "RETURN_WINDOW", 7, null);
        DefaultPolicyViews.Overview o = service.saveDraft(org, owner, "RETURN_WINDOW", 14, null);

        assertThat(item(o, DefaultPolicyItem.RETURN_WINDOW).status()).isEqualTo("CHANGED");
        assertThat(item(o, DefaultPolicyItem.RETURN_WINDOW).sentence()).contains("7일");
        assertThat(doc(DefaultPolicyGroup.RETURNS).getBody()).contains("7일");

        service.confirm(org, owner, "RETURN_WINDOW", 14, null);
        OrgKnowledgeSource returns = doc(DefaultPolicyGroup.RETURNS);
        assertThat(returns.getBody()).contains("14일").doesNotContain("7일");
        assertThat(returns.getVersion()).isEqualTo(2);
        assertThat(orgRevisions.findAll()).anySatisfy(r -> assertThat(r.getBody()).contains("7일"));
    }

    @Test
    @DisplayName("화면에서 본 답과 저장된 답이 다르면 확정하지 않는다")
    void staleConfirmIsRefused() {
        service.saveDraft(org, owner, "RETURN_FEE", 6000, null);
        assertThatThrownBy(() -> service.confirm(org, owner, "RETURN_FEE", 5000, null))
                .isInstanceOf(ApiException.class).hasMessageContaining("화면과 달라요");
        assertThatThrownBy(() -> service.confirm(org, owner, "WARRANTY", null, "1년"))
                .isInstanceOf(ApiException.class).hasMessageContaining("먼저 답을 적고");
        assertThat(orgSources.findAllByOrgIdOrderByCreatedAtAsc(org)).isEmpty();
    }

    @Test
    @DisplayName("확정을 내리면 그 주제에 남은 확정 답이 없을 때 문서가 꺼져 아무것도 답하지 않는다")
    void unconfirmSwitchesTheDocumentOff() {
        service.saveDraft(org, owner, "CS_HOURS", null, "평일 10시~17시");
        service.confirm(org, owner, "CS_HOURS", null, "평일 10시~17시");
        DefaultPolicyViews.Overview o = service.unconfirm(org, owner, "CS_HOURS");

        assertThat(item(o, DefaultPolicyItem.CS_HOURS).status()).isEqualTo("DRAFT");
        assertThat(doc(DefaultPolicyGroup.SERVICE).isActive()).isFalse();
        assertThat(orgKnowledge.search(org, "상담 시간이 어떻게 되나요?", 5).passages()).isEmpty();
        assertThat(o.documents()).singleElement().satisfies(d -> assertThat(d.active()).isFalse());

        service.confirm(org, owner, "CS_HOURS", null, "평일 10시~17시");
        assertThat(doc(DefaultPolicyGroup.SERVICE).isActive()).isTrue();
        assertThat(mapped.findAllByOrgId(org)).hasSize(1);
    }

    @Test
    @DisplayName("출고 마감·휴무는 여기서 못 고치고, 톡톡 배송 기준을 확정하면 배송 문서에 그대로 들어간다")
    void sharedItemsComeFromTheTalkTalkGuidance() {
        assertThatThrownBy(() -> service.saveDraft(org, owner, "DISPATCH_CUTOFF", 14, null))
                .isInstanceOf(ApiException.class).hasMessageContaining("톡톡 설정");
        assertThat(item(service.overview(org), DefaultPolicyItem.DISPATCH_CUTOFF).status()).isEqualTo("EMPTY");

        TalkTalkGuidance draft = guidance.saveDraft(org, owner, new TalkTalkGuidanceService.Input(14, 0,
                List.of("MON", "TUE", "WED", "THU", "FRI"),
                List.of(new TalkTalkGuidanceRules.ClosedDate(LocalDate.of(2026, 12, 25), "성탄절")),
                LocalDate.of(2027, 1, 31)));
        // A draft guidance is not the shop's word either.
        assertThat(mapped.findAllByOrgId(org)).isEmpty();
        TalkTalkGuidance g = guidance.confirm(org, owner, draft.getId(), draft.getFingerprint());

        DefaultPolicyViews.Overview o = service.overview(org);
        assertThat(item(o, DefaultPolicyItem.DISPATCH_CUTOFF).status()).isEqualTo("CONFIRMED");
        assertThat(item(o, DefaultPolicyItem.DISPATCH_CUTOFF).shared()).isTrue();
        String body = doc(DefaultPolicyGroup.SHIPPING).getBody();
        assertThat(body).contains(TalkTalkGuidanceRules.ruleSentence(g)).contains("12월 25일").contains("성탄절");

        guidance.retire(org, owner, g.getId());
        assertThat(doc(DefaultPolicyGroup.SHIPPING).isActive()).isFalse();
        assertThat(item(service.overview(org), DefaultPolicyItem.HOLIDAYS).status()).isEqualTo("EMPTY");
    }

    @Test
    @DisplayName("확정한 7일과 올린 매뉴얼의 14일은 자료끼리 다른 값으로 잡힌다")
    void confirmedAnswerIsComparedWithUploadedManuals() {
        orgKnowledge.create(org, new OrgKnowledgeRequest(OrgKnowledgeType.EXCHANGE_REFUND_POLICY, "반품 규정",
                "반품은 상품을 받은 날부터 14일 안에 신청할 수 있습니다.", null), owner, "판매자");
        service.saveDraft(org, owner, "RETURN_WINDOW", 7, null);
        service.confirm(org, owner, "RETURN_WINDOW", 7, null);

        assertThat(currency.overview(org).conflicts())
                .anySatisfy(c -> assertThat(c.attribute()).isEqualTo(ClaimAttribute.RETURN_WINDOW));
    }

    @Test
    @DisplayName("같은 제목의 판매자 규칙이 이미 있으면 건드리지 않고 번호를 붙여 따로 만든다")
    void neverOverwritesTheSellersOwnRule() {
        orgKnowledge.create(org, new OrgKnowledgeRequest(OrgKnowledgeType.SHIPPING_POLICY, "배송 안내 (기본 설정)",
                "판매자가 직접 쓴 배송 안내", null), owner, "판매자");
        service.saveDraft(org, owner, "COURIER", null, "로젠택배");
        service.confirm(org, owner, "COURIER", null, "로젠택배");

        assertThat(doc(DefaultPolicyGroup.SHIPPING).getTitle()).isEqualTo("배송 안내 (기본 설정) 2");
        assertThat(orgSources.findByOrgIdAndTitle(org, "배송 안내 (기본 설정)").orElseThrow().getBody())
                .isEqualTo("판매자가 직접 쓴 배송 안내");
    }

    @Test
    @DisplayName("빈칸 표시·범위 밖 숫자·다른 가게 사용자는 거절된다")
    void validationAndTenancy() {
        assertThatThrownBy(() -> service.saveDraft(org, owner, "WARRANTY", null, "【확인: 보증 기간】"))
                .isInstanceOf(ApiException.class).hasMessageContaining("【확인");
        assertThatThrownBy(() -> service.saveDraft(org, owner, "RETURN_WINDOW", 0, null))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.saveDraft(org, owner, "RETURN_FEE", -1, null))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.saveDraft(org, owner, "NO_SUCH", null, "x"))
                .isInstanceOf(ApiException.class);
        UUID otherOrg = org("다른 가게");
        UUID stranger = user(otherOrg, "OWNER");
        assertThatThrownBy(() -> service.saveDraft(org, stranger, "COURIER", null, "CJ"))
                .isInstanceOf(ApiException.class);
        UUID staff = user(org, "STAFF");
        service.saveDraft(org, staff, "COURIER", null, "CJ");
        assertThatThrownBy(() -> service.confirm(org, staff, "COURIER", null, "CJ"))
                .isInstanceOf(ApiException.class).hasMessageContaining("소유자");
        assertThat(service.overview(otherOrg).confirmed()).isZero();
    }
}
