package com.study.payment.controller;

import com.study.payment.dto.PaymentRequest;
import com.study.payment.dto.PaymentResponse;
import com.study.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping
    public ResponseEntity<PaymentResponse> create(
            @RequestHeader("X-Request-ID") String requestId,
            @RequestBody PaymentRequest request) {
        return ResponseEntity.ok(paymentService.processPayment(requestId, request));
    }

    @GetMapping
    public List<PaymentResponse> list() {
        return paymentService.listAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<PaymentResponse> getById(@PathVariable UUID id) {
        return paymentService.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
