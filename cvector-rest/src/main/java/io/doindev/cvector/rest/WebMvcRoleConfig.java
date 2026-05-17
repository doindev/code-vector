package io.doindev.cvector.rest;

import io.doindev.cvector.core.CvectorRole;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@ConditionalOnWebApplication
public class WebMvcRoleConfig {

    @Bean
    public CvectorRole cvectorRestRole() {
        return CvectorRole.resolve();
    }

    @Bean
    public WebMvcConfigurer rolesWebMvcConfigurer(CvectorRole cvectorRestRole, ActiveProject activeProject) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                // Order matters: role-gating runs first so 403s don't reveal that an
                // endpoint exists when the caller doesn't have the role. The
                // active-project guard fires after — by the time a request reaches it,
                // it's authorised to run, just (potentially) missing the project context
                // the downstream controller needs.
                registry.addInterceptor(new RoleInterceptor(cvectorRestRole)).addPathPatterns("/api/**");
                registry.addInterceptor(new ActiveProjectRequiredInterceptor(activeProject)).addPathPatterns("/api/**");
            }
        };
    }
}
