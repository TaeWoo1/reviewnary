package com.sellerops.operations;

import com.sellerops.attention.OperatorAttentionService;
import com.sellerops.attention.dto.OperatorVocItem;
import com.sellerops.attention.reply.DelegatedReplyLane;
import com.sellerops.channel.Channel;
import com.sellerops.channel.ChannelRepository;
import com.sellerops.operations.dto.ReviewWorkView;
import com.sellerops.review.triage.ReviewTriageChannelCapability;
import com.sellerops.selleraccount.SellerAccount;
import com.sellerops.selleraccount.SellerAccountRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * The review half of the seller's work — what {@link ReviewWorkController} serves — as a read other services can call
 * (2026-11-09, demo handoff §7.12: the Home's one count reuses this rule rather than restating it). The sets and the
 * delegate filtering are {@link ReviewWorkController}'s, moved here unchanged.
 */
@Service
public class ReviewWorkReader {

    static final int RECENT = ReviewWorkController.RECENT;
    private static final Set<String> STILL_THE_SELLERS = Set.of(
            com.sellerops.attention.reply.ReviewReplyWorkState.DRAFT_NEEDED.name(),
            com.sellerops.attention.reply.ReviewReplyWorkState.AWAITING_APPROVAL.name());

    private final OperationsHomeService home;
    private final OperatorAttentionService attention;
    private final SellerAccountRepository accounts;
    private final ChannelRepository channels;
    private DelegatedReplyLane delegated;

    public ReviewWorkReader(OperationsHomeService home, OperatorAttentionService attention,
                            SellerAccountRepository accounts, ChannelRepository channels) {
        this.home = home;
        this.attention = attention;
        this.accounts = accounts;
        this.channels = channels;
    }

    @Autowired(required = false)
    public void setDelegatedReplyLane(DelegatedReplyLane delegated) {
        this.delegated = delegated;
    }

    /** The review-work read with the given caps — byte-identical to what {@code GET /api/operations/review-work} returned. */
    public ReviewWorkView read(UUID orgId, int maxAttention, int maxTodo) {
        return read(orgId, maxAttention, maxTodo, false).view();
    }

    /**
     * The same read; with {@code lifted} the per-account reply work is read up to {@code maxTodo} rather than the
     * screen's page size, and {@code complete} says whether any cap was reached (2026-11-09, demo handoff §7.12).
     */
    public Read read(UUID orgId, int maxAttention, int maxTodo, boolean lifted) {
        boolean[] complete = {true};
        Map<UUID, Channel> byId = channels.findAll().stream()
                .collect(Collectors.toMap(Channel::getId, Function.identity(), (a, b) -> a));
        List<ReviewWorkView.AccountWork> committed = new ArrayList<>();
        for (SellerAccount account : accounts.findAllByOrgId(orgId)) {
            Channel channel = byId.get(account.getChannelId());
            if (channel == null || !ReviewTriageChannelCapability.of(channel.getCode()).replyFlowExists()) {
                continue;
            }
            var work = lifted ? attention.replyWorkUpTo(orgId, account.getId(), maxTodo)
                    : attention.replyWork(orgId, account.getId(), maxTodo, RECENT);
            if (lifted && (work.todo().size() >= maxTodo
                    || (work.coverage() != null && work.coverage().isUncertain()))) {
                complete[0] = false; // the ceiling was reached, or this account's work cannot be attributed
            }
            committed.add(new ReviewWorkView.AccountWork(account.getId(), channel.getCode(), channel.getNameKo(),
                    work.coverage() == null ? null : work.coverage().name(),
                    work.todo().stream().filter(t -> STILL_THE_SELLERS.contains(t.replyWorkState())).toList(),
                    work.recentlyReported()));
        }
        Map<UUID, String> lane = delegated == null ? Map.of() : delegated.statesOf(orgId, reviewsOf(committed));
        committed = committed.stream().map(a -> new ReviewWorkView.AccountWork(a.accountId(), a.channelCode(),
                a.channelNameKo(), a.coverage(),
                a.todo().stream().filter(t -> !withTheDelegate(lane, t)).toList(),
                a.recentlyReported())).toList();
        Set<UUID> todoReviews = reviewsOf(committed);
        Map<UUID, String> delegatedStates = lane.entrySet().stream()
                .filter(e -> todoReviews.contains(e.getKey()) && ReviewWorkController.BACK_WITH_THE_SELLER.contains(e.getValue()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        long attentionTotal = home.undecidedAttentionCount(orgId);
        List<com.sellerops.operations.dto.OperationsHomeView.AttentionReview> attentionRows =
                home.undecidedAttention(orgId, maxAttention);
        if (attentionRows.size() < attentionTotal) {
            complete[0] = false;
        }
        return new Read(new ReviewWorkView(attentionTotal, attentionRows,
                committed, home.safetyAmong(orgId, todoReviews), home.safetyWorryAmong(orgId, todoReviews),
                delegatedStates, home.safetyKindsAmong(orgId, todoReviews)), complete[0]);
    }

    /** One review-work read and whether it holds everything that exists. */
    public record Read(ReviewWorkView view, boolean complete) {
    }


    private static boolean withTheDelegate(Map<UUID, String> lane, OperatorVocItem item) {
        UUID reviewId = reviewIdOf(item.reviewId());
        String state = reviewId == null ? null : lane.get(reviewId);
        return state != null && ReviewWorkController.WITH_THE_DELEGATE.contains(state);
    }

    private static Set<UUID> reviewsOf(List<ReviewWorkView.AccountWork> committed) {
        return committed.stream().flatMap(a -> a.todo().stream())
                .map(OperatorVocItem::reviewId).map(ReviewWorkReader::reviewIdOf).filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private static UUID reviewIdOf(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException notOurs) {
            return null;
        }
    }
}
