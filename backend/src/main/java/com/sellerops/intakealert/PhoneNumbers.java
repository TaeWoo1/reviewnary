package com.sellerops.intakealert;

import java.util.Optional;
import java.util.regex.Pattern;

/** Korean mobile numbers only (010/011/016/017/018/019), digits kept, never logged; shown as its last four digits. */
public final class PhoneNumbers {

    private static final Pattern MOBILE = Pattern.compile("010[0-9]{8}|01[16789][0-9]{7,8}");

    private PhoneNumbers() {
    }

    /** Digits of a valid mobile number, or empty. Hyphens and spaces are allowed in the input. */
    public static Optional<String> normalize(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String digits = raw.replaceAll("[\\s-]", "");
        return MOBILE.matcher(digits).matches() ? Optional.of(digits) : Optional.empty();
    }

    public static String last4(String digits) {
        return digits.substring(digits.length() - 4);
    }

    public static String masked(String last4) {
        return last4 == null ? null : "***-****-" + last4;
    }
}
