package com.sellerops.vendor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The vendor server: what the seller-PC product needs from a central service, and nothing else —
 * {@code docs/vendor_server_contract_v1.md} §1. It holds no channel credential and calls no channel API (the one
 * planned exception is the Cafe24 token endpoint, §5); it never sends the seller's PC an instruction to act.
 */
@SpringBootApplication
public class VendorServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(VendorServerApplication.class, args);
    }
}
