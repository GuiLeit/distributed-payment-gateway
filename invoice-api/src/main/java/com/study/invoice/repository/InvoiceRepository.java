package com.study.invoice.repository;

import com.study.invoice.model.Invoice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {
    boolean existsByRequestId(String requestId);
    Optional<Invoice> findByRequestId(String requestId);
    Optional<Invoice> findByPaymentId(UUID paymentId);
}
