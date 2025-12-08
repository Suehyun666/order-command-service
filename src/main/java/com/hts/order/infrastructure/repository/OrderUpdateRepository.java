package com.hts.order.infrastructure.repository;

import com.hts.generated.events.order.OrderFillEvent;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.SqlConnection;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class OrderUpdateRepository {

    private static final Logger LOG = Logger.getLogger(OrderUpdateRepository.class);

    @Inject PgPool client;

    /**
     * 주문을 FILLED 상태로 업데이트하고 order_history에 기록
     */
    public Uni<Boolean> updateOrderToFilled(OrderFillEvent event) {
        return client.withTransaction(conn -> {
            // 1. orders 테이블 업데이트
            return updateOrderStatus(conn, event)
                .chain(updated -> {
                    if (!updated) {
                        LOG.warnf("Order not found or not in valid status: %s", event.getClientOrderId());
                        return Uni.createFrom().item(false);
                    }

                    // 2. order_history에 기록
                    return insertOrderHistory(conn, event)
                        .replaceWith(true);
                });
        });
    }

    private Uni<Boolean> updateOrderStatus(SqlConnection conn, OrderFillEvent event) {
        // 체결 수량 계산
        long totalFilledQty = event.getFillsList().stream()
            .mapToLong(fill -> fill.getQuantity())
            .sum();

        return conn.preparedQuery("""
            UPDATE orders
            SET status = 'FILLED', filled_quantity = $1, updated_at = NOW()
            WHERE order_id = $2 AND status IN ('RECEIVED', 'ACCEPTED', 'SENT')
            """)
            .execute(Tuple.of(totalFilledQty, parseOrderId(event.getClientOrderId())))
            .map(rows -> rows.rowCount() > 0);
    }

    private Uni<Void> insertOrderHistory(SqlConnection conn, OrderFillEvent event) {
        long totalFilledQty = event.getFillsList().stream()
            .mapToLong(fill -> fill.getQuantity())
            .sum();

        return conn.preparedQuery("""
            INSERT INTO order_history(order_id, account_id, status, previous_status, quantity, price, filled_quantity, reason)
            SELECT order_id, account_id, 'FILLED', status, quantity, price, $1, 'Order filled by exchange'
            FROM orders
            WHERE order_id = $2
            """)
            .execute(Tuple.of(totalFilledQty, parseOrderId(event.getClientOrderId())))
            .replaceWithVoid();
    }

    private long parseOrderId(String clientOrderId) {
        try {
            return Long.parseLong(clientOrderId);
        } catch (NumberFormatException e) {
            LOG.errorf("Invalid client_order_id format: %s", clientOrderId);
            return 0L;
        }
    }
}
