package com.sellerops.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.sellerops.attention.reply.ReviewReplyApprovalRepository;
import com.sellerops.attention.triage.ReviewTriage;
import com.sellerops.attention.triage.ReviewTriageRepository;
import com.sellerops.attention.triage.TriageDisposition;
import com.sellerops.channel.Channel;
import com.sellerops.channel.ChannelRepository;
import com.sellerops.channel.ChannelStatus;
import com.sellerops.coverage.ChannelCoverageService;
import com.sellerops.inquiry.InquiryRepository;
import com.sellerops.inquiry.workitem.InquiryWorkItemRepository;
import com.sellerops.operations.dto.OperationsHomeView.AttentionReview;
import com.sellerops.opportunity.OpportunityService;
import com.sellerops.organization.Organization;
import com.sellerops.organization.OrganizationRepository;
import com.sellerops.product.ProductRepository;
import com.sellerops.repeatedissue.RepeatedIssueWorkspaceService;
import com.sellerops.review.Review;
import com.sellerops.review.ReviewRepository;
import com.sellerops.review.triage.pilot.AiTriagePilotService;
import com.sellerops.reviewissue.ReviewIssueQueryService;
import com.sellerops.selleraccount.SellerAccountRepository;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * <b>A review that reports a safety or legal problem is the seller's work whatever its rating</b> (product-owner
 * decision, 2026-10-22) — it joins the undecided 확인 필요 list and its count, marked, and nothing else does.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class HomeSafetyReviewTest {

    @Autowired ReviewRepository reviews;
    @Autowired ReviewTriageRepository triage;
    @Autowired ProductRepository products;
    @Autowired SellerAccountRepository accounts;
    @Autowired ChannelRepository channels;
    @Autowired OrganizationRepository organizations;

    private OperationsHomeService home;
    private UUID org;
    private UUID channel;
    private int day;

    @BeforeEach
    void setUp() {
        home = new OperationsHomeService(reviews, products, accounts, channels, mock(AiTriagePilotService.class),
                mock(ReviewIssueQueryService.class), mock(RepeatedIssueWorkspaceService.class),
                mock(ChannelCoverageService.class), mock(ReviewReplyApprovalRepository.class),
                mock(InquiryWorkItemRepository.class), mock(InquiryRepository.class), mock(OpportunityService.class));
        org = organization("안전 시험 상점");
        Channel c = new Channel();
        c.setCode("SAFETY_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        c.setNameKo("안전 시험 채널");
        c.setStatus(ChannelStatus.AVAILABLE);
        c.setSupportsReview(true);
        c.setSortOrder(999);
        channel = channels.save(c).getId();
    }

    @Test
    void aFiveStarReportOfHarmJoinsTheListAndItsCountMarked() {
        Review complaint = review(org, 1, "색이 사진이랑 너무 달라요");
        Review burned = review(org, 5, "디자인은 예쁜데 아이가 손에 화상을 입었어요");
        review(org, 5, "아이가 다칠 걱정 없어요");
        review(org, 5, "배송이 빨라요");

        List<AttentionReview> rows = home.undecidedAttention(org, 10);
        assertThat(rows).extracting(AttentionReview::reviewId).containsExactly(burned.getId(), complaint.getId());
        assertThat(rows).extracting(AttentionReview::safety).containsExactly(true, false);
        assertThat(home.undecidedAttentionCount(org)).isEqualTo(2);
    }

    @Test
    void aLowRatedReportIsMarkedInPlaceAndNotCountedTwice() {
        Review burned = review(org, 1, "충전하다가 타는 냄새가 났어요");

        assertThat(home.undecidedAttention(org, 10)).singleElement()
                .satisfies(row -> {
                    assertThat(row.reviewId()).isEqualTo(burned.getId());
                    assertThat(row.safety()).isTrue();
                });
        assertThat(home.undecidedAttentionCount(org)).isEqualTo(1);
    }

    @Test
    void aReportTheSellerAlreadyDecidedAboutIsNotRaisedAgain() {
        Review burned = review(org, 5, "먹고 나서 배탈이 났어요");
        ReviewTriage decided = new ReviewTriage();
        decided.setOrgId(org);
        decided.setReviewId(burned.getId());
        decided.setChannelId(channel);
        decided.setDisposition(TriageDisposition.NO_ACTION);
        decided.setDecidedBy("seller");
        decided.setDecidedAt(Instant.parse("2026-10-21T00:00:00Z"));
        triage.saveAndFlush(decided);

        assertThat(home.undecidedAttention(org, 10)).isEmpty();
        assertThat(home.undecidedAttentionCount(org)).isZero();
    }

    @Test
    void anotherSellersReportIsNotThisSellersWork() {
        UUID other = organization("다른 상점");
        Review theirs = review(other, 5, "모서리에 다쳤어요");

        assertThat(home.undecidedAttention(org, 10)).isEmpty();
        assertThat(home.safetyAmong(org, Set.of(theirs.getId()))).isEmpty();
        assertThat(home.safetyAmong(other, Set.of(theirs.getId()))).containsExactly(theirs.getId());
    }

    private UUID organization(String name) {
        Organization o = new Organization();
        o.setName(name);
        return organizations.save(o).getId();
    }

    private Review review(UUID orgId, int rating, String body) {
        Review r = new Review();
        r.setOrgId(orgId);
        r.setChannelId(channel);
        r.setExternalId(UUID.randomUUID().toString());
        r.setRating(rating);
        r.setBody(body);
        r.setReceivedAt(Instant.parse("2026-10-01T00:00:00Z").plusSeconds(86_400L * day++));
        return reviews.saveAndFlush(r);
    }
}
