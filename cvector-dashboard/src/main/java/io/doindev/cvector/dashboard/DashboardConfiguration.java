package io.doindev.cvector.dashboard;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;


/**
 * Wires the built Angular SPA into Spring's static-resource handler chain. The Angular
 * build (via {@code mvn -P dashboard-ui package}) drops its output under
 * {@code src/main/resources/static/dashboard/} which Spring Boot's classpath:static/
 * auto-config already serves -- but we add an explicit handler so the SPA's deep links
 * (e.g. {@code /dashboard/monitors}) fall back to {@code index.html} on the client,
 * rather than 404 because Spring couldn't find a matching static file.
 *
 * <p>The REST API on {@code /api/**} is unaffected -- those routes are mapped by
 * {@code @RestController} beans elsewhere and take precedence.
 */
@Configuration
public class DashboardConfiguration implements WebMvcConfigurer {

    /** Path the SPA mounts at, both for static assets and client-side routing. */
    public static final String DASHBOARD_PATH = "/dashboard";

    /**
     * Classpath location the Angular build drops its dist into. Angular 17's application
     * builder wraps the SSR-ready output in a {@code browser/} subdirectory whether or not
     * SSR is enabled, so the actual {@code index.html} + hashed chunks live one level
     * below the {@code --output-path} the build invocation specifies.
     */
    private static final String DASHBOARD_CLASSPATH = "classpath:/static/dashboard/browser/";

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Single handler — Angular 17's application builder hashes every JS/CSS chunk, so
        // we can cache them aggressively. index.html is small (~6 KB) and the SPA's bootstrap
        // path; we trade a few KB of re-fetch on each page load for the deploy-safety of
        // never serving a stale index that references missing hashes. A per-path cache split
        // tripped over Spring's pattern matching for literal paths under a resource location
        // — keep this simple unless we add a HandlerInterceptor that varies headers by suffix.
        registry.addResourceHandler(DASHBOARD_PATH + "/**")
                .addResourceLocations(DASHBOARD_CLASSPATH)
                .setCacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                .resourceChain(true);
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // SPA fallback: any GET that doesn't match a static file or REST route under
        // /dashboard returns index.html so the Angular Router can take over. The
        // forward (not redirect) keeps the URL bar honest.
        registry.addViewController(DASHBOARD_PATH).setViewName("forward:" + DASHBOARD_PATH + "/index.html");
        registry.addViewController(DASHBOARD_PATH + "/").setViewName("forward:" + DASHBOARD_PATH + "/index.html");
        // Catch-all for deep links like /dashboard/monitors, /dashboard/settings, etc.
        registry.addViewController(DASHBOARD_PATH + "/{path:[^.]*}")
                .setViewName("forward:" + DASHBOARD_PATH + "/index.html");
    }
}
