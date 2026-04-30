package com.study.payment.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentProcessedEvent(
        String requestId,
        UUID paymentId,
        String status,
        BigDecimal amount,
        String currency,
        UUID customerId,
        Instant createdAt
) {}
