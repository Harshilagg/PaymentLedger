package com.paymentledger.wallet.api;

import com.paymentledger.wallet.support.SharedPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The regression guard for the bug where every non-2xx response in this service arrived as a
 * bodyless 403 - including for routes matching no controller at all. The cause was the /error
 * forward re-entering the security filter chain as an unauthenticated dispatch (see SPEC.md
 * "Error handling").
 *
 * This runs against a real embedded Tomcat rather than MockMvc on purpose: MockMvc does not
 * perform the servlet container's ERROR dispatch, so it would happily pass whether or not the
 * fix is present, which is the one thing this test exists to detect.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration",
                // This IT asserts HTTP error shapes and never touches the outbox. Left at its
                // 500ms default, the relay would poll throughout the run for no reason, and go on
                // polling from this cached context long after the class finishes.
                "app.outbox.relay.poll-interval-ms=3600000",
                "app.idempotency.cleanup-interval-ms=3600000",
        })
class ErrorResponseIT {

    /** OutboxRelay is a plain @Component and needs some KafkaTemplate for the context to start. */
    @TestConfiguration
    static class NoKafkaConfig {
        @Bean
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafkaTemplate() {
            KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
            when(template.send(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(null));
            return template;
        }
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        SharedPostgres.registerProperties(registry);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    /**
     * TestRestTemplate defaults to HttpURLConnection, which on a 401 tries to re-authenticate and
     * resend the request - and throws "cannot retry due to server authentication, in streaming
     * mode" when the body was streamed rather than buffered. That makes every POST that correctly
     * answers 401 fail in the client before the test can assert anything, while GETs returning 401
     * and POSTs returning 400 pass. Nothing to do with the server: it is this test's HTTP client.
     *
     * java.net.http.HttpClient, via JdkClientHttpRequestFactory, has no such behaviour and needs
     * no extra dependency.
     */
    @BeforeEach
    void useAClientThatDoesNotRetryOn401() {
        restTemplate.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
    }

    private String registerAndGetAccessToken() {
        ResponseEntity<Map<String, Object>> response = exchange(
                HttpMethod.POST, "/auth/register", null,
                Map.of("email", UUID.randomUUID() + "@example.com", "password", "password-123"));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody()).isNotNull();
        return (String) response.getBody().get("accessToken");
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map<String, Object>> exchange(HttpMethod method, String path,
                                                          String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return restTemplate.exchange(path, method, new HttpEntity<>(body, headers),
                (Class<Map<String, Object>>) (Class<?>) Map.class);
    }

    private ResponseEntity<Map<String, Object>> get(String path, String token) {
        return exchange(HttpMethod.GET, path, token, null);
    }

    @Test
    void requestWithNoTokenGets401WithAProblemDetailBody() {
        ResponseEntity<Map<String, Object>> response = get("/accounts", null);

        // Not 403, and not empty: both were true before the filter chain was fixed.
        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody()).isNotNull()
                .containsEntry("status", 401)
                .containsEntry("title", "Unauthorized");
    }

    @Test
    void requestWithAGarbageTokenGets401WithAProblemDetailBody() {
        ResponseEntity<Map<String, Object>> response = get("/accounts", "not-a-jwt");

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody()).isNotNull().containsEntry("status", 401);
    }

