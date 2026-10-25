package com.sellerops.vendor.delegate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * <b>Google as a delegate's way in</b> — contract §2 (D3 revised by the owner, 2026-10-26).
 *
 * <p>OpenID Connect authorization code flow with PKCE: the browser goes to Google, Google sends it back to
 * {@link #CALLBACK_PATH} with a code, and this server trades that code for an ID token in its own call to Google's
 * token endpoint. Only {@code openid email} is asked for — the account number ({@code sub}) and a verified address.
 *
 * <p>The ID token's signature is not checked: it arrives in this server's own TLS call to the token endpoint, which
 * OpenID Connect Core §3.1.3.7 (6) allows in place of the signature. Issuer, audience, expiry, nonce and a verified
 * address are checked here, every time.
 *
 * <p>Off unless a client id, a client secret and the server's public address are all set — then the pages offer
 * only the password and authenticator path.
 */
@Component
public class GoogleSignIn {

    static final String AUTHORIZE = "https://accounts.google.com/o/oauth2/v2/auth";
    static final String TOKEN = "https://oauth2.googleapis.com/token";
    static final Set<String> ISSUERS = Set.of("https://accounts.google.com", "accounts.google.com");
    public static final String CALLBACK_PATH = "/v1/delegate/auth/google/callback";
    private static final Duration LEEWAY = Duration.ofSeconds(60);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Google's token endpoint: the form in, the response's {@code id_token} out. */
    public interface TokenEndpoint {
        String idToken(Map<String, String> form) throws IOException;
    }

    /** The account Google vouched for. */
    public record Identity(String sub, String email) {}

    /** Google did not give a usable answer; the reason stays in the server, never on the page. */
    public static final class Refused extends RuntimeException {
        Refused(String reason) {
            super(reason);
        }
    }

    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;
    private final TokenEndpoint endpoint;
    private final Clock clock;

    @Autowired
    public GoogleSignIn(@Value("${vendor.google.client-id:}") String clientId,
                        @Value("${vendor.google.client-secret:}") String clientSecret,
                        @Value("${vendor.public-url:}") String publicUrl) {
        this(clientId, clientSecret, publicUrl, new Http(), Clock.systemUTC());
    }

    public GoogleSignIn(String clientId, String clientSecret, String publicUrl, TokenEndpoint endpoint, Clock clock) {
        this.clientId = clientId == null ? "" : clientId.strip();
        this.clientSecret = clientSecret == null ? "" : clientSecret.strip();
        String base = publicUrl == null ? "" : publicUrl.strip().replaceAll("/+$", "");
        this.redirectUri = base.isEmpty() ? "" : base + CALLBACK_PATH;
        this.endpoint = endpoint;
        this.clock = clock;
    }

    public boolean enabled() {
        return !clientId.isEmpty() && !clientSecret.isEmpty() && !redirectUri.isEmpty();
    }

    /** Where the browser goes. {@code loginHint}: the invited address, so Google offers that account first. */
    public String authorizeUrl(String state, String nonce, String codeVerifier, String loginHint) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("client_id", clientId);
        q.put("redirect_uri", redirectUri);
        q.put("response_type", "code");
        q.put("scope", "openid email");
        q.put("state", state);
        q.put("nonce", nonce);
        q.put("code_challenge", challenge(codeVerifier));
        q.put("code_challenge_method", "S256");
        q.put("prompt", "select_account");
        if (loginHint != null && !loginHint.isBlank()) {
            q.put("login_hint", loginHint);
        }
        return AUTHORIZE + "?" + form(q);
    }

    /** Trades Google's code for the account it names. Throws {@link Refused} for every way that can go wrong. */
    public Identity exchange(String code, String codeVerifier, String nonce) {
        if (code == null || code.isBlank()) {
            throw new Refused("no code");
        }
        Map<String, String> f = new LinkedHashMap<>();
        f.put("grant_type", "authorization_code");
        f.put("code", code);
        f.put("redirect_uri", redirectUri);
        f.put("client_id", clientId);
        f.put("client_secret", clientSecret);
        f.put("code_verifier", codeVerifier);
        String idToken;
        try {
            idToken = endpoint.idToken(f);
        } catch (IOException e) {
            throw new Refused("token endpoint " + e.getClass().getSimpleName());
        }
        return verify(idToken, clientId, nonce, clock.instant());
    }

    /** The ID token's claims, checked — see the class comment for why its signature is not. */
    static Identity verify(String idToken, String clientId, String nonce, Instant now) {
        JsonNode claims;
        try {
            String[] parts = idToken == null ? new String[0] : idToken.split("\\.");
            if (parts.length != 3) {
                throw new Refused("not a JWT");
            }
            claims = JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
        } catch (IOException | IllegalArgumentException e) {
            throw new Refused("unreadable ID token");
        }
        if (!ISSUERS.contains(claims.path("iss").asText())) {
            throw new Refused("issuer");
        }
        JsonNode aud = claims.path("aud");
        boolean ours = aud.isArray()
                ? StreamSupport.stream(aud.spliterator(), false).anyMatch(a -> clientId.equals(a.asText()))
                : clientId.equals(aud.asText());
        if (!ours || (claims.has("azp") && !clientId.equals(claims.path("azp").asText()))) {
            throw new Refused("audience");
        }
        if (!claims.path("exp").canConvertToLong()
                || Instant.ofEpochSecond(claims.path("exp").asLong()).plus(LEEWAY).isBefore(now)) {
            throw new Refused("expired");
        }
        if (nonce == null || !MessageDigest.isEqual(nonce.getBytes(StandardCharsets.UTF_8),
                claims.path("nonce").asText().getBytes(StandardCharsets.UTF_8))) {
            throw new Refused("nonce");
        }
        JsonNode verified = claims.path("email_verified");
        if (!(verified.asBoolean(false) || "true".equals(verified.asText()))) {
            throw new Refused("address not verified");
        }
        String sub = claims.path("sub").asText();
        String email = claims.path("email").asText();
        if (sub.isBlank() || email.isBlank()) {
            throw new Refused("no account");
        }
        return new Identity(sub, email);
    }

    /** PKCE S256: base64url(SHA-256(verifier)), no padding. */
    static String challenge(String verifier) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String form(Map<String, String> values) {
        return values.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    /** The real call: a form POST to {@link #TOKEN}, ten seconds at most. The response body is never logged. */
    static final class Http implements TokenEndpoint {
        private final HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        @Override
        public String idToken(Map<String, String> f) throws IOException {
            HttpRequest request = HttpRequest.newBuilder(URI.create(TOKEN))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(form(f)))
                    .build();
            HttpResponse<String> response;
            try {
                response = client.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted");
            }
            if (response.statusCode() != 200) {
                throw new IOException("status " + response.statusCode());
            }
            String idToken = JSON.readTree(response.body()).path("id_token").asText("");
            if (idToken.isEmpty()) {
                throw new IOException("no id_token");
            }
            return idToken;
        }
    }
}
