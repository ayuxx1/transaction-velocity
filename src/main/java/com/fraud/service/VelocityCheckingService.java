package com.fraud.service;

import com.fraud.dto.TransactionRequest;
import com.fraud.dto.VelocityCheckResult;
import com.fraud.config.VelocityProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.time.Clock;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Service
public class VelocityCheckingService {

    private static final RedisScript<Long> CHECK_VELOCITY_SCRIPT = new DefaultRedisScript<>("""
            local previous = redis.call('GET', KEYS[2])
            if previous then
                local separator = string.find(previous, ':', 1, true)
                if string.sub(previous, 1, separator - 1) ~= ARGV[4] then
                    return 0
                end
                return -tonumber(string.sub(previous, separator + 1))
            end
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[1])
            redis.call('ZADD', KEYS[1], ARGV[2], ARGV[3])
            local count = redis.call('ZCARD', KEYS[1])
            redis.call('PEXPIRE', KEYS[1], ARGV[5])
            redis.call('SET', KEYS[2], ARGV[4] .. ':' .. tostring(count), 'PX', ARGV[5])
            return count
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final VelocityProperties velocityProperties;
    private final Clock clock;

    public VelocityCheckingService(
            StringRedisTemplate redisTemplate,
            VelocityProperties velocityProperties,
            Clock clock) {
        this.redisTemplate = redisTemplate;
        this.velocityProperties = velocityProperties;
        this.clock = clock;
    }

    /**
     * Checks whether a transaction exceeds the account's rolling-window threshold.
     *
     * @param request      incoming transaction
     */
    public VelocityCheckResult checkTransactionVelocity(TransactionRequest request) {

        Objects.requireNonNull(request, "request must not be null");

        long windowSeconds = velocityProperties.windowSeconds();
        long maxThreshold = velocityProperties.maxTransactions();
        long windowMillis = Math.multiplyExact(windowSeconds, 1_000L);
        long now = clock.millis();
        long windowStart = Math.subtractExact(now, windowMillis);
        long ttlMillis = Math.multiplyExact(windowMillis, 2);
        String accountKey = "{" + request.accountId() + "}";
        String redisKey = "velocity:txn:" + accountKey;
        String idempotencyKey = "velocity:idempotency:" + accountKey + ":" + request.transactionId();
        String fingerprint = fingerprint(request);

        Long currentCount = redisTemplate.execute(
                CHECK_VELOCITY_SCRIPT,
                List.of(redisKey, idempotencyKey),
                Long.toString(windowStart),
                Long.toString(now),
                request.transactionId(),
                fingerprint,
                Long.toString(ttlMillis));
        long scriptResult = Objects.requireNonNull(currentCount, "Redis velocity script returned no count");
        if (scriptResult == 0) {
            throw new DuplicateTransactionConflictException();
        }
        boolean duplicate = scriptResult < 0;
        long count = Math.abs(scriptResult);

        return new VelocityCheckResult(count > maxThreshold, count, windowSeconds, maxThreshold, duplicate);
    }

    private static String fingerprint(TransactionRequest request) {
        String canonicalRequest = request.accountId() + "\n"
                + request.transactionId() + "\n"
                + request.amount().stripTrailingZeros().toPlainString() + "\n"
                + Objects.toString(request.ipAddress(), "");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalRequest.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
