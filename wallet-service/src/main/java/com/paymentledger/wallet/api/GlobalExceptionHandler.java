package com.paymentledger.wallet.api;

import com.paymentledger.wallet.domain.InsufficientFundsException;
import com.paymentledger.wallet.idempotency.IdempotencyConflictException;
import com.paymentledger.wallet.security.InvalidCredentialsException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Extends ResponseEntityExceptionHandler so that Spring's own exceptions - unreadable bodies,
 * unsupported methods, and above all validation failures - come out in the same RFC 7807 shape as
 * the domain exceptions below, instead of whatever default each would otherwise produce.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(IdempotencyConflictException.class)
    public ProblemDetail handleIdempotencyConflict(IdempotencyConflictException e) {
        return ProblemDetailSupport.of(HttpStatus.CONFLICT, e.getMessage());
    }

    // The message is the exception's own fixed text, never anything derived from the attempt -
    // see InvalidCredentialsException for why every rejection has to look identical.
    @ExceptionHandler(InvalidCredentialsException.class)
    public ProblemDetail handleInvalidCredentials(InvalidCredentialsException e) {
        return ProblemDetailSupport.of(HttpStatus.UNAUTHORIZED, e.getMessage());
    }

    // 404 whether the resource is missing or simply isn't the caller's - see SPEC.md.
    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail handleResourceNotFound(ResourceNotFoundException e) {
        return ProblemDetailSupport.of(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(InsufficientFundsException.class)
    public ProblemDetail handleInsufficientFunds(InsufficientFundsException e) {
        return ProblemDetailSupport.of(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }

    // Only reached once OptimisticLockRetrier has exhausted its retries - see SPEC.md
    // "Optimistic locking, precisely". 503 tells the client this is transient contention, not a
    // request-shape problem, so retrying the whole request later is the right response.
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ProblemDetail handleExhaustedOptimisticLockRetries(ObjectOptimisticLockingFailureException e) {
        return ProblemDetailSupport.of(HttpStatus.SERVICE_UNAVAILABLE,
                "High contention on this wallet - please retry");
    }

    /**
     * Field errors go in an "errors" member rather than being flattened into the detail string, so
     * a client can point at the offending input instead of parsing prose.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                   HttpHeaders headers,
                                                                   HttpStatusCode status,
                                                                   WebRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(fieldError -> errors.putIfAbsent(
                fieldError.getField(),
                fieldError.getDefaultMessage() == null ? "is invalid" : fieldError.getDefaultMessage()));
        ex.getBindingResult().getGlobalErrors().forEach(globalError -> errors.putIfAbsent(
                globalError.getObjectName(),
                globalError.getDefaultMessage() == null ? "is invalid" : globalError.getDefaultMessage()));

        ProblemDetail problem = ProblemDetailSupport.of(HttpStatus.BAD_REQUEST, "Request validation failed");
        problem.setProperty("errors", errors);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
    }
}
