package com.sellerops.vendor.ops;

import com.sellerops.vendor.account.SellerAccounts;
import com.sellerops.vendor.auth.SessionRevocations;
import com.sellerops.vendor.auth.SessionTokens;
import com.sellerops.vendor.delegate.Delegates;
import com.sellerops.vendor.retention.Retention;
import com.sellerops.vendor.store.Stores;
import com.sellerops.vendor.task.ReplyTasks;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The operator's account commands, run on the server host as a one-off process instead of the web server — contract
 * §2, D2. There is no admin screen yet (the delegate and admin consoles are FE deltas still to be confirmed), and an
 * invite-only service needs some way to invite.
 *
 * <pre>
 *   java -jar vendor-server.jar invite seller@example.com              # prints the seller's join link once
 *   java -jar vendor-server.jar suspend seller@example.com             # ends its sessions and installs at the next request
 *   java -jar vendor-server.jar reinstate seller@example.com
 *   java -jar vendor-server.jar end-sessions someone@example.com       # signs them out everywhere; the account stays
 *   java -jar vendor-server.jar invite-delegate me@example.com [admin] # prints the delegate's join link once
 *   java -jar vendor-server.jar suspend-delegate me@example.com        # ends sessions and every store assignment
 *   java -jar vendor-server.jar reinstate-delegate me@example.com      # the account only; assign stores again
 *   java -jar vendor-server.jar add-store seller@example.com 라벨       # a seller's NAVER store; prints its id
 *   java -jar vendor-server.jar assign me@example.com &lt;store-id&gt;
 *   java -jar vendor-server.jar unassign me@example.com &lt;store-id&gt;
 *   java -jar vendor-server.jar purge                                  # one retention pass (§6); after a restore, before serving
 *   java -jar vendor-server.jar in-doubt                               # a suspended delegate's in-doubt tasks, to check
 *   java -jar vendor-server.jar settle &lt;task-id&gt; posted|not-posted admin@example.com
 * </pre>
 *
 * <p>Invite codes are printed to the operator's terminal and nowhere else — they are not logged, and the database
 * keeps only their hash. The operator hands each to its person. Every invite is printed as its join page's link with
 * the code in its fragment ({@code /join#…} for a seller, {@code /delegate/join#…} for a delegate): the code is 43
 * characters, not something to type, and a fragment never reaches a server log. The link starts with {@code vendor.public-url} when the deployment sets
 * it; without it only the path is printed, to be put after the server's address.
 */
public final class OpsCommands {

    public static final Set<String> NAMES = Set.of("invite", "suspend", "reinstate", "end-sessions",
            "invite-delegate", "suspend-delegate", "reinstate-delegate", "add-store", "assign", "unassign", "purge",
            "in-doubt", "settle");

    static final String USAGE = "usage: invite|suspend|reinstate|end-sessions <email> · "
            + "invite-delegate <email> [admin] · "
            + "suspend-delegate|reinstate-delegate <email> · add-store <seller-email> <label> · "
            + "assign|unassign <delegate-email> <store-id> · purge · in-doubt · "
            + "settle <task-id> posted|not-posted <admin-email>";

    /** The page a delegate's invite link opens; the code follows {@code #}. */
    public static final String JOIN_PATH = "/delegate/join#";

    /** The page a seller's invite link opens; the code follows {@code #}. */
    public static final String SELLER_JOIN_PATH = "/join#";

    /** The services the commands act on, and the server's public address ({@code ""} when not set). */
    public record Services(SellerAccounts accounts, Delegates delegates, Stores stores,
                           SessionRevocations revocations, Retention retention, ReplyTasks tasks, String publicUrl) {}

    private OpsCommands() {
    }

    /** Runs one command; returns the process exit code. */
    public static int run(Services services, String[] args, PrintStream out) {
        if (args.length == 1 && "purge".equals(args[0])) {
            Retention.Purged purged = services.retention().purge();
            out.println("deleted: reviews " + purged.reviews() + " · tasks " + purged.tasks() + " · invites "
                    + purged.invites() + " · logged-out sessions " + purged.sessions());
            return 0;
        }
        if (args.length == 1 && "in-doubt".equals(args[0])) {
            return inDoubt(services, out);
        }
        if (args.length == 4 && "settle".equals(args[0])) {
            return settle(services, args, out);
        }
        if (args.length < 2 || !NAMES.contains(args[0]) || Set.of("purge", "in-doubt", "settle").contains(args[0])) {
            out.println(USAGE);
            return 2;
        }
        String name = args[0];
        boolean twoArgs = args.length == 2;
        return switch (name) {
            case "invite", "suspend", "reinstate", "end-sessions", "suspend-delegate", "reinstate-delegate" ->
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
            case "invite" -> link(services, out, () -> services.accounts().issueInvite(email),
                    SellerAccounts.INVITE_LIFETIME.toDays(), SELLER_JOIN_PATH);
            case "suspend" -> done(out, services.accounts().suspend(email), "suspended", "no such account");
            case "end-sessions" -> endSessions(services, email, out);
            case "reinstate" -> done(out, services.accounts().reinstate(email), "reinstated", "no such account");
            case "suspend-delegate" -> done(out, services.delegates().suspend(email),
                    "suspended; store assignments ended", "no such delegate");
            default -> done(out, services.delegates().reinstate(email),
                    "reinstated; assign stores again", "no such delegate");
        };
    }

    /**
     * A leaked session or a lost phone: every session the person holds now ends at its next request, as a seller
     * and as a delegate when the address is both (the owner is the first delegate). The account itself is untouched
     * — signing in again works — so this is not suspension. A PC's install token is not a session; it is revoked
     * from the seller's own list of PCs.
     */
    private static int endSessions(Services services, String email, PrintStream out) {
        boolean seller = services.accounts().idOf(email)
                .map(id -> services.revocations().endAll(SessionTokens.Kind.SELLER, id)).orElse(false);
        boolean delegate = services.delegates().idOf(email)
                .map(id -> services.revocations().endAll(SessionTokens.Kind.DELEGATE, id)).orElse(false);
        if (!seller && !delegate) {
            out.println("no such account or delegate");
            return 1;
        }
        out.println("sessions ended:" + (seller ? " seller" : "") + (delegate ? " delegate" : ""));
        return 0;
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

    /**
     * A suspended delegate's in-doubt tasks, with what an admin needs to look each up in Seller Center: the store, the
     * review number and the approved reply to compare word for word. The review itself is not printed.
     */
    private static int inDoubt(Services services, PrintStream out) {
        List<ReplyTasks.InDoubt> tasks = services.tasks().inDoubt();
        if (tasks.isEmpty()) {
            out.println("no in-doubt tasks of suspended delegates");
            return 0;
        }
        for (ReplyTasks.InDoubt t : tasks) {
            out.println("task " + t.taskId() + " · " + t.storeLabel() + " · 리뷰번호 " + t.reviewNo() + " · since "
                    + t.since());
            out.println("  approved reply: " + t.approvedBody());
        }
        return 0;
    }

    /**
     * An admin who looked in Seller Center settles one: {@code posted} when the approved reply is there word for word,
     * {@code not-posted} when no reply of ours is. Anything else — a different text, unsure — is not settled here; the
     * task stays in doubt and the review closed to a second reply.
     */
    private static int settle(Services services, String[] args, PrintStream out) {
        UUID taskId;
        try {
            taskId = UUID.fromString(args[1]);
        } catch (IllegalArgumentException e) {
            out.println("not a task id");
            return 2;
        }
        ReplyTasks.Outcome outcome = switch (args[2]) {
            case "posted" -> ReplyTasks.Outcome.POSTED;
            case "not-posted" -> ReplyTasks.Outcome.FAILED;
            default -> null;
        };
        if (outcome == null) {
            return usage(out);
        }
        Optional<UUID> admin = services.delegates().idOf(args[3]);
        if (admin.isEmpty()) {
            out.println("no such admin");
            return 1;
        }
        try {
            ReplyTasks.TaskView settled = services.tasks().settleByOperator(taskId, admin.get(), outcome);
            out.println("settled: " + settled.state());
            return 0;
        } catch (ReplyTasks.TaskException e) {
            out.println(switch (e.failure()) {
                case NOT_ASSIGNED -> "not an active admin";
                case NOT_FOUND -> "no in-doubt task of a suspended delegate with that id";
                default -> "task moved; list again";
            });
            return 1;
        }
    }

    private interface CodeIssuer {
        String issue();
    }

    private static int joinLink(Services services, PrintStream out, String email, Delegates.Role role) {
        return link(services, out, () -> services.delegates().issueInvite(email, role),
                Delegates.INVITE_LIFETIME.toDays(), JOIN_PATH);
    }

    /** Issues an invite and prints its join link once — the code only ever in the link's fragment. */
    private static int link(Services services, PrintStream out, CodeIssuer issuer, long days, String path) {
        String code;
        try {
            code = issuer.issue();
        } catch (IllegalArgumentException e) {
            out.println("not an e-mail address");
            return 2;
        }
        String base = services.publicUrl() == null ? "" : services.publicUrl().strip().replaceAll("/+$", "");
        out.println(base.isEmpty()
                ? "join link path (valid " + days + " days, shown once; put the server's address in front): "
                        + path + code
                : "join link (valid " + days + " days, shown once): " + base + path + code);
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
