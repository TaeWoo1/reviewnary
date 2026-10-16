package com.sellerops.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Puts {@link LoopbackRequestGuard} in front of every other filter — Spring Security included — when the
 * backend runs on a seller's PC. Absent the flag, nothing is registered and no existing deployment changes.
 */
@Configuration
@ConditionalOnProperty(name = "sellerops.local-seller.enabled", havingValue = "true")
public class LocalSellerGuardConfiguration {

    @Bean
    public FilterRegistrationBean<LoopbackRequestGuard> loopbackRequestGuard() {
        FilterRegistrationBean<LoopbackRequestGuard> registration =
                new FilterRegistrationBean<>(new LoopbackRequestGuard());
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setName("loopbackRequestGuard");
        return registration;
    }
}
