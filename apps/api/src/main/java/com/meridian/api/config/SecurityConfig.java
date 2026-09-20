package com.meridian.api.config;

import com.meridian.api.auth.AuthCookies;
import com.meridian.api.auth.JwtAuthenticationFilter;
import com.meridian.api.auth.JwtService;
import com.meridian.api.common.CsrfCookieFilter;
import com.meridian.api.common.RateLimitFilter;
import com.meridian.api.users.UserRepository;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * The security posture, assembled in one place.
 *
 * <p>This replaces what was spread across helmet, the cors middleware, {@code csrfProtection},
 * {@code requireAuth} and {@code requireRole} in the Express app. Each block below notes what it
 * corresponds to.
 *
 * <p>The ordering matters and mirrors the old middleware chain: rate limiting runs before
 * authentication (so a flood of bad tokens is cheap to reject), authentication runs before
 * authorization, and CSRF is evaluated by Spring's own filter ahead of both.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * Routes that never require a session: the health probes, the signature-verified webhook, and
     * the auth endpoints themselves. Everything else is authenticated — the same default the old
     * {@code router.use(requireAuth)} calls established per-router.
     */
    private static final String[] PUBLIC_PATHS = {
            "/health", "/health/live", "/health/ready",
            "/webhooks/github",
            "/auth/login", "/auth/logout", "/auth/refresh",
            "/auth/github", "/auth/github/callback"
    };

    private final AppProperties props;

    public SecurityConfig(AppProperties props) {
        this.props = props;
    }

    /**
     * bcrypt at the configured cost, default 12 — unchanged, so hashes written by the Node service
     * verify here and vice versa. The cost is configurable mainly so tests can drop it.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(props.bcryptCost());
    }

    /**
     * The three filters below are built here rather than being {@code @Component}s on purpose: a
     * {@code Filter} bean would also be picked up by Boot's servlet auto-registration and run a
     * second time outside this chain.
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           JwtService jwtService,
                                           UserRepository users,
                                           AuthCookies authCookies) throws Exception {

        JwtAuthenticationFilter jwtFilter = new JwtAuthenticationFilter(jwtService, users, authCookies);
        RateLimitFilter rateLimitFilter = new RateLimitFilter(props);
        CsrfCookieFilter csrfCookieFilter = new CsrfCookieFilter();

        http
                // Strict allow-list CORS with credentials. Never a wildcard — `credentials: true`
                // and `*` are mutually exclusive anyway, and the origin list is explicit.
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))

                // Double-submit CSRF, matching the old middleware exactly: a readable `mrd_csrf`
                // cookie that the browser echoes back in `X-CSRF-Token`. Safe methods are exempt by
                // default; the webhook is exempt because it authenticates by HMAC over the raw body
                // and has no cookie to double-submit.
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        .ignoringRequestMatchers("/webhooks/github"))

                // JWTs carry the session; there is no server-side HTTP session to create.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // Security headers. The old helmet config is reproduced here — this service serves
                // JSON only, so the CSP can be as restrictive as `default-src 'none'`.
                .headers(headers -> headers
                        .contentTypeOptions(Customizer.withDefaults())
                        .frameOptions(HeadersConfigurer.FrameOptionsConfig::deny)
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'none'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"))
                        .referrerPolicy(referrer -> referrer.policy(
                                org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
                                        .ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31536000))
                        .crossOriginResourcePolicy(corp -> corp.policy(
                                org.springframework.security.web.header.writers.CrossOriginResourcePolicyHeaderWriter
                                        .CrossOriginResourcePolicy.SAME_SITE))
                        .permissionsPolicyHeader(pp -> pp.policy("geolocation=(), microphone=(), camera=()")))

                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        // The STOMP handshake authenticates itself in WebSocketConfig's channel
                        // interceptor, which can read the CONNECT frame's token as well as the cookie.
                        .requestMatchers("/live/**").permitAll()
                        .anyRequest().authenticated())

                // Unauthenticated and forbidden responses keep the `{"error": ...}` shape the
                // frontend's api.js already branches on, rather than Spring's default empty body.
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) ->
                                writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "unauthenticated"))
                        .accessDeniedHandler((request, response, deniedException) ->
                                writeError(response, HttpServletResponse.SC_FORBIDDEN, "forbidden")))

                .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                // Runs after Spring's CsrfFilter has put the deferred token in request scope, so
                // resolving it there writes the cookie.
                .addFilterAfter(csrfCookieFilter, org.springframework.security.web.csrf.CsrfFilter.class)

                .formLogin(login -> login.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable());

        return http.build();
    }

    /**
     * The CSRF cookie. {@code withHttpOnlyFalse} is required, not an oversight — the frontend reads
     * {@code document.cookie} to find the token and put it in the request header. That is safe here
     * because the token's only job is to prove the request came from a page that could read a
     * SameSite cookie on this origin.
     */
    @Bean
    public CsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieName(AuthCookies.CSRF);
        repository.setHeaderName("X-CSRF-Token");
        repository.setParameterName("_csrf");
        repository.setCookieCustomizer(cookie -> cookie
                .secure(props.cookieSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(java.time.Duration.ofDays(1)));

        // Wrapped so the token survives Spring's rotate-on-authentication, which fires on every
        // request in a stateless API and would otherwise blank the cookie half the time.
        return new StableCsrfTokenRepository(repository);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.copyOf(props.resolvedAllowedOrigins()));
        config.setAllowCredentials(true);
        config.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Content-Type", "Authorization", "X-CSRF-Token"));
        config.setMaxAge(600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    private static void writeError(HttpServletResponse response, int status, String code) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"error\":\"" + code + "\"}");
    }
}
