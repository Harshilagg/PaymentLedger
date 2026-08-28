package com.paymentledger.wallet.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentledger.wallet.api.ProblemDetailSupport;
import com.paymentledger.wallet.ratelimit.RateLimitFilter;
import com.paymentledger.wallet.ratelimit.RateLimitProperties;
import com.paymentledger.wallet.ratelimit.RateLimiter;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    private static final String[] PUBLIC_PATHS = {
            "/auth/register",
            "/auth/login",
            "/auth/refresh",
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/v3/api-docs/**",
            "/actuator/health"
    };

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Without this, an unauthenticated request falls through to Spring Security's default
     * Http403ForbiddenEntryPoint, which answers 403 with no body - wrong status, and nothing for a
     * client to act on. 401 is what "you have not identified yourself" means.
     */
    @Bean
    public AuthenticationEntryPoint authenticationEntryPoint(ObjectMapper objectMapper) {
        return (request, response, authException) -> ProblemDetailSupport.write(
                objectMapper, request, response, HttpStatus.UNAUTHORIZED,
                "Authentication is required to access this resource");
    }

    /**
     * Ownership checks deliberately throw ResourceNotFoundException rather than reaching this, so
     * in practice this covers only authorization failures raised by the filter chain itself. It
     * exists so that if one ever does occur it carries the same body shape as everything else.
     */
    @Bean
    public AccessDeniedHandler accessDeniedHandler(ObjectMapper objectMapper) {
        return (request, response, accessDeniedException) -> ProblemDetailSupport.write(
                objectMapper, request, response, HttpStatus.FORBIDDEN, "Access is denied");
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                    JwtService jwtService,
                                                    AuthenticationEntryPoint authenticationEntryPoint,
                                                    AccessDeniedHandler accessDeniedHandler,
                                                    RateLimiter rateLimiter,
                                                    RateLimitProperties rateLimitProperties,
                                                    ObjectMapper objectMapper) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Boot renders errors by forwarding to /error, which re-enters this filter
                        // chain as a fresh ERROR dispatch carrying no SecurityContext. Without this
                        // line that forward is treated as an unauthenticated request to a protected
                        // path, and its rejection overwrites the real response - which is why every
                        // 400, 403 and 404 this service produced used to arrive as a bodyless 403.
                        // Not a hole: an ERROR dispatch is container-internal and cannot be
                        // requested from outside.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterBefore(new JwtAuthenticationFilter(jwtService),
                        UsernamePasswordAuthenticationFilter.class)
                // After the JWT filter, because the bucket is keyed on the authenticated user and
                // there is no principal on the context until that filter has run.
                .addFilterAfter(new RateLimitFilter(rateLimiter, rateLimitProperties, objectMapper),
                        JwtAuthenticationFilter.class);

        return http.build();
    }
}
