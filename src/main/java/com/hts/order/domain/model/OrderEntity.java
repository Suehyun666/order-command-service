package com.hts.order.domain.model;

import com.hts.generated.grpc.*;

public record OrderEntity(
        long orderId,
        long accountId,
        String symbol,
        Side side,
        OrderType orderType,
        long quantity,
        long price,
        TimeInForce timeInForce,
        OrderStatus status,
        String reserveId
) {
    public static OrderEntity from(long orderId, long accountId, String symbol, Side side,
                                   OrderType orderType, long quantity, long price, TimeInForce timeInForce,
                                   String reserveId) {
        return new OrderEntity(orderId, accountId, symbol, side, orderType, quantity, price,
                timeInForce, OrderStatus.RECEIVED, reserveId);
    }

    public byte[] serializeForOutbox() {
        return String.format(
                "{\"order_id\":\"%d\",\"account_id\":%d,\"symbol\":\"%s\",\"side\":\"%s\",\"quantity\":%d,\"price\":%d}",
                orderId, accountId, symbol, side.name(), quantity, price
        ).getBytes();
    }
}
