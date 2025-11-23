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
        OrderStatus status
) {
    public static OrderEntity from() {
        return null;
    }

    public byte[] serializeForOutbox() {
        return String.format(
                "{\"orderId\":%d,\"accountId\":%d,\"symbol\":\"%s\",\"side\":\"%s\",\"quantity\":%d,\"price\":%d}",
                orderId, accountId, symbol, side, quantity, price
        ).getBytes();
    }
}
