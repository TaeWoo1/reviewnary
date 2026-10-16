package com.sellerops.vendor.install;

import com.sellerops.vendor.Codes;

/**
 * The install token's shape — contract §2: opaque, {@code rvi_}-prefixed, stored only as SHA-256. The prefix is how
 * {@link com.sellerops.vendor.auth.BearerAuthFilter} tells it from a seller session before any parsing: an install
 * token is never handed to the JWT parser, and a JWT never starts with it.
 */
public final class InstallTokens {

    public static final String PREFIX = "rvi_";

    private InstallTokens() {
    }

    public static String mint() {
        return PREFIX + Codes.newCode();
    }

    public static boolean looksLikeInstallToken(String bearer) {
        return bearer != null && bearer.startsWith(PREFIX);
    }
}
