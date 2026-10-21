package com.sellerops.vendor.link;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The pages people use this server through, beside {@code /link} — contract §4.3, mockup confirmed 2026-10-21.
 * {@code /replies}: the seller reads a review and approves the reply the PC sent. {@code /delegate}: the delegate
 * claims an approved reply, checks once more just before saving, copies it into Seller Center by hand and reports,
 * and uploads a store's review export.
 * Static files under {@code static/replies/}, {@code static/delegate/} and {@code static/assets/}; this only maps the
 * bare paths. Both pages sign in through the ordinary API and hold no credential the API does not already give.
 */
@Controller
public class AppPagesController {

    @GetMapping("/replies")
    public String replies() {
        return "forward:/replies/index.html";
    }

    @GetMapping("/delegate")
    public String delegate() {
        return "forward:/delegate/index.html";
    }

    /**
     * A delegate accepts an invite: password → authenticator → recovery codes. The invite code arrives in the link's
     * fragment ({@code /delegate/join#…}), which a browser never sends to any server; the page clears it on load.
     */
    @GetMapping("/delegate/join")
    public String join() {
        return "forward:/delegate/join.html";
    }
}
