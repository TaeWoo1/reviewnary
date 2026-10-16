package com.sellerops.vendor.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.vendor.account.SellerAccounts;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class OpsCommandsTest {

    @Autowired
    private SellerAccounts accounts;

    @Test
    void theInviteCodePrintedOnceRedeemsIntoAnAccount() {
        String email = "ops-" + UUID.randomUUID() + "@example.com";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(OpsCommands.run(accounts, new String[] {"invite", email},
                new PrintStream(out, true, StandardCharsets.UTF_8))).isZero();
        String printed = out.toString(StandardCharsets.UTF_8).trim();
        String code = printed.substring(printed.lastIndexOf(' ') + 1);
        assertThat(accounts.redeem(code, "a long enough password")).isNotNull();
        assertThat(OpsCommands.run(accounts, new String[] {"suspend", email}, new PrintStream(out))).isZero();
        assertThat(OpsCommands.run(accounts, new String[] {"suspend", "nobody@example.com"},
                new PrintStream(out))).isEqualTo(1);
    }

    @Test
    void anythingElseIsAUsageError() {
        PrintStream out = new PrintStream(new ByteArrayOutputStream());
        assertThat(OpsCommands.run(accounts, new String[] {"invite"}, out)).isEqualTo(2);
        assertThat(OpsCommands.run(accounts, new String[] {"delete", "a@example.com"}, out)).isEqualTo(2);
        assertThat(OpsCommands.run(accounts, new String[] {"invite", "not-an-address"}, out)).isEqualTo(2);
    }
}
