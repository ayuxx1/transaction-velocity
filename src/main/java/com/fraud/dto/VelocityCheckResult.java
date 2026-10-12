package com.fraud.dto;

public record VelocityCheckResult(
        boolean limitExceeded,
        long currentCount,
        long windowSeconds,
        long threshold,
        boolean duplicate
) {
}
