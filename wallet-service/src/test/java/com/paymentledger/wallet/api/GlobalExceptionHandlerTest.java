package com.paymentledger.wallet.api;

import com.paymentledger.wallet.domain.InsufficientFundsException;
import com.paymentledger.wallet.idempotency.IdempotencyConflictException;
import com.paymentledger.wallet.security.InvalidCredentialsException;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    /** Stands in for a request DTO so BeanPropertyBindingResult has something real to bind against. */
    private record Payload(String amount, String currency) {
    }

    @Test
    void idempotencyConflictBecomes409() {
        ProblemDetail problem = handler.handleIdempotencyConflict(
                new IdempotencyConflictException("deposit-42"));

        assertThat(problem.getStatus()).isEqualTo(409);
        assertThat(problem.getTitle()).isEqualTo(HttpStatus.CONFLICT.getReasonPhrase());
        assertThat(problem.getDetail())
                .isEqualTo("Idempotency-Key deposit-42 was already used with a different request body");
    }

    @Test
    void insufficientFundsBecomes422() {
        UUID walletId = UUID.randomUUID();

        ProblemDetail problem = handler.handleInsufficientFunds(
                new InsufficientFundsException(walletId, 5_000, 1_200));

        assertThat(problem.getStatus()).isEqualTo(422);
        assertThat(problem.getDetail())
                .isEqualTo("Wallet " + walletId + " has 1200 minor units available, requested 5000");
    }

    @Test
    void resourceNotFoundBecomes404() {
        ProblemDetail problem = handler.handleResourceNotFound(
                new ResourceNotFoundException("Wallet abc not found"));

        assertThat(problem.getStatus()).isEqualTo(404);
        assertThat(problem.getDetail()).isEqualTo("Wallet abc not found");
    }

    @Test
    void invalidCredentialsBecomes401WithNothingAttemptSpecific() {
        ProblemDetail problem = handler.handleInvalidCredentials(new InvalidCredentialsException());

        assertThat(problem.getStatus()).isEqualTo(401);
        assertThat(problem.getDetail()).isEqualTo("Invalid credentials");
    }

    @Test
    void exhaustedOptimisticLockRetriesBecomes503() {
        ProblemDetail problem = handler.handleExhaustedOptimisticLockRetries(
                new ObjectOptimisticLockingFailureException("Wallet", "id"));

        assertThat(problem.getStatus()).isEqualTo(503);
        assertThat(problem.getDetail()).isEqualTo("High contention on this wallet - please retry");
    }

    @Test
    void validationFailureBecomes400WithAFieldLevelErrorsMap() throws Exception {
        BindingResult bindingResult = new BeanPropertyBindingResult(new Payload(null, null), "payload");
        bindingResult.rejectValue("amount", "DecimalMin", "must be greater than 0.00");
        bindingResult.rejectValue("currency", "Pattern", "must match \"^[A-Z]{3}$\"");

        ResponseEntity<Object> response = handler.handleMethodArgumentNotValid(
                new MethodArgumentNotValidException(methodParameter(), bindingResult),
                new HttpHeaders(), HttpStatus.BAD_REQUEST, null);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        ProblemDetail problem = (ProblemDetail) response.getBody();
        assertThat(problem).isNotNull();
        assertThat(problem.getStatus()).isEqualTo(400);
        assertThat(problem.getDetail()).isEqualTo("Request validation failed");

        assertThat(problem.getProperties()).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, String> errors = (Map<String, String>) problem.getProperties().get("errors");
        assertThat(errors).containsExactly(
                Map.entry("amount", "must be greater than 0.00"),
                Map.entry("currency", "must match \"^[A-Z]{3}$\""));
    }

    @Test
    void validationFailureWithNoMessageStillNamesTheField() throws Exception {
        BindingResult bindingResult = new BeanPropertyBindingResult(new Payload(null, null), "payload");
        bindingResult.rejectValue("amount", "NotNull");

        ResponseEntity<Object> response = handler.handleMethodArgumentNotValid(
                new MethodArgumentNotValidException(methodParameter(), bindingResult),
                new HttpHeaders(), HttpStatus.BAD_REQUEST, null);

        ProblemDetail problem = (ProblemDetail) response.getBody();
        assertThat(problem).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, String> errors = (Map<String, String>) problem.getProperties().get("errors");
        assertThat(errors).containsKey("amount");
    }

    /** MethodArgumentNotValidException needs a parameter to describe; any real one will do. */
    private static MethodParameter methodParameter() throws NoSuchMethodException {
        return new MethodParameter(
                GlobalExceptionHandlerTest.class.getDeclaredMethod("sampleEndpoint", Payload.class), 0);
    }

    @SuppressWarnings("unused")
    private void sampleEndpoint(Payload payload) {
    }
}
