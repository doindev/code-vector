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
    public WebMvcConfigurer rolesWebMvcConfigurer(CvectorRole cvectorRestRole) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new RoleInterceptor(cvectorRestRole)).addPathPatterns("/api/**");
            }
        };
    }
}
