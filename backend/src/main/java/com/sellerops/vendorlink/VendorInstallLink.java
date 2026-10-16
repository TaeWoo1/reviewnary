package com.sellerops.vendorlink;

import com.sellerops.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * <b>This PC's registration with the vendor server</b> — at most one row ({@link VendorLinkService} replaces it).
 * Contract {@code docs/vendor_server_contract_v1.md} §2.
 *
 * <p>The install token and the install's X25519 private key are both sealed under the vault key ring
 * ({@code LocalSecretSealer}); neither exists here in plain text. The public half was registered with the server
 * and is not kept — it is derivable from the private key.
 */
@Getter
@Setter
@Entity
@Table(name = "vendor_install_link")
public class VendorInstallLink extends BaseEntity {

    /** The server's id for this install; not a secret. */
    @Column(name = "install_id", nullable = false)
    private UUID installId;

    /** The platform name this PC reported (e.g. {@code macOS (aarch64)}), never a host name. */
    @Column(name = "device_name", nullable = false, length = 80)
    private String deviceName;

    @Column(name = "token_enc", nullable = false)
    private byte[] tokenEnc;

    @Column(name = "private_key_enc", nullable = false)
    private byte[] privateKeyEnc;

    @Column(name = "encryption_key_id", nullable = false, length = 100)
    private String encryptionKeyId;

    @Column(name = "token_expires_at", nullable = false)
    private Instant tokenExpiresAt;
}
