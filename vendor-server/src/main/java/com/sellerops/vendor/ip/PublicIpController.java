package com.sellerops.vendor.ip;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /v1/ip} — what this server sees as the caller's public IPv4, for the NAVER call-IP guidance on the
 * seller's PC (contract §7, proposal §17.9). Nothing is stored or logged.
 *
 * <p>Install-token only (the security chain keeps it closed until install authentication exists), so the service is
 * not a public IP-lookup endpoint for anyone who finds it.
 */
@RestController
public class PublicIpController {

    @GetMapping("/v1/ip")
    public ResponseEntity<Map<String, String>> ip(HttpServletRequest request) {
        return ClientIpv4.of(request.getRemoteAddr())
                .map(v4 -> ResponseEntity.ok(Map.of("ipv4", v4)))
                .orElseGet(() -> ResponseEntity.unprocessableEntity().body(Map.of("error", "ipv4_unavailable")));
    }
}
