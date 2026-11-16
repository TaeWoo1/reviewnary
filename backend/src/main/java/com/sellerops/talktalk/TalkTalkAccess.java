package com.sellerops.talktalk;

import com.sellerops.common.ApiException;
import com.sellerops.user.User;
import com.sellerops.user.UserRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Who may do what in the lane, checked on the server (server_operation_boundary_v1 §2.2). Connecting, confirming a
 * guidance version and delegating are the owner's; sending and handing over need a signed-in user of the same org.
 * The org and the actor come only from the authenticated principal.
 *
 * <p>Honest limit, as {@code InquiryDismissalAdminController} states it: every account this product creates is
 * {@code OWNER} today, so the owner check refuses nobody yet. It stands where a role model would land.
 */
@Component
public class TalkTalkAccess {

    static final String OWNER = "OWNER";

    private final UserRepository users;

    public TalkTalkAccess(UserRepository users) {
        this.users = users;
    }

    public User member(UUID orgId, UUID userId) {
        if (orgId == null || userId == null) {
            throw ApiException.forbidden("로그인이 필요해요.");
        }
        return users.findById(userId).filter(u -> orgId.equals(u.getOrgId()))
                .orElseThrow(() -> ApiException.forbidden("이 가게의 사용자가 아니에요."));
    }

    public User owner(UUID orgId, UUID userId) {
        User u = member(orgId, userId);
        if (!OWNER.equals(u.getRole())) {
            throw ApiException.forbidden("가게 소유자만 할 수 있어요.");
        }
        return u;
    }
}
