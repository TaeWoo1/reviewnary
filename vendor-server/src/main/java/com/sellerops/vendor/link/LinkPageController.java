package com.sellerops.vendor.link;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * {@code GET /link} — the page where a seller allows a PC onto their account (contract §2). The PC opens
 * {@code /link?code=…} in the seller's default browser; the page logs in, shows the account, the PC type and the
 * code, and approves on 「연결 허용」. Static files under {@code static/link/}; this only maps the bare path,
 * with or without a trailing slash.
 */
@Controller
public class LinkPageController {

    @GetMapping({"/link", "/link/"})
    public String page() {
        return "forward:/link/index.html";
    }
}
