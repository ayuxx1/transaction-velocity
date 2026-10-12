package com.fraud.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record TransactionRequest(
        @NotBlank
        @Size(max = 64)
        @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]*")
        String transactionId,
        @NotBlank
        @Size(max = 64)
        @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]*")
        String accountId,
        @NotNull
        @Positive
        @Digits(integer = 12, fraction = 2)
        BigDecimal amount,
        @Size(max = 45)
        String ipAddress
) {
}
