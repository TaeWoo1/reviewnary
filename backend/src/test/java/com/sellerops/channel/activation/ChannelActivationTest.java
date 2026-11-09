package com.sellerops.channel.activation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sellerops.channel.Channel;
import com.sellerops.channel.ChannelRepository;
import com.sellerops.channel.ChannelStatus;
import com.sellerops.channel.activation.ChannelActivation.Level;
import com.sellerops.common.ApiException;
import com.sellerops.credential.ConnectorCredentialRepository;
import com.sellerops.credential.CredentialVault;
import com.sellerops.selleraccount.SellerAccount;
import com.sellerops.selleraccount.SellerAccountRepository;
import com.sellerops.selleraccount.SellerAccountService;
import com.sellerops.selleraccount.dto.ApiChannelRequest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * <b>A channel's official connection opens only where its qualification exists</b> — proposal §19.4 T1. Held at the
 * vault (every credential the official API is reached with is stored, rotated and opened there) and before a seller
 * is given an API account to fill; a file upload is not gated; not enforced, everything is as it was.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class ChannelActivationTest {

    @Autowired ConnectorCredentialRepository credentials;
    @Autowired SellerAccountRepository accounts;
    @Autowired ChannelRepository channels;

    private final UUID own = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();
    private final Map<String, String> secrets = Map.of("accessKey", "AK", "secretKey", "SK");
    private Channel naver;
    private Channel coupang;

    @BeforeEach
    void channels() {
        naver = channels.findByCode("NAVER").orElseGet(() -> save("NAVER"));
        coupang = channels.findByCode("COUPANG").orElseGet(() -> save("COUPANG"));
    }

    private Channel save(String code) {
        Channel c = new Channel();
        c.setCode(code);
        c.setNameKo(code);
        c.setStatus(ChannelStatus.AVAILABLE);
        c.setSortOrder(0);
        return channels.save(c);
    }

    private ChannelActivation activation(boolean enforced, Level naverLevel) {
        return new ChannelActivation(enforced, Set.of(own), Map.of("NAVER", naverLevel), accounts, channels);
    }

    @Test
    void levelsSayWhoMayUseTheChannel() {
        assertThat(activation(false, Level.OFF).allows(other, "NAVER")).as("not enforced: as it was").isTrue();
        assertThat(activation(true, Level.OFF).allows(own, "NAVER")).isFalse();
        assertThat(activation(true, Level.SELF).allows(own, "NAVER")).isTrue();
        assertThat(activation(true, Level.SELF).allows(other, "NAVER")).isFalse();
        assertThat(activation(true, Level.ALL).allows(other, "NAVER")).isTrue();
        assertThat(activation(true, Level.ALL).allows(own, "COUPANG")).as("a channel not named is off").isFalse();
    }

    @Test
    void theVaultNeitherStoresNorOpensACredentialOnAChannelThatIsNotOpen() {
        UUID ownAccount = account(own, naver, false);
        UUID otherAccount = account(other, naver, false);
        CredentialVault vault = new CredentialVault(credentials, new ObjectMapper(), key(), "k1");
        vault.store(other, otherAccount, "API", "HMAC", secrets, null, null, null);
        vault.setActivation(activation(true, Level.SELF));

        assertThatThrownBy(() -> vault.open(other, otherAccount)).as("stored before, not opened now")
                .isInstanceOf(ApiException.class).hasMessage(ChannelActivation.NOT_YET);
        assertThatThrownBy(() -> vault.rotateSecrets(other, otherAccount, secrets)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> vault.store(other, otherAccount, "API", "HMAC", secrets, null, null, null))
                .isInstanceOf(ApiException.class);

        vault.store(own, ownAccount, "API", "HMAC", secrets, null, null, null);
        assertThat(vault.open(own, ownAccount).secrets()).containsEntry("accessKey", "AK");

        vault.setActivation(activation(true, Level.OFF));
        assertThatThrownBy(() -> vault.open(own, ownAccount)).as("turned off: even the operator's own store")
                .isInstanceOf(ApiException.class);
    }

    @Test
    void noApiAccountIsGivenOnAClosedChannelAndAFileUploadIsNotGated() {
        SellerAccountService service = new SellerAccountService(accounts, channels);
        service.setActivation(activation(true, Level.SELF));
        assertThatThrownBy(() -> service.registerApiChannel(other, new ApiChannelRequest(naver.getId(), null)))
                .isInstanceOf(ApiException.class).hasMessage(ChannelActivation.NOT_YET);
        assertThat(accounts.findAllByOrgIdAndChannelId(other, naver.getId())).isEmpty();
        assertThat(service.registerApiChannel(own, new ApiChannelRequest(naver.getId(), null)).id()).isNotNull();

        UUID file = account(other, naver, true);
        activation(true, Level.OFF).requireForAccount(other, file);
    }

    private UUID account(UUID org, Channel channel, boolean fileUpload) {
        SellerAccount a = new SellerAccount();
        a.setOrgId(org);
        a.setChannelId(channel.getId());
        a.setConnectionStatus(ChannelStatus.PENDING);
        a.setFileUpload(fileUpload);
        return accounts.save(a).getId();
    }

    private static String key() {
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        return Base64.getEncoder().encodeToString(k);
    }
}
