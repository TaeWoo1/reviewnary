package com.sellerops.vendor.ops;

import com.sellerops.vendor.account.SellerAccounts;
import com.sellerops.vendor.auth.SessionRevocations;
import com.sellerops.vendor.auth.SessionTokens;
import com.sellerops.vendor.delegate.Delegates;
import com.sellerops.vendor.install.Installs;
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
 *   java -jar vendor-server.jar recover-seller seller@example.com [new@example.com]  # recovery link; same account
 *   java -jar vendor-server.jar installs seller@example.com            # the seller's linked PCs
 *   java -jar vendor-server.jar revoke-install seller@example.com &lt;install-id&gt;
 *   java -jar vendor-server.jar invite-delegate me@example.com [admin] # prints the delegate's join link once
 *   java -jar vendor-server.jar suspend-delegate me@example.com        # ends sessions and every store assignment
 *   java -jar vendor-server.jar reinstate-delegate me@example.com      # the account only; assign stores again
 *   java -jar vendor-server.jar add-store seller@example.com 라벨       # a seller's NAVER store; prints its id
 *   java -jar vendor-server.jar assign me@example.com &lt;store-id&gt;     # opens it; it works once checked
 *   java -jar vendor-server.jar checklist                              # every store's two checks, to go through
 *   java -jar vendor-server.jar check-owner &lt;store-id&gt; seller-center-screen|submanager-invite admin@example.com
 *   java -jar vendor-server.jar check-assignment me@example.com &lt;store-id&gt; admin@example.com
 *   java -jar vendor-server.jar unassign me@example.com &lt;store-id&gt;
 *   java -jar vendor-server.jar purge                                  # one retention pass (§6); after a restore, before serving
 *   java -jar vendor-server.jar in-doubt                               # a suspended delegate's in-doubt tasks, to check
 *   java -jar vendor-server.jar unconfirmed                            # posts the channel has not shown for too long
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
            "in-doubt", "settle", "checklist", "check-owner", "check-assignment", "recover-seller", "installs",
            "revoke-install", "unconfirmed");

    static final String USAGE = "usage: invite|suspend|reinstate|end-sessions <email> · "
            + "invite-delegate <email> [admin] · "
            + "suspend-delegate|reinstate-delegate <email> · add-store <seller-email> <label> · "
            + "assign|unassign <delegate-email> <store-id> · purge · in-doubt · unconfirmed · "
            + "settle <task-id> posted|not-posted <admin-email> · checklist · "
            + "check-owner <store-id> seller-center-screen|submanager-invite <admin-email> · "
            + "check-assignment <delegate-email> <store-id> <admin-email> · "
            + "recover-seller <email> [new-email] · installs <seller-email> · revoke-install <seller-email> <install-id>";

    /** The page a delegate's invite link opens; the code follows {@code #}. */
    public static final String JOIN_PATH = "/delegate/join#";

    /** The page a seller's invite link opens; the code follows {@code #}. */
    public static final String SELLER_JOIN_PATH = "/join#";

    /** The services the commands act on, and the server's public address ({@code ""} when not set). */
    public record Services(SellerAccounts accounts, Delegates delegates, Stores stores,
                           SessionRevocations revocations, Retention retention, ReplyTasks tasks, Installs installs,
                           String publicUrl) {}

    private OpsCommands() {
    }

    /** Runs one command; returns the process exit code. */
    public static int run(Services services, String[] args, PrintStream out) {
        if (args.length == 1 && "purge".equals(args[0])) {
            Retention.Purged purged = services.retention().purge();
            out.println("deleted: reviews " + purged.reviews() + " · tasks " + purged.tasks() + " · invites "
                    + purged.invites() + " · logged-out sessions " + purged.sessions() + " · step-up codes "
                    + purged.stepUps());
            return 0;
        }
        if (args.length == 1 && "in-doubt".equals(args[0])) {
            return inDoubt(services, out);
        }
        if (args.length == 1 && "unconfirmed".equals(args[0])) {
            return unconfirmed(services, out);
        }
        if (args.length == 4 && "settle".equals(args[0])) {
            return settle(services, args, out);
        }
        if (args.length == 1 && "checklist".equals(args[0])) {
            return checklist(services, out);
        }
        if (args.length == 4 && "check-owner".equals(args[0])) {
            return checkOwner(services, args, out);
        }
        if (args.length == 4 && "check-assignment".equals(args[0])) {
            return checkAssignment(services, args, out);
        }
        if ((args.length == 2 || args.length == 3) && "recover-seller".equals(args[0])) {
            return recoverSeller(services, args, out);
        }
        if (args.length == 2 && "installs".equals(args[0])) {
            return sellerInstalls(services, args[1], out);
        }
        if (args.length == 3 && "revoke-install".equals(args[0])) {
            return revokeInstall(services, args, out);
        }
        if (args.length < 2 || !NAMES.contains(args[0])
                || Set.of("purge", "in-doubt", "settle", "checklist", "check-owner", "check-assignment",
                        "recover-seller", "installs", "revoke-install", "unconfirmed").contains(args[0])) {
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
            return done(out, services.stores().assign(storeId, delegate.get()),
                    "assigned; it works once checked (checklist)", "already assigned");
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
     * Delegates' posts the channel has not shown for too long (owner decision 2026-10-25: {@value
     * ReplyTasks#CONFIRM_UPLOADS} newer uploads or 7 days). The seller sees 「운영팀 확인」 and has nothing to do; an
     * operator looks in Seller Center and takes it up with the delegate. Nothing here completes or moves a task.
     */
    private static int unconfirmed(Services services, PrintStream out) {
        List<ReplyTasks.Unconfirmed> tasks = services.tasks().unconfirmed();
        if (tasks.isEmpty()) {
            out.println("no unconfirmed posts");
            return 0;
        }
        for (ReplyTasks.Unconfirmed t : tasks) {
            out.println("task " + t.taskId() + " · " + t.storeLabel() + " · 리뷰번호 " + t.reviewNo() + " · posted "
                    + t.postedAt() + " · uploads since " + t.uploadsSince());
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

    /**
     * The store check (contract §2), as a list to go through before any seller's reviews reach the server. Each store
     * says its two questions and, once answered, who answered when. Nothing about reviews is printed.
     */
    private static int checklist(Services services, PrintStream out) {
        List<Stores.ChecklistEntry> entries = services.stores().checklist();
        if (entries.isEmpty()) {
            out.println("no stores");
            return 0;
        }
        for (Stores.ChecklistEntry e : entries) {
            out.println("store " + e.storeId() + " · " + e.label() + " · seller " + e.sellerEmail() + " · "
                    + (e.receives() ? "receives reviews" : "does not receive reviews yet"));
            out.println(e.ownerCheckedAt() == null
                    ? "  [ ] 판매자 소유 — 이 스토어가 판매자 계정 주인의 것인가: check-owner " + e.storeId()
                            + " seller-center-screen|submanager-invite <admin-email>"
                    : "  [x] 판매자 소유 — " + e.ownerCheckedBy() + " · " + e.ownerCheckedAt() + " · "
                            + basisWord(e.ownerBasis()));
            if (e.assignments().isEmpty()) {
                out.println("  [ ] 담당자 배정 — 배정된 담당자 없음: assign <delegate-email> " + e.storeId());
            }
            for (Stores.AssignmentCheck a : e.assignments()) {
                out.println(a.checkedAt() == null
                        ? "  [ ] 담당자 배정 " + a.delegateEmail() + " — 그 담당자의 네이버 계정이 판매자센터에서 이 스토어의 "
                                + "부매니저인가: check-assignment " + a.delegateEmail() + " " + e.storeId()
                                + " <admin-email>"
                        : "  [x] 담당자 배정 " + a.delegateEmail() + " — " + a.checkedBy() + " · " + a.checkedAt());
            }
        }
        return 0;
    }

    private static int checkOwner(Services services, String[] args, PrintStream out) {
        Optional<UUID> storeId = uuid(args[1]);
        Stores.OwnerBasis basis = switch (args[2]) {
            case "seller-center-screen" -> Stores.OwnerBasis.SELLER_CENTER_SCREEN;
            case "submanager-invite" -> Stores.OwnerBasis.SUBMANAGER_INVITE;
            default -> null;
        };
        if (storeId.isEmpty() || basis == null) {
            return usage(out);
        }
        Optional<UUID> admin = services.delegates().idOf(args[3]);
        return checked(out, admin.isEmpty() ? Stores.Check.NOT_ADMIN
                : services.stores().checkOwner(storeId.get(), admin.get(), basis), "no such store");
    }

    private static int checkAssignment(Services services, String[] args, PrintStream out) {
        Optional<UUID> storeId = uuid(args[2]);
        if (storeId.isEmpty()) {
            out.println("not a store id");
            return 2;
        }
        Optional<UUID> delegate = services.delegates().idOf(args[1]);
        Optional<UUID> admin = services.delegates().idOf(args[3]);
        if (delegate.isEmpty()) {
            out.println("no such delegate");
            return 1;
        }
        return checked(out, admin.isEmpty() ? Stores.Check.NOT_ADMIN
                : services.stores().checkAssignment(storeId.get(), delegate.get(), admin.get()),
                "no open assignment of that delegate to that store");
    }

    private static int checked(PrintStream out, Stores.Check check, String notFound) {
        out.println(switch (check) {
            case CHECKED -> "checked";
            case ALREADY_CHECKED -> "already checked";
            case NOT_ADMIN -> "not an active admin";
            case NOT_FOUND -> notFound;
        });
        return check == Stores.Check.CHECKED || check == Stores.Check.ALREADY_CHECKED ? 0 : 1;
    }

    private static String basisWord(Stores.OwnerBasis basis) {
        return basis == Stores.OwnerBasis.SELLER_CENTER_SCREEN ? "seller-center-screen" : "submanager-invite";
    }

    private static Optional<UUID> uuid(String raw) {
        try {
            return Optional.of(UUID.fromString(raw));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * A seller who lost their mailbox or password — after the operator has checked who is asking, outside this
     * server. Prints a recovery link for the same account (to the new address when given) and the account's PCs, to
     * review: a PC the seller does not recognise is revoked with {@code revoke-install}.
     */
    private static int recoverSeller(Services services, String[] args, PrintStream out) {
        Optional<UUID> account = services.accounts().idOf(args[1]);
        if (account.isEmpty()) {
            out.println("no such account");
            return 1;
        }
        String newEmail = args.length == 3 ? args[2] : null;
        int printed = link(services, out, () -> {
            try {
                return services.accounts().issueRecovery(account.get(), newEmail);
            } catch (IllegalStateException e) {
                throw new IllegalArgumentException("address in use");
            }
        }, SellerAccounts.INVITE_LIFETIME.toDays(), SELLER_JOIN_PATH);
        if (printed != 0) {
            return printed;
        }
        out.println("redeeming it sets a new password on the same account and ends its sessions; stores, tasks and "
                + "PCs stay. Review the PCs:");
        return sellerInstalls(services, args[1], out);
    }

    private static int sellerInstalls(Services services, String email, PrintStream out) {
        Optional<UUID> account = services.accounts().idOf(email);
        if (account.isEmpty()) {
            out.println("no such account");
            return 1;
        }
        List<Installs.View> pcs = services.installs().list(account.get());
        if (pcs.isEmpty()) {
            out.println("no linked PCs");
        }
        for (Installs.View pc : pcs) {
            out.println("install " + pc.id() + " · " + pc.deviceName() + " · linked " + pc.createdAt()
                    + " · last used " + (pc.lastUsedAt() == null ? "never" : pc.lastUsedAt()));
        }
        return 0;
    }

    private static int revokeInstall(Services services, String[] args, PrintStream out) {
        Optional<UUID> account = services.accounts().idOf(args[1]);
        Optional<UUID> install = uuid(args[2]);
        if (install.isEmpty()) {
            out.println("not an install id");
            return 2;
        }
        if (account.isEmpty()) {
            out.println("no such account");
            return 1;
        }
        return done(out, services.installs().revoke(account.get(), install.get()), "revoked",
                "no such install of that account");
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
            out.println("address in use".equals(e.getMessage()) ? "that address belongs to another account"
                    : "not an e-mail address");
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
