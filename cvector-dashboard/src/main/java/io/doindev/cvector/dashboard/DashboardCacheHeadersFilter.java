package io.doindev.cvector.dashboard;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * Promotes hashed Angular build artefacts under {@code /dashboard/**} to a 1-year immutable
 * cache while leaving {@code index.html} (and other unhashed entry-point files) on the
 * shorter default {@link DashboardConfiguration} sets. The split is filter-based rather than
 * resource-handler-based because Spring's resource-handler pattern matching can't reliably
 * resolve a literal path (e.g. {@code /dashboard/index.html}) against a glob location — see
 * the project memory for the symptoms when we tried.
 *
 * <p>The hashed-asset signature is the trailing {@code -ABCDEFGH.ext} that Angular CLI
 * emits for every JS/CSS chunk and bundled asset; we recognise it conservatively to avoid
 * mis-caching any future entry point.
 */
@Configuration
public class DashboardCacheHeadersFilter {

    /** Match {@code -ABCD1234.js}, {@code -ABCD1234.css}, …, in the last URL segment. */
    private static final Pattern HASHED_ASSET = Pattern.compile(
            ".*-[A-Z0-9]{8,}\\.(js|css|woff|woff2|ttf|svg|png|jpg|jpeg|ico)$");

    private static final String IMMUTABLE = "public, max-age=31536000, immutable";

    @Bean
    public FilterRegistrationBean<OncePerRequestFilter> dashboardCacheHeadersFilterRegistration() {
        FilterRegistrationBean<OncePerRequestFilter> reg = new FilterRegistrationBean<>(new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
                    throws ServletException, IOException {
                chain.doFilter(req, resp);
                // postHandle: override the resource handler's Cache-Control only for hashed
                // assets and only when the response is OK. Doing it after the chain lets us
                // see the URI Spring resolved and skip any error responses.
                if (resp.getStatus() == HttpServletResponse.SC_OK) {
                    String uri = req.getRequestURI();
                    if (uri != null && uri.startsWith("/dashboard/") && HASHED_ASSET.matcher(uri).matches()) {
                        resp.setHeader("Cache-Control", IMMUTABLE);
                    }
                }
            }
        });
        // Static-resource URLs only — REST endpoints under /api/** don't need this filter.
        reg.addUrlPatterns("/dashboard/*");
        return reg;
    }
}
