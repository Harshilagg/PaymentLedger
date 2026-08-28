package com.paymentledger.wallet.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentledger.wallet.api.ProblemDetailSupport;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Applies the token bucket to authenticated writes.
 *
 * Sits after JwtAuthenticationFilter because the bucket is keyed on the authenticated user, which
 * does not exist until that filter has run.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter rateLimiter;
    private final RateLimitProperties properties;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(RateLimiter rateLimiter, RateLimitProperties properties, ObjectMapper objectMapper) {
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * Reads are unlimited, so only mutating methods are considered.
     *
     * /auth/** is excluded for a structural reason rather than a policy one: it is unauthenticated,
     * so there is no user to key a bucket on. Limiting it would need a different key (IP, say) and a
     * different failure policy - see the note in RateLimiter about not mistaking this for
     * credential-stuffing protection.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!properties.enabled()) {
            return true;
        }
        String method = request.getMethod();
        boolean isWrite = "POST".equals(method) || "PUT".equals(method)
                || "PATCH".equals(method) || "DELETE".equals(method);
        return !isWrite || request.getRequestURI().startsWith("/auth/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        String userId = currentUserId();
        if (userId == null) {
            // Unauthenticated write: nothing to key on, and the security chain is about to reject
            // it anyway. Letting it through here keeps the 401 as the answer rather than a 429 that
            // would tell an anonymous caller something about a bucket they do not have.
            filterChain.doFilter(request, response);
            return;
        }

        RateLimitDecision decision = rateLimiter.check(userId);
        writeRateLimitHeaders(response, decision);

        if (decision.allowed()) {
            filterChain.doFilter(request, response);
            return;
        }

        // Seconds, rounded up: Retry-After is defined in whole seconds, and rounding down would
        // invite a client to retry fractionally too early and be rejected again.
        long retryAfterSeconds = Math.max(1, ceilDivide(decision.millisUntilNextToken(), 1000));
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));

        // Written through the same helper as every other filter-chain error, so a 429 body is
        // indistinguishable in shape from a 401 or a 403 (see ProblemDetailSupport).
        ProblemDetailSupport.write(objectMapper, request, response, HttpStatus.TOO_MANY_REQUESTS,
                "Rate limit exceeded. Retry in " + retryAfterSeconds + "s.");
    }

    /** Sent on every limited response, not only rejections, so a client can pace itself in advance. */
    private void writeRateLimitHeaders(HttpServletResponse response, RateLimitDecision decision) {
        response.setHeader("X-RateLimit-Limit", String.valueOf(rateLimiter.capacity()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(Math.max(0, decision.remaining())));
        response.setHeader("X-RateLimit-Reset", String.valueOf(ceilDivide(decision.millisUntilFull(), 1000)));
    }

    private static long ceilDivide(long value, long divisor) {
        return (value + divisor - 1) / divisor;
    }

    /** The principal placed on the context by JwtAuthenticationFilter is the user's UUID. */
    private static String currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        return principal == null ? null : principal.toString();
    }
}
