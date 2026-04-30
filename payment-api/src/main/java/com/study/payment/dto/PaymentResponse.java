package com.study.payment.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(
        String requestId,
        UUID paymentId,
        String status,
        BigDecimal amount,
        String currency,
        String method,
        UUID customerId,
        Instant createdAt
) {}
