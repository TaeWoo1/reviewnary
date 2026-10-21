package com.sellerops.vendor.ops;

import com.sellerops.vendor.account.SellerAccounts;
import com.sellerops.vendor.delegate.Delegates;
import com.sellerops.vendor.store.Stores;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The operator's account commands, run on the server host as a one-off process instead of the web server — contract
 * §2, D2. There is no admin screen yet (the delegate and admin consoles are FE deltas still to be confirmed), and an
 * invite-only service needs some way to invite.
 *
 * <pre>
 *   java -jar vendor-server.jar invite seller@example.com              # prints the invite code once
 *   java -jar vendor-server.jar suspend seller@example.com             # ends its sessions and installs at the next request
 *   java -jar vendor-server.jar reinstate seller@example.com
 *   java -jar vendor-server.jar invite-delegate me@example.com [admin] # prints the delegate's join link once
 *   java -jar vendor-server.jar suspend-delegate me@example.com        # ends sessions and every store assignment
 *   java -jar vendor-server.jar reinstate-delegate me@example.com      # the account only; assign stores again
 *   java -jar vendor-server.jar add-store seller@example.com 라벨       # a seller's NAVER store; prints its id
 *   java -jar vendor-server.jar assign me@example.com &lt;store-id&gt;
 *   java -jar vendor-server.jar unassign me@example.com &lt;store-id&gt;
 * </pre>
 *
 * <p>Invite codes are printed to the operator's terminal and nowhere else — they are not logged, and the database
 * keeps only their hash. The operator hands each to its person. A delegate's invite is printed as the join page's
 * link with the code in its fragment ({@code /delegate/join#…}): the code is 43 characters, not something to type,
 * and a fragment never reaches a server log. The link starts with {@code vendor.public-url} when the deployment sets
 * it; without it only the path is printed, to be put after the server's address.
 */
public final class OpsCommands {

    public static final Set<String> NAMES = Set.of("invite", "suspend", "reinstate", "invite-delegate",
            "suspend-delegate", "reinstate-delegate", "add-store", "assign", "unassign");

    static final String USAGE = "usage: invite|suspend|reinstate <email> · invite-delegate <email> [admin] · "
            + "suspend-delegate|reinstate-delegate <email> · add-store <seller-email> <label> · "
            + "assign|unassign <delegate-email> <store-id>";

    /** The page a delegate's invite link opens; the code follows {@code #}. */
    public static final String JOIN_PATH = "/delegate/join#";

    /** The services the commands act on, and the server's public address ({@code ""} when not set). */
    public record Services(SellerAccounts accounts, Delegates delegates, Stores stores, String publicUrl) {}

    private OpsCommands() {
    }

    /** Runs one command; returns the process exit code. */
    public static int run(Services services, String[] args, PrintStream out) {
        if (args.length < 2 || !NAMES.contains(args[0])) {
            out.println(USAGE);
            return 2;
        }
        String name = args[0];
        boolean twoArgs = args.length == 2;
        return switch (name) {
            case "invite", "suspend", "reinstate", "suspend-delegate", "reinstate-delegate" ->
                    twoArgs ? person(services, name, args[1], out) : usage(out);
            case "invite-delegate" -> {
                if (args.length > 3 || (args.length == 3 && !"admin".equals(args[2]))) {
                    yield usage(out);
                }
                Delegates.Role role = args.length == 3 ? Delegates.Role.ADMIN : Delegates.Role.DELEGATE;
                yield joinLink(services, out, args[1], role);
            }
            case "add-store" -> args.length < 3 ? usage(out) : addStore(services, args, out);
            default -> args.length == 3 ? assignment(services, name, args[1], args[2], out) : usage(out);
        };
    }

    private static int person(Services services, String name, String email, PrintStream out) {
        return switch (name) {
            case "invite" -> invite(out, () -> services.accounts().issueInvite(email),
                    SellerAccounts.INVITE_LIFETIME.toDays());
            case "suspend" -> done(out, services.accounts().suspend(email), "suspended", "no such account");
            case "reinstate" -> done(out, services.accounts().reinstate(email), "reinstated", "no such account");
            case "suspend-delegate" -> done(out, services.delegates().suspend(email),
                    "suspended; store assignments ended", "no such delegate");
            default -> done(out, services.delegates().reinstate(email),
                    "reinstated; assign stores again", "no such delegate");
        };
    }

    private static int addStore(Services services, String[] args, PrintStream out) {
        Optional<UUID> account = services.accounts().idOf(args[1]);
        if (account.isEmpty()) {
            out.println("no such account");
            return 1;
        }
        String label = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        try {
            out.println("store id: " + services.stores().add(account.get(), Stores.Channel.NAVER, label));
            return 0;
        } catch (IllegalArgumentException e) {
            out.println("label must be 1-" + Stores.MAX_LABEL_LENGTH + " characters");
            return 2;
        }
    }

    private static int assignment(Services services, String name, String email, String rawStoreId, PrintStream out) {
        UUID storeId;
        try {
            storeId = UUID.fromString(rawStoreId);
        } catch (IllegalArgumentException e) {
            out.println("not a store id");
            return 2;
        }
        Optional<UUID> delegate = services.delegates().idOf(email);
        if (delegate.isEmpty() || !services.stores().exists(storeId)) {
            out.println(delegate.isEmpty() ? "no such delegate" : "no such store");
            return 1;
        }
        if ("assign".equals(name)) {
            if (services.delegates().active(delegate.get()).isEmpty()) {
                out.println("delegate is suspended");
                return 1;
            }
            return done(out, services.stores().assign(storeId, delegate.get()), "assigned", "already assigned");
        }
        return done(out, services.stores().unassign(storeId, delegate.get()), "unassigned", "was not assigned");
    }

    private interface CodeIssuer {
        String issue();
    }

    private static int invite(PrintStream out, CodeIssuer issuer, long days) {
        try {
            String code = issuer.issue();
            out.println("invite code (valid " + days + " days, shown once): " + code);
            return 0;
        } catch (IllegalArgumentException e) {
            out.println("not an e-mail address");
            return 2;
        }
    }

    private static int joinLink(Services services, PrintStream out, String email, Delegates.Role role) {
        String code;
        try {
            code = services.delegates().issueInvite(email, role);
        } catch (IllegalArgumentException e) {
            out.println("not an e-mail address");
            return 2;
        }
        String base = services.publicUrl() == null ? "" : services.publicUrl().strip().replaceAll("/+$", "");
        long days = Delegates.INVITE_LIFETIME.toDays();
        out.println(base.isEmpty()
                ? "join link path (valid " + days + " days, shown once; put the server's address in front): "
                        + JOIN_PATH + code
                : "join link (valid " + days + " days, shown once): " + base + JOIN_PATH + code);
        return 0;
    }

    private static int done(PrintStream out, boolean ok, String yes, String no) {
        out.println(ok ? yes : no);
        return ok ? 0 : 1;
    }

    private static int usage(PrintStream out) {
        out.println(USAGE);
        return 2;
    }
}
