package com.sellerops.inquiry.publish;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * Registers {@link FakeChannelReplyAdapter} for the development check of approval → registration → verification
 * (2026-11-05), and beside it the fake channel's board and collector (2026-11-06), so a registered answer — or one the
 * seller wrote on the seller centre — comes back through the ordinary collection path. Never on a prod profile, and never beside the real adapters: with
 * {@code sellerops.inquiry.publish.execution-enabled=true} the real ESM adapter registers unconditionally, and a fake
 * beside it would leave a deployment able to send real answers while its operator believes it is testing.
 */
@Configuration
@ConditionalOnProperty(name = "sellerops.dev.fake-channel", havingValue = "true")
public class FakeChannelWiring {

    @Bean
    com.sellerops.connector.FakeChannelBoard fakeChannelBoard(Environment env) {
        refuseOutsideDevelopment(env);
        String file = env.getProperty("sellerops.dev.fake-channel-board", "");
        return new com.sellerops.connector.FakeChannelBoard(file.isBlank() ? null : java.nio.file.Path.of(file));
    }

    @Bean
    ChannelReplyAdapter fakeChannelReplyAdapter(Environment env, com.sellerops.connector.FakeChannelBoard board) {
        refuseOutsideDevelopment(env);
        return new FakeChannelReplyAdapter(env.getProperty("sellerops.dev.fake-channel-code", "CAFE24"), board);
    }

    /**
     * The fake channel's collector (2026-11-06): what the board shows, through the ordinary collection path. Never beside
     * the real connector for the same code — two connectors dedicated to one channel would leave which one collects to
     * the bean order.
     */
    @Bean
    com.sellerops.connector.FakeChannelInquiryConnector fakeChannelInquiryConnector(Environment env,
            com.sellerops.connector.FakeChannelBoard board) {
        refuseOutsideDevelopment(env);
        String code = env.getProperty("sellerops.dev.fake-channel-code", "CAFE24");
        if ("CAFE24".equals(code) && env.getProperty("sellerops.connector.cafe24.enabled", Boolean.class, false)) {
            throw new IllegalStateException(
                    "sellerops.dev.fake-channel cannot collect beside the real Cafe24 connector (cafe24 is enabled)");
        }
        return new com.sellerops.connector.FakeChannelInquiryConnector(code, board);
    }

    private static void refuseOutsideDevelopment(Environment env) {
        if (env.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException("sellerops.dev.fake-channel is for development and cannot run on prod");
        }
        if (env.getProperty("sellerops.inquiry.publish.execution-enabled", Boolean.class, false)) {
            throw new IllegalStateException(
                    "sellerops.dev.fake-channel cannot run beside the real reply adapters (publish execution is on)");
        }
    }
}
