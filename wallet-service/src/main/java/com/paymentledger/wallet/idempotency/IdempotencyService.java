package com.paymentledger.wallet.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Wraps a mutating operation with the client-facing idempotency contract from SPEC.md: same key
 * + same request -> cached response, no re-execution; same key + different request -> conflict;
 * new key -> run the operation and cache its response, atomically with whatever the operation
 * itself writes (this method's transaction boundary covers both).
 */
@Service
public class IdempotencyService {

    private final IdempotencyRecordRepository repository;
    private final ObjectMapper objectMapper;
    private final long ttlHours;

    public IdempotencyService(IdempotencyRecordRepository repository, ObjectMapper objectMapper,
                               @Value("${app.idempotency.ttl-hours}") long ttlHours) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.ttlHours = ttlHours;
    }

    @Transactional
    public <T> T execute(String key, String canonicalRequest, Class<T> responseType, Supplier<T> operation) {
        String hash = sha256Hex(canonicalRequest);

        Optional<IdempotencyRecord> existing = repository.findById(key);
        if (existing.isPresent()) {
            IdempotencyRecord record = existing.get();
            if (!record.matchesRequestHash(hash)) {
                throw new IdempotencyConflictException(key);
            }
            return readJson(record.getResponseBody(), responseType);
        }

        T response = operation.get();
        repository.save(new IdempotencyRecord(key, hash, writeJson(response), ttlHours));
        return response;
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize idempotency response for " + value, e);
        }
    }

    private <T> T readJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize cached idempotency response", e);
        }
    }
}
