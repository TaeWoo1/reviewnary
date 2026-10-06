package com.sellerops.responsibility.aside;

import com.sellerops.common.ApiException;

/**
 * <b>The bound a dispatched job carries on the row, instead of only in a comment.</b>
 *
 * <p>Every published recipe reads ONE page and has no verb for turning one — the helper-side runtimes open a
 * route, read, and close. So the only value this type can hold today is 1, and that is the point: the bound is
 * written down where the database can refuse to store anything else ({@code chk_scheduled_aside_job_pages}),
 * so «bounded by construction» stops being a property you have to go and read the runtime to believe.
 *
 * <p><b>Why have the type at all, then.</b> Because the day a recipe can turn a page, the change should be a
 * migration and a decision — visible, dated, reviewable — rather than a loop appearing in a runtime with
 * nothing anywhere to contradict it. A ceiling that exists is a ceiling someone has to raise deliberately.
 */
public record AsideJobLimits(int maxPages) {

    /** The only bound the published recipes can honour: one window asks for one page. */
    public static final AsideJobLimits ONE_PAGE = new AsideJobLimits(1);

    public AsideJobLimits {
        if (maxPages != 1) {
            // Not clamped, refused. A caller asking for two pages is asking for something no recipe can do,
            // and silently giving them one would let them believe they had asked for a deeper read.
            throw ApiException.badRequest("한 번에 한 화면만 확인할 수 있습니다.");
        }
    }
}
