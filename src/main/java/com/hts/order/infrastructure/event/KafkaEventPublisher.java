package com.hts.order.infrastructure.event;

import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class KafkaEventPublisher {
    private static final Logger LOG = LoggerFactory.getLogger(KafkaEventPublisher.class);

    public void publish(String eventType, long orderId) {
        System.out.printf("Published event to Kafka: type=%s, orderId=%d%n", eventType, orderId);
    }
}
