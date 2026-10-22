package com.sellerops.vendor;

import com.sellerops.vendor.account.SellerAccounts;
import com.sellerops.vendor.auth.SessionRevocations;
import com.sellerops.vendor.delegate.Delegates;
import com.sellerops.vendor.ops.OpsCommands;
import com.sellerops.vendor.retention.Retention;
import com.sellerops.vendor.store.Stores;
import com.sellerops.vendor.task.ReplyTasks;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The vendor server: what the seller-PC product needs from a central service, and nothing else —
 * {@code docs/vendor_server_contract_v1.md} §1. It holds no channel credential and calls no channel API (the one
 * planned exception is the Cafe24 token endpoint, §5); it never sends the seller's PC an instruction to act.
 *
 * <p>With an operator command as the first argument ({@link OpsCommands}) it starts without the web server, runs
 * that one command against the same database, and exits.
 */
@SpringBootApplication
public class VendorServerApplication {

    public static void main(String[] args) {
        if (args.length > 0 && OpsCommands.NAMES.contains(args[0])) {
            int code;
            try (ConfigurableApplicationContext context = new SpringApplicationBuilder(VendorServerApplication.class)
                    .web(WebApplicationType.NONE).run()) {
                code = OpsCommands.run(new OpsCommands.Services(context.getBean(SellerAccounts.class),
                        context.getBean(Delegates.class), context.getBean(Stores.class),
                        context.getBean(SessionRevocations.class), context.getBean(Retention.class),
                        context.getBean(ReplyTasks.class),
                        context.getEnvironment().getProperty("vendor.public-url", "")), args, System.out);
            }
            System.exit(code);
        }
        SpringApplication.run(VendorServerApplication.class, args);
    }
}
