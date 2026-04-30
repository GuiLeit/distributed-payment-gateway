package com.study.payment.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record PaymentRequest(
        UUID customerId,
        BigDecimal amount,
        String currency,
        String method
) {}
