package com.sellerops.operations;

import com.sellerops.attention.OperatorAttentionService;
import com.sellerops.attention.dto.OperatorVocItem;
import com.sellerops.attention.reply.DelegatedReplyLane;
import com.sellerops.attention.reply.ReviewReplyWorkState;
import com.sellerops.auth.AuthPrincipal;
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
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The review half of 확인할 일, whole — READ only (UI/UX v2 Phase 3). See {@link ReviewWorkView}.
 *
 * <p>Composed from two reads that already exist and already mean what they say: the Home's undecided-확인 필요
 * predicate, and each account's reply to-do. Only accounts whose channel has a reply flow are asked, because only
 * those can hold a reply to-do; the question is the capability table's, not this class's.
 */
@RestController
@RequestMapping("/api/operations")
public class ReviewWorkController {

    /** Enough for any morning; the count beside the list still says how many exist. */
    static final int MAX_ATTENTION = 100;
    static final int MAX_TODO = 100;
    /** The same five the 리뷰 screen's 「최근에 기록한 답변」 showed. */
    static final int RECENT = 5;
    private static final Set<String> STILL_THE_SELLERS =
            Set.of(ReviewReplyWorkState.DRAFT_NEEDED.name(), ReviewReplyWorkState.AWAITING_APPROVAL.name());
    /**
     * Delegate-lane states in which the reply is the delegate's to finish, not the seller's: approved and waiting for
     * a delegate, being posted, being stopped, or reported posted. They leave the list for the reason {@code APPROVED}
     * does — nothing in them waits on the seller. This PC's own draft read cannot tell them from 승인 대기.
     */
    static final Set<String> WITH_THE_DELEGATE =
            Set.of("APPROVED", "CLAIMED", "CANCEL_REQUESTED", "HOLD_REQUESTED", "UNKNOWN", "POSTED");
    /**
     * Lane states that put the reply back in front of the seller; the row says which, not a plain 승인 대기. A draft
     * only waiting on the phone is not one of them (2026-10-25: every draft is on the phone) — it is plain 승인 대기.
     */
    static final Set<String> BACK_WITH_THE_SELLER = Set.of("RECHECK_NEEDED", "HELD", "FAILED");

    private final OperationsHomeService home;
    private final OperatorAttentionService attention;
    private final SellerAccountRepository accounts;
    private final ChannelRepository channels;

    private final ReviewWorkReader reader;

    public ReviewWorkController(OperationsHomeService home, OperatorAttentionService attention,
                                SellerAccountRepository accounts, ChannelRepository channels) {
        this.home = home;
        this.attention = attention;
        this.accounts = accounts;
        this.channels = channels;
        this.reader = new ReviewWorkReader(home, attention, accounts, channels);
    }

    /** The vendor's delegate lane, or null in a context that has none — then every row stands as its draft read says. */
    private DelegatedReplyLane delegated;

    @Autowired(required = false)
    public void setDelegatedReplyLane(DelegatedReplyLane delegated) {
        this.delegated = delegated;
        reader.setDelegatedReplyLane(delegated);
    }

    @GetMapping("/review-work")
    public ReviewWorkView reviewWork(@AuthenticationPrincipal AuthPrincipal principal) {
        return reader.read(principal.orgId(), MAX_ATTENTION, MAX_TODO);
    }
}
