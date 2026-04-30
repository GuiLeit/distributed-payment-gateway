package com.study.invoice.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record InvoiceResponse(
        UUID id,
        String requestId,
        UUID paymentId,
        UUID customerId,
        BigDecimal amount,
        String currency,
        String status,
        Instant createdAt
) {}
