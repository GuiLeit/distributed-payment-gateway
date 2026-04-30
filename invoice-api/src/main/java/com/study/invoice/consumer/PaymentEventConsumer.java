package com.study.invoice.consumer;

import com.rabbitmq.client.Channel;
import com.study.invoice.dto.PaymentProcessedEvent;
import com.study.invoice.service.InvoiceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventConsumer {

    private final InvoiceService invoiceService;

    @Value("${app.consumer.max-retries:3}")
    private int maxRetries;

    private final ConcurrentHashMap<String, AtomicInteger> retryCounts = new ConcurrentHashMap<>();

    @RabbitListener(queues = "payment.processed.queue")
    public void consume(@Payload PaymentProcessedEvent event,
                        Channel channel,
                        @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        MDC.put("requestId", event.requestId());
        try {
            invoiceService.processEvent(event);
            retryCounts.remove(event.requestId());
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("Failed to process event: paymentId={}", event.paymentId(), e);
            int attempts = retryCounts
                    .computeIfAbsent(event.requestId(), k -> new AtomicInteger(0))
                    .incrementAndGet();
            if (attempts >= maxRetries) {
                retryCounts.remove(event.requestId());
                log.warn("Max retries reached, dead-lettering: requestId={}", event.requestId());
                channel.basicNack(deliveryTag, false, false);
            } else {
                channel.basicNack(deliveryTag, false, true);
            }
        } finally {
            MDC.remove("requestId");
        }
    }
}
