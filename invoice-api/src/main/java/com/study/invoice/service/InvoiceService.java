package com.study.invoice.service;

import com.study.invoice.dto.InvoiceResponse;
import com.study.invoice.dto.PaymentProcessedEvent;
import com.study.invoice.model.Invoice;
import com.study.invoice.repository.InvoiceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class InvoiceService {

    private final InvoiceRepository invoiceRepository;

    @Transactional
    public void processEvent(PaymentProcessedEvent event) {
        if (invoiceRepository.existsByRequestId(event.requestId())) {
            log.info("Duplicate event, skipping: requestId={}", event.requestId());
            return;
        }

        if (!"APPROVED".equals(event.status())) {
            log.info("Payment not APPROVED, discarding: requestId={}, status={}", event.requestId(), event.status());
            return;
        }

        Invoice invoice = new Invoice();
        invoice.setRequestId(event.requestId());
        invoice.setPaymentId(event.paymentId());
        invoice.setCustomerId(event.customerId());
        invoice.setAmount(event.amount());
        invoice.setCurrency(event.currency());
        invoice.setStatus("ISSUED");

        try {
            invoiceRepository.save(invoice);
            log.info("Invoice created: paymentId={}, requestId={}", event.paymentId(), event.requestId());
        } catch (DataIntegrityViolationException e) {
            log.warn("Race condition on invoice creation, skipping: requestId={}", event.requestId());
        }
    }

    @Transactional(readOnly = true)
    public List<InvoiceResponse> listAll() {
        return invoiceRepository.findAll().stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public Optional<InvoiceResponse> findById(UUID id) {
        return invoiceRepository.findById(id).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public Optional<InvoiceResponse> findByPaymentId(UUID paymentId) {
        return invoiceRepository.findByPaymentId(paymentId).map(this::toResponse);
    }

    private InvoiceResponse toResponse(Invoice i) {
        return new InvoiceResponse(
                i.getId(),
                i.getRequestId(),
                i.getPaymentId(),
                i.getCustomerId(),
                i.getAmount(),
                i.getCurrency(),
                i.getStatus(),
                i.getCreatedAt()
        );
    }
}
