package com.paymentledger.wallet.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;

import java.io.IOException;
import java.net.URI;

/**
 * Errors leave this application by two different routes: most travel through
 * {@link GlobalExceptionHandler}, but authentication and authorization failures happen inside the
 * filter chain, before any controller is reached, and have to write themselves. This exists so
 * both routes emit the identical RFC 7807 shape - a client should not be able to tell which layer
 * rejected it.
 */
public final class ProblemDetailSupport {

    private ProblemDetailSupport() {
    }

    public static ProblemDetail of(HttpStatus status, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        return problem;
    }

    /**
     * Writes the body directly rather than calling {@code response.sendError}, which would forward
     * to /error and start a second filter-chain dispatch - the exact path that used to replace
     * every error in this application with a bodyless 403.
     */
    public static void write(ObjectMapper objectMapper, HttpServletRequest request,
                              HttpServletResponse response, HttpStatus status, String detail)
            throws IOException {
        ProblemDetail problem = of(status, detail);
        problem.setInstance(URI.create(request.getRequestURI()));

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
