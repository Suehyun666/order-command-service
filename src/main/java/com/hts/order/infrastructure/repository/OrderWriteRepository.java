package com.hts.order.infrastructure.repository;

import com.hts.order.domain.model.OrderEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jooq.DSLContext;

@ApplicationScoped
public class OrderWriteRepository {

    @Inject RedisRepository redisRepository;
    @Inject DSLContext dslContext;

    public void insertOrder(DSLContext tx, OrderEntity order) {
        tx.execute("""
            INSERT INTO orders(order_id, account_id, symbol, side, order_type, quantity, price, time_in_force, status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
                order.orderId(),
                order.accountId(),
                order.symbol(),
                order.side().name(),
                order.orderType().name(),
                order.quantity(),
                order.price(),
                order.timeInForce().name(),
                order.status().name());
    }

    public void insertOutbox(DSLContext tx, OrderEntity order, String eventType) {
        tx.execute("""
           INSERT INTO outbox(event_type, aggregate_id, payload, status)
           VALUES (?, ?, ?, 'PENDING')
        """, eventType, order.orderId(), order.serializeForOutbox());
    }

    public boolean markCancelRequested(DSLContext tx, long orderId, long accountId) {
        int rows = tx.execute("""
           UPDATE orders SET status = 'CANCEL_REQUESTED'
           WHERE order_id = ? AND account_id = ? AND status IN ('RECEIVED', 'ACCEPTED')
        """, orderId, accountId);
        return rows == 1;
    }
}
