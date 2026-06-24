package com.paymentengine.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentengine.model.dto.PaymentDto.PaymentResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Idempotency Guard using Redis.
 *
 * Problem it solves:
 * A merchant's server retries a payment request due to network timeout.
 * Without idempotency, we'd charge the customer twice.
 *
 * Solution:
 * Merchant sends a unique idempotency_key with every request.
 * We store the result in Redis with the key for 24 hours.
 * If the same key arrives again, we return the cached result — no re-processing.
 *
 * Redis key format: idempotency:{idempotency_key}
 * TTL: 24 hours (configurable)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IdempotencyService {

    private static final String KEY_PREFIX = "idempotency:";

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${app.redis.idempotency.ttl-minutes:1440}")
    private long ttlMinutes;

    /**
     * Check if this idempotency key has already been processed.
     * Returns the cached response if found, empty if this is a new request.
     */
    public Optional<PaymentResponse> getIfPresent(String idempotencyKey) {
        String redisKey = buildKey(idempotencyKey);
        try {
            String cachedJson = redisTemplate.opsForValue().get(redisKey);
            if (cachedJson != null) {
                log.info("Idempotent response served for key: {}", idempotencyKey);
                PaymentResponse response = objectMapper.readValue(cachedJson, PaymentResponse.class);
                response.setIdempotentResponse(true);
                return Optional.of(response);
            }
        } catch (Exception e) {
            // If Redis is down, we FAIL OPEN — let the request through.
            // Worst case: a duplicate charge, which is recoverable via reconciliation.
            // Better than blocking all payments because Redis is unavailable.
            log.error("Redis read failed for idempotency key: {}. Failing open.", idempotencyKey, e);
        }
        return Optional.empty();
    }

    /**
     * Store the payment response against the idempotency key.
     * Called after successful payment processing.
     */
    public void store(String idempotencyKey, PaymentResponse response) {
        String redisKey = buildKey(idempotencyKey);
        try {
            String json = objectMapper.writeValueAsString(response);
            redisTemplate.opsForValue().set(redisKey, json, ttlMinutes, TimeUnit.MINUTES);
            log.debug("Stored idempotency key: {} with TTL: {} minutes", idempotencyKey, ttlMinutes);
        } catch (Exception e) {
            // Non-fatal: payment succeeded, we just couldn't cache the result.
            // The DB record exists, so reconciliation can still detect duplicates.
            log.error("Failed to store idempotency key: {} in Redis", idempotencyKey, e);
        }
    }

    /**
     * Explicitly invalidate a key — used when a payment fails and
     * we want to allow the merchant to retry with the same key.
     */
    public void invalidate(String idempotencyKey) {
        redisTemplate.delete(buildKey(idempotencyKey));
        log.info("Invalidated idempotency key: {}", idempotencyKey);
    }

    private String buildKey(String idempotencyKey) {
        return KEY_PREFIX + idempotencyKey;
    }
}
