package com.amsati.shopscale.subscription;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {
    private final WriteGuardInterceptor writeGuard;
    private final com.amsati.shopscale.industry.V1ScopeInterceptor v1Scope;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(v1Scope).addPathPatterns("/api/**");
        registry.addInterceptor(writeGuard).addPathPatterns(
            "/api/products/**", "/api/categories/**", "/api/inventory/**",
            "/api/customers/**", "/api/sales/**", "/api/expenses/**", "/api/team/**", "/api/suppliers/**", "/api/purchases/**")
            .excludePathPatterns("/api/team/invitations/accept");
    }
}
