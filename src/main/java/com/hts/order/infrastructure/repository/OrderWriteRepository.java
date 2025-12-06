package com.hts.order.infrastructure.repository;

import com.hts.order.domain.model.OrderEntity;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.SqlConnection;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.Arrays;

@ApplicationScoped
public class OrderWriteRepository {

    private static final Logger log = Logger.getLogger(OrderWriteRepository.class);

    public Uni<Void> insertOrderAtomic(SqlConnection conn, OrderEntity order, String eventType) {
        return insertOrder(conn, order)
            .chain(() -> insertHistory(conn, order))
            .chain(() -> insertOutbox(conn, order, eventType));
    }

    private Uni<Void> insertOrder(SqlConnection conn, OrderEntity order) {
        return conn.preparedQuery("""
            INSERT INTO orders(order_id, account_id, symbol, side, order_type, quantity, price,
                              time_in_force, status, reserve_id, filled_quantity)
            VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, 0)
        """)
        .execute(Tuple.wrap(Arrays.asList(
                order.orderId(),
                order.accountId(),
                order.symbol(),
                order.side().name(),
                order.orderType().name(),
                order.quantity(),
                order.price(),
                order.timeInForce().name(),
                order.status().name(),
                order.reserveId()
        )))
        .replaceWithVoid();
    }

    private Uni<Void> insertHistory(SqlConnection conn, OrderEntity order) {
        return conn.preparedQuery("""
            INSERT INTO order_history(order_id, account_id, status, previous_status, quantity, price, filled_quantity)
            VALUES ($1, $2, $3, NULL, $4, $5, 0)
        """)
        .execute(Tuple.of(
            order.orderId(),
            order.accountId(),
            order.status().name(),
            order.quantity(),
            order.price()
        ))
        .replaceWithVoid();
    }

    private Uni<Void> insertOutbox(SqlConnection conn, OrderEntity order, String eventType) {
        return conn.preparedQuery("""
           INSERT INTO outbox(event_type, aggregate_id, payload, status)
           VALUES ($1, $2, $3::jsonb, 'PENDING')
        """)
        .execute(Tuple.of(
            eventType,
            order.orderId(),
            new String(order.serializeForOutbox())
        ))
        .replaceWithVoid();
    }

    public Uni<CancelResult> markCancelRequested(SqlConnection conn, long orderId, long accountId) {
        return conn.preparedQuery("""
           UPDATE orders SET status = 'CANCEL_REQUESTED', updated_at = NOW()
           WHERE order_id = $1 AND account_id = $2 AND status IN ('RECEIVED', 'ACCEPTED')
           RETURNING side, reserve_id
        """)
        .execute(Tuple.of(orderId, accountId))
        .map(rows -> {
            if (!rows.iterator().hasNext()) {
                return null;
            }
            var row = rows.iterator().next();
            return new CancelResult(
                row.getString("side"),
                row.getString("reserve_id")
            );
        })
        .call(result -> {
            if (result != null) {
                return insertCancelHistory(conn, orderId);
            }
            return Uni.createFrom().voidItem();
        });
    }

    private Uni<Void> insertCancelHistory(SqlConnection conn, long orderId) {
        return conn.preparedQuery("""
            INSERT INTO order_history(order_id, account_id, status, previous_status, quantity, price, filled_quantity, reason)
            SELECT order_id, account_id, 'CANCEL_REQUESTED', 'RECEIVED', quantity, price, filled_quantity, 'User requested'
            FROM orders WHERE order_id = $1
        """)
        .execute(Tuple.of(orderId))
        .replaceWithVoid();
    }

    public record CancelResult(String side, String reserveId) {}
}
