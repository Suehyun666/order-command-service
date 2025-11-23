package com.hts.order.infrastructure.repository;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jooq.DSLContext;
import org.jooq.Record;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@ApplicationScoped
public class OrderReadRepository {
    @Inject DSLContext dsl;
    /**
     * Get order amount (price * quantity) for unreserve
     * @return order amount or null if not found
     */
    public Long getOrderAmount(DSLContext tx, long orderId, long accountId) {
        org.jooq.Record record = tx.fetchOne("""
            SELECT price * quantity as amount
            FROM orders
            WHERE order_id = ? AND account_id = ?
        """, orderId, accountId);

        if (record == null) {
            return null;
        }
        return record.get("amount", Long.class);
    }

    /**
     * Get symbol by orderId (fallback용 - DispatchHandler에서 사용)
     *
     * ⚠️ 트랜잭션 외부 조회 - Fallback 경로에서만 사용 (느림 ~10ms)
     *
     * @return symbol or null if not found
     */
    public String getSymbolByOrderId(long orderId) {
        Record record = dsl.fetchOne("""
            SELECT symbol
            FROM orders
            WHERE order_id = ?
        """, orderId);

        if (record == null) {
            return null;
        }
        return record.get("symbol", String.class);
    }

    /**
     * Find order IDs older than cutoff date (cleanup용)
     *
     * OrderIndexCache 정리 스케줄러에서 사용
     * 오래된 주문의 Redis 캐시 삭제 대상 조회
     *
     * @param cutoff 기준 일시
     * @return old order IDs
     */
    public List<Long> findOrderIdsOlderThan(LocalDateTime cutoff) {
        return dsl.fetch("""
            SELECT order_id
            FROM orders
            WHERE created_at < ?
            ORDER BY created_at ASC
            LIMIT 1000
        """, cutoff)
                .stream()
                .map(r -> r.get("order_id", Long.class))
                .collect(Collectors.toList());
    }
}
