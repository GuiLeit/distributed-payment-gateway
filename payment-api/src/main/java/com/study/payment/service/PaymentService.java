package com.study.payment.service;

import com.study.payment.config.RabbitMQConfig;
import com.study.payment.dto.PaymentProcessedEvent;
import com.study.payment.dto.PaymentRequest;
import com.study.payment.dto.PaymentResponse;
import com.study.payment.model.Payment;
import com.study.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final RabbitTemplate rabbitTemplate;

    @Transactional
    public PaymentResponse processPayment(String requestId, PaymentRequest request) {
        Optional<Payment> existing = paymentRepository.findByRequestId(requestId);
        if (existing.isPresent()) {
            log.info("Duplicate requestId, returning existing payment: requestId={}", requestId);
            return toResponse(existing.get());
        }

        Payment payment = new Payment();
        payment.setRequestId(requestId);
        payment.setCustomerId(request.customerId());
        payment.setAmount(request.amount());
        payment.setCurrency(request.currency());
        payment.setMethod(request.method());
        payment.setStatus("APPROVED");

        try {
            payment = paymentRepository.save(payment);
        } catch (DataIntegrityViolationException e) {
            log.warn("Race condition on requestId={}, fetching existing record", requestId);
            return toResponse(paymentRepository.findByRequestId(requestId).orElseThrow());
        }

        PaymentProcessedEvent event = new PaymentProcessedEvent(
                requestId,
                payment.getId(),
                payment.getStatus(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getCustomerId(),
                payment.getCreatedAt()
        );
        rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE, RabbitMQConfig.ROUTING_KEY, event);
        log.info("Payment created and event published: paymentId={}", payment.getId());

        return toResponse(payment);
    }

    @Transactional(readOnly = true)
    public List<PaymentResponse> listAll() {
        return paymentRepository.findAll().stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public Optional<PaymentResponse> findById(UUID id) {
        return paymentRepository.findById(id).map(this::toResponse);
    }

    private PaymentResponse toResponse(Payment p) {
        return new PaymentResponse(
                p.getRequestId(),
                p.getId(),
                p.getStatus(),
                p.getAmount(),
                p.getCurrency(),
                p.getMethod(),
                p.getCustomerId(),
                p.getCreatedAt()
        );
    }
}
