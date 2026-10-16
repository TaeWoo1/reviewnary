package com.sellerops.vendor.ops;

import com.sellerops.vendor.account.SellerAccounts;
import java.io.PrintStream;
import java.util.Set;

/**
 * The operator's account commands, run on the server host as a one-off process instead of the web server — contract
 * §2, D2. There is no admin screen yet (the delegate and admin consoles are FE deltas still to be confirmed), and an
 * invite-only service needs some way to invite.
 *
 * <pre>
 *   java -jar vendor-server.jar invite seller@example.com     # prints the invite code once
 *   java -jar vendor-server.jar suspend seller@example.com    # ends its sessions and installs at the next request
 *   java -jar vendor-server.jar reinstate seller@example.com
 * </pre>
 *
 * <p>The invite code is printed to the operator's terminal and nowhere else — it is not logged, and the database
 * keeps only its hash. The operator hands it to the seller.
 */
public final class OpsCommands {

    public static final Set<String> NAMES = Set.of("invite", "suspend", "reinstate");

    private OpsCommands() {
    }

    /** Runs one command; returns the process exit code. */
    public static int run(SellerAccounts accounts, String[] args, PrintStream out) {
        if (args.length != 2 || !NAMES.contains(args[0])) {
            out.println("usage: invite|suspend|reinstate <email>");
            return 2;
        }
        String email = args[1];
        switch (args[0]) {
            case "invite" -> {
                try {
                    String code = accounts.issueInvite(email);
                    out.println("invite code (valid " + SellerAccounts.INVITE_LIFETIME.toDays() + " days, shown once): "
                            + code);
                    return 0;
                } catch (IllegalArgumentException e) {
                    out.println("not an e-mail address");
                    return 2;
                }
            }
            case "suspend" -> {
                boolean done = accounts.suspend(email);
                out.println(done ? "suspended" : "no such account");
                return done ? 0 : 1;
            }
            default -> {
                boolean done = accounts.reinstate(email);
                out.println(done ? "reinstated" : "no such account");
                return done ? 0 : 1;
            }
        }
    }
}
