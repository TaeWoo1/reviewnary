package com.sellerops.config;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * <b>On a seller's PC, the backend serves the screen itself</b> — proposal §18.7 packaging result: "backend가 화면을
 * 서빙하지 않는다(`/` → 401). 같은 출처로 서빙하는 경로가 필요하다". One installed program, one address on
 * {@code 127.0.0.1}: the seller's browser opens it, the screen calls {@code /api/*} on the same origin, and no dev
 * server, proxy or public website stands between the seller and their own data.
 *
 * <p>On only when local-seller mode is on <b>and</b> {@code sellerops.local-seller.screen-dir} names the built
 * frontend ({@code frontend/dist} of a {@code VITE_LOCAL_SELLER=true} build). Blank keeps today's shape — a developer
 * runs vite beside the backend — and every other deployment (nginx serves the web screen) is untouched.
 *
 * <p>What it may serve, and what it never becomes:
 * <ul>
 *   <li>Files under that directory, by GET or HEAD only. Any other path that is not the API's falls back to
 *       {@code index.html} so the screen's own routes ({@code /reviews/…}, {@code /link}) survive a reload.</li>
 *   <li>Never an API answer: {@code /api/**}, the OAuth paths, {@code /actuator} and {@code /health} are outside this
 *       chain and stay with {@link SecurityConfig}, so they still need their tokens, and an unknown {@code /api/x} is
 *       not answered with a page.</li>
 *   <li>Nothing outside the directory: Spring's resource resolver refuses {@code ..}, encoded traversal and paths that
 *       resolve outside its location.</li>
 * </ul>
 *
 * <p>The screen's Content-Security-Policy is the build-time {@code <meta>} the frontend build writes; the API chain's
 * {@code default-src 'none'} header would forbid the screen's own scripts, so this chain does not send it. The headers
 * nginx sends for the web screen ({@code frontend/nginx.conf}) are sent here instead. {@link LoopbackRequestGuard}
 * still runs first, so a rebinding {@code Host} gets no page either.
 */
@Configuration
@ConditionalOnExpression("${sellerops.local-seller.enabled:false} && '${sellerops.local-seller.screen-dir:}' != ''")
public class LocalSellerScreenConfiguration implements WebMvcConfigurer {

    /** Prefixes that belong to the API chain; a request under one of them is never a screen file. */
    static final List<String> NOT_SCREEN = List.of("/api/", "/oauth2/", "/login/oauth2/", "/actuator", "/health",
            "/error");

    private final Path screenDir;

    public LocalSellerScreenConfiguration(@Value("${sellerops.local-seller.screen-dir}") String screenDir) {
        Path dir = Path.of(screenDir).toAbsolutePath().normalize();
        // Fail closed at boot, like LocalSellerConfigValidator: an install whose screen is missing would answer every
        // page with a fallback that does not exist, and the seller would see a blank tab with nothing to say why.
        if (!Files.isRegularFile(dir.resolve("index.html"))) {
            throw new IllegalStateException("sellerops.local-seller.screen-dir does not hold a built screen "
                    + "(no index.html) — build the frontend with VITE_LOCAL_SELLER=true or leave the setting blank");
        }
        this.screenDir = dir;
    }

    /** A GET or HEAD whose path is not the API's. */
    static boolean isScreenRequest(HttpServletRequest request) {
        String method = request.getMethod();
        if (!HttpMethod.GET.matches(method) && !HttpMethod.HEAD.matches(method)) {
            return false;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        for (String prefix : NOT_SCREEN) {
            if (path.equals(prefix.replaceAll("/$", "")) || path.startsWith(prefix)) {
                return false;
            }
        }
        return true;
    }

    @Bean
    @Order(1)
    public SecurityFilterChain localSellerScreenChain(HttpSecurity http) throws Exception {
        RequestMatcher screen = LocalSellerScreenConfiguration::isScreenRequest;
        http.securityMatcher(screen)
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(headers -> headers
                        // The resource handlers below set each file's caching; Security's no-store would undo it.
                        .cacheControl(cache -> cache.disable())
                        .frameOptions(frame -> frame.deny())
                        .referrerPolicy(rp -> rp.policy(
                                ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        .permissionsPolicy(pp -> pp.policy(
                                "camera=(), microphone=(), geolocation=(), payment=()")))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    /** {@code /} has no file name, and a resource handler answers an empty path with nothing before any resolver. */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("forward:/index.html");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = screenDir.toUri().toString();
        // Hashed bundles never change under their name; index.html must be fetched fresh so an update shows at once.
        // Their own handler, with no fallback: a missing bundle is a 404, not the entry page served as a script.
        registry.addResourceHandler("/assets/**")
                .addResourceLocations(location + "assets/")
                .setCacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable())
                .resourceChain(true)
                .addResolver(new PathResourceResolver());
        registry.addResourceHandler("/**")
                .addResourceLocations(location)
                .setCacheControl(CacheControl.noStore())
                .resourceChain(true)
                .addResolver(new ScreenFallbackResolver(new FileSystemResource(screenDir.resolve("index.html"))));
    }

    /** A real file when there is one; otherwise the screen's entry page — except for anything the API chain owns. */
    static final class ScreenFallbackResolver extends PathResourceResolver {
        private final Resource index;

        ScreenFallbackResolver(Resource index) {
            this.index = index;
        }

        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            Resource found = super.getResource(resourcePath, location);
            if (found != null) {
                return found;
            }
            String path = "/" + resourcePath;
            for (String prefix : NOT_SCREEN) {
                if (path.startsWith(prefix.replaceAll("/$", ""))) {
                    return null;
                }
            }
            return index;
        }
    }
}