    @Test
    void routeThatMatchesNoControllerGets404WithABodyRatherThanABare403() {
        ResponseEntity<Map<String, Object>> response =
                get("/this-route-does-not-exist-at-all", registerAndGetAccessToken());

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).isNotNull().containsEntry("status", 404);
    }

    @Test
    void malformedPathVariableGets400RatherThanABare403() {
        ResponseEntity<Map<String, Object>> response =
                get("/wallets/not-a-uuid", registerAndGetAccessToken());

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isNotNull().containsEntry("status", 400);
    }

    /**
     * The whole point of the 404-for-both rule: if these two responses differed in any way beyond
     * the id the caller already supplied, that difference would be an id-enumeration oracle.
     */
    @Test
    void anotherUsersWalletIsIndistinguishableFromOneThatDoesNotExist() {
        String alice = registerAndGetAccessToken();
        String bob = registerAndGetAccessToken();

        String accountId = (String) exchange(HttpMethod.POST, "/accounts", alice, null)
                .getBody().get("id");
        String aliceWalletId = (String) exchange(HttpMethod.POST, "/accounts/" + accountId + "/wallets",
                alice, Map.of("currency", "USD")).getBody().get("id");
        UUID imaginaryWalletId = UUID.randomUUID();

        ResponseEntity<Map<String, Object>> realButNotBobs = get("/wallets/" + aliceWalletId, bob);
        ResponseEntity<Map<String, Object>> neverExisted = get("/wallets/" + imaginaryWalletId, bob);

        assertThat(realButNotBobs.getStatusCode().value()).isEqualTo(404);
        assertThat(neverExisted.getStatusCode().value()).isEqualTo(404);
        assertThat(realButNotBobs.getHeaders().getContentType())
                .isEqualTo(neverExisted.getHeaders().getContentType());

        // Identical once the caller-supplied id is normalised away - nothing else varies.
        assertThat(withIdRemoved(realButNotBobs.getBody(), aliceWalletId))
                .isEqualTo(withIdRemoved(neverExisted.getBody(), imaginaryWalletId.toString()));
    }

    private static String withIdRemoved(Map<String, Object> body, String id) {
        return body.toString().replace(id, "{id}");
    }

    @Test
    void validationFailureGets400WithAFieldLevelErrorsMap() {
        String token = registerAndGetAccessToken();
        String accountId = (String) exchange(HttpMethod.POST, "/accounts", token, null)
                .getBody().get("id");

        ResponseEntity<Map<String, Object>> response = exchange(
                HttpMethod.POST, "/accounts/" + accountId + "/wallets", token,
                Map.of("currency", "usdollar"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isNotNull().containsEntry("status", 400);

        @SuppressWarnings("unchecked")
        Map<String, String> errors = (Map<String, String>) response.getBody().get("errors");
        assertThat(errors).containsKey("currency");
    }

    @Test
    void loginWithAWrongPasswordGets401WithNothingAttemptSpecific() {
        ResponseEntity<Map<String, Object>> response = exchange(
                HttpMethod.POST, "/auth/login", null,
                Map.of("email", "nobody-" + UUID.randomUUID() + "@example.com", "password", "wrong"));

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody()).isNotNull().containsEntry("detail", "Invalid credentials");
    }

    @Test
    void refreshTokenReplayedAfterRotationIsRejected() {
        Map<String, Object> pair = exchange(HttpMethod.POST, "/auth/register", null,
                Map.of("email", UUID.randomUUID() + "@example.com", "password", "password-123")).getBody();
        String firstRefreshToken = (String) pair.get("refreshToken");

        ResponseEntity<Map<String, Object>> rotated = exchange(HttpMethod.POST, "/auth/refresh", null,
                Map.of("refreshToken", firstRefreshToken));
        assertThat(rotated.getStatusCode().value()).isEqualTo(200);
        String secondRefreshToken = (String) rotated.getBody().get("refreshToken");

        ResponseEntity<Map<String, Object>> replay = exchange(HttpMethod.POST, "/auth/refresh", null,
                Map.of("refreshToken", firstRefreshToken));
        assertThat(replay.getStatusCode().value()).isEqualTo(401);

        // Reuse detection cuts off the whole family, not just the replayed token.
        ResponseEntity<Map<String, Object>> afterReuse = exchange(HttpMethod.POST, "/auth/refresh", null,
                Map.of("refreshToken", secondRefreshToken));
        assertThat(afterReuse.getStatusCode().value()).isEqualTo(401);
    }
}
