package com.paymentledger.wallet.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IdempotencyServiceTest {

    private final IdempotencyRecordRepository repository = mock(IdempotencyRecordRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final IdempotencyService service = new IdempotencyService(repository, objectMapper, 24);

    @Test
    void newKeyRunsTheOperationExactlyOnceAndCachesTheResponse() {
        when(repository.findById("key-1")).thenReturn(Optional.empty());
        AtomicInteger executions = new AtomicInteger();

        String result = service.execute("key-1", "request-body", String.class, () -> {
            executions.incrementAndGet();
            return "the-response";
        });

        assertThat(result).isEqualTo("the-response");
        assertThat(executions.get()).isEqualTo(1);
    }

    @Test
    void sameKeyAndSameRequestReturnsCachedResponseWithoutRerunning() throws Exception {
        String requestBody = "request-body";
        IdempotencyRecord cached = new IdempotencyRecord(
                "key-2", sha256Hex(requestBody), objectMapper.writeValueAsString("cached-response"), 24);
        when(repository.findById("key-2")).thenReturn(Optional.of(cached));
        AtomicInteger executions = new AtomicInteger();

        String result = service.execute("key-2", requestBody, String.class, () -> {
            executions.incrementAndGet();
            return "should-not-run";
        });

        assertThat(result).isEqualTo("cached-response");
        assertThat(executions.get()).isZero();
    }

    @Test
    void sameKeyDifferentRequestIsAConflict() throws Exception {
        IdempotencyRecord cached = new IdempotencyRecord(
                "key-3", sha256Hex("original-request"), objectMapper.writeValueAsString("cached-response"), 24);
        when(repository.findById("key-3")).thenReturn(Optional.of(cached));

        assertThatThrownBy(() -> service.execute("key-3", "different-request", String.class, () -> "x"))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    private String sha256Hex(String input) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }
}
