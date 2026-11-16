package com.sellerops.talktalk;

import java.util.List;

/**
 * The source ranges the official README lists for a webhook ACL (navertalk/chatbot-api, 「Webhook 요구 사항」, read
 * 2026-11-16). This is <b>not</b> request authentication — the protocol documents none — only the documented half of
 * the ingress trust boundary. IPv4 only, as documented.
 */
public final class TalkTalkSourceAllowlist {

    public static final List<String> DOCUMENTED_RANGES = List.of(
            "211.249.40.0/27", "211.249.68.0/27", "220.230.168.0/27", "103.6.173.0/27");

    private TalkTalkSourceAllowlist() {
    }

    public static boolean documented(String ip) {
        return within(ip, DOCUMENTED_RANGES);
    }

    public static boolean within(String ip, List<String> cidrs) {
        long addr = parse(ip);
        if (addr < 0) {
            return false;
        }
        for (String cidr : cidrs) {
            String[] parts = cidr.strip().split("/");
            long base = parse(parts[0]);
            int bits = parts.length > 1 ? Integer.parseInt(parts[1]) : 32;
            if (base < 0 || bits < 0 || bits > 32) {
                continue;
            }
            long mask = bits == 0 ? 0 : (0xFFFFFFFFL << (32 - bits)) & 0xFFFFFFFFL;
            if ((addr & mask) == (base & mask)) {
                return true;
            }
        }
        return false;
    }

    /** An IPv4 dotted quad as an unsigned number, or -1 when it is not one. */
    static long parse(String ip) {
        if (ip == null) {
            return -1;
        }
        String s = ip.strip();
        if (s.startsWith("::ffff:")) {
            s = s.substring(7);
        }
        String[] q = s.split("\\.");
        if (q.length != 4) {
            return -1;
        }
        long v = 0;
        for (String part : q) {
            if (part.isEmpty() || part.length() > 3 || !part.chars().allMatch(Character::isDigit)) {
                return -1;
            }
            int n = Integer.parseInt(part);
            if (n > 255) {
                return -1;
            }
            v = (v << 8) | n;
        }
        return v;
    }
}
