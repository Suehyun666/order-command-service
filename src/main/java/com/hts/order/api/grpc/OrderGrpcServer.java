package com.hts.order.api.grpc;

import com.hts.generated.grpc.*;
import com.hts.order.domain.model.ServiceResult;
import com.hts.order.domain.service.OrderCommandService;
import io.grpc.Status;
import io.quarkus.grpc.GrpcService;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@GrpcService
public class OrderGrpcServer implements OrderService {

    private static final Logger log = Logger.getLogger(OrderGrpcServer.class);

    @Inject OrderCommandService orderCommandService;

    @Override
    public Uni<OrderResponse> placeOrder(PlaceOrderRequest request) {
        Long accountId = AuthInterceptor.ACCOUNT_ID_CONTEXT_KEY.get();

        if (accountId == null || accountId <= 0) {
            log.warn("Invalid or missing accountId in context");
            return Uni.createFrom().item(buildErrorResponse(0, "Unauthorized"));
        }

        log.infof("PlaceOrder: accountId=%d, symbol=%s, side=%s, quantity=%d, price=%d",
                  accountId, request.getSymbol(), request.getSide(), request.getQuantity(), request.getPrice());

        return orderCommandService.handlePlace(accountId, request)
                .map(this::toResponse)
                .onFailure().recoverWithItem(t -> {
                    log.errorf(t, "PlaceOrder failed: accountId=%d, symbol=%s", accountId, request.getSymbol());
                    return buildErrorResponse(0, t.getMessage());
                });
    }

    @Override
    public Uni<OrderResponse> cancelOrder(CancelOrderRequest request) {
        Long accountId = AuthInterceptor.ACCOUNT_ID_CONTEXT_KEY.get();

        if (accountId == null || accountId <= 0) {
            log.warn("Invalid or missing accountId in context");
            return Uni.createFrom().item(buildErrorResponse(request.getOrderId(), "Unauthorized"));
        }

        log.infof("CancelOrder: accountId=%d, orderId=%d", accountId, request.getOrderId());

        return orderCommandService.handleCancel(accountId, request)
                .map(this::toResponse)
                .onFailure().recoverWithItem(t -> {
                    log.errorf(t, "CancelOrder failed: accountId=%d, orderId=%d", accountId, request.getOrderId());
                    return buildErrorResponse(request.getOrderId(), t.getMessage());
                });
    }

    private OrderResponse toResponse(ServiceResult result) {
        return OrderResponse.newBuilder()
                .setOrderId(result.orderId())
                .setStatus(result.status())
                .setMessage(result.message())
                .setTimestamp(System.currentTimeMillis())
                .build();
    }

    private OrderResponse buildErrorResponse(long orderId, String message) {
        return OrderResponse.newBuilder()
                .setOrderId(orderId)
                .setStatus(OrderStatus.REJECTED)
                .setMessage(message)
                .setTimestamp(System.currentTimeMillis())
                .build();
    }
}
