package com.hts.order.domain.service;

import com.hts.generated.grpc.*;
import com.hts.order.api.grpc.AccountGrpcClient;
import com.hts.order.domain.model.OrderEntity;
import com.hts.order.domain.model.ServiceResult;
import com.hts.order.exceptions.DatabaseException;
import com.hts.order.exceptions.OrderNotFoundException;
import com.hts.order.infrastructure.CompensationExecutor;
import com.hts.order.infrastructure.repository.IdempotencyRepository;
import com.hts.order.infrastructure.repository.OrderWriteRepository;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.pgclient.PgPool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.UUID;

@ApplicationScoped
public class OrderCommandService {

    private static final Logger log = Logger.getLogger(OrderCommandService.class);

    @Inject AccountGrpcClient accountClient;
    @Inject OrderWriteRepository orderWriteRepository;
    @Inject IdempotencyRepository idempotencyRepository;
    @Inject CompensationExecutor compensationExecutor;
    @Inject PgPool client;

    public Uni<ServiceResult> handlePlace(long accountId, PlaceOrderRequest request) {
        String idempotencyKey = request.getIdempotencyKey();

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Uni.createFrom().item(
                ServiceResult.failure(OrderStatus.REJECTED, "Idempotency key required")
            );
        }

        if (request.getSide() != Side.BUY && request.getSide() != Side.SELL) {
            return Uni.createFrom().item(
                ServiceResult.failure(OrderStatus.REJECTED, "Invalid side")
            );
        }

        return idempotencyRepository.tryAcquireLock(idempotencyKey, accountId)
            .onItem().transformToUni(lockAcquired -> {
                if (!lockAcquired) {
                    log.infof("Duplicate request detected: idempotencyKey=%s", idempotencyKey);
                    return fetchExistingResult(idempotencyKey);
                }

                return processNewOrder(accountId, idempotencyKey, request);
            });
    }

    private Uni<ServiceResult> processNewOrder(long accountId, String idempotencyKey, PlaceOrderRequest request) {
        long orderId = generateOrderId();
        String reserveId = generateReserveId();

        OrderEntity order = OrderEntity.from(
                orderId, accountId, request.getSymbol(), request.getSide(),
                request.getOrderType(), request.getQuantity(), request.getPrice(),
                request.getTimeInForce(), reserveId
        );

        return (request.getSide() == Side.BUY
            ? handleBuyOrderWithCompensation(order, idempotencyKey)
            : handleSellOrderWithCompensation(order, idempotencyKey))
            .onFailure().call(ex -> {
                log.errorf(ex, "Order processing failed: idempotencyKey=%s, orderId=%d",
                          idempotencyKey, orderId);
                return idempotencyRepository.updateFailed(idempotencyKey, ex.getMessage());
            });
    }

    private Uni<ServiceResult> fetchExistingResult(String idempotencyKey) {
        return idempotencyRepository.findResult(idempotencyKey)
            .map(result -> {
                if (result == null || result.orderId() == null) {
                    return ServiceResult.failure(OrderStatus.REJECTED, "Processing");
                }

                if ("SUCCESS".equals(result.status())) {
                    return ServiceResult.success(result.orderId());
                } else {
                    return ServiceResult.failure(OrderStatus.REJECTED, "Previously failed");
                }
            });
    }

    private Uni<ServiceResult> handleBuyOrderWithCompensation(OrderEntity order, String idempotencyKey) {
        long amountMicroUnits = order.price() * order.quantity();

        return accountClient.reserveCash(
                order.accountId(),
                amountMicroUnits,
                "USD",
                order.reserveId(),
                String.valueOf(order.orderId())
        )
        .onItem().transformToUni(reply -> {
            if (reply.getCode() == AccoutResult.SUCCESS) {
                return persistOrderWithIdempotency(order, idempotencyKey)
                    .onFailure().call(dbError -> {
                        log.errorf(dbError, "DB failed after reserve, compensating: orderId=%d, reserveId=%s",
                                  order.orderId(), order.reserveId());
                        return compensationExecutor.compensateCashReserve(order.accountId(), order.reserveId());
                    });
            } else {
                log.warnf("Cash reserve failed: accountId=%d, orderId=%d, code=%s",
                         order.accountId(), order.orderId(), reply.getCode());
                return Uni.createFrom().item(
                    ServiceResult.failure(OrderStatus.REJECTED, "Insufficient funds")
                );
            }
        })
        .onFailure().invoke(t ->
            log.errorf(t, "Reserve cash RPC failed: accountId=%d, orderId=%d",
                      order.accountId(), order.orderId())
        )
        .onFailure().recoverWithItem(t ->
            ServiceResult.failure(OrderStatus.REJECTED, "Account service unavailable")
        );
    }

    private Uni<ServiceResult> handleSellOrderWithCompensation(OrderEntity order, String idempotencyKey) {
        return accountClient.reservePosition(
                order.accountId(),
                order.symbol(),
                order.quantity(),
                order.reserveId(),
                String.valueOf(order.orderId())
        )
        .onItem().transformToUni(reply -> {
            if (reply.getCode() == AccoutResult.SUCCESS) {
                return persistOrderWithIdempotency(order, idempotencyKey)
                    .onFailure().call(dbError -> {
                        log.errorf(dbError, "DB failed after reserve, compensating: orderId=%d, reserveId=%s",
                                  order.orderId(), order.reserveId());
                        return compensationExecutor.compensatePositionReserve(order.accountId(), order.reserveId());
                    });
            } else {
                log.warnf("Position reserve failed: accountId=%d, orderId=%d, symbol=%s, code=%s",
                         order.accountId(), order.orderId(), order.symbol(), reply.getCode());
                return Uni.createFrom().item(
                    ServiceResult.failure(OrderStatus.REJECTED, "Insufficient position")
                );
            }
        })
        .onFailure().invoke(t ->
            log.errorf(t, "Reserve position RPC failed: accountId=%d, orderId=%d",
                      order.accountId(), order.orderId())
        )
        .onFailure().recoverWithItem(t ->
            ServiceResult.failure(OrderStatus.REJECTED, "Account service unavailable")
        );
    }

    public Uni<ServiceResult> handleCancel(long accountId, CancelOrderRequest request) {
        String idempotencyKey = request.getIdempotencyKey();

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Uni.createFrom().item(
                ServiceResult.failure(OrderStatus.REJECTED, "Idempotency key required")
            );
        }

        return idempotencyRepository.tryAcquireLock(idempotencyKey, accountId)
            .onItem().transformToUni(lockAcquired -> {
                if (!lockAcquired) {
                    log.infof("Duplicate cancel request detected: idempotencyKey=%s", idempotencyKey);
                    return fetchExistingResult(idempotencyKey);
                }

                return processCancelOrder(accountId, idempotencyKey, request);
            });
    }

    private Uni<ServiceResult> processCancelOrder(long accountId, String idempotencyKey, CancelOrderRequest request) {
        return client.withTransaction(conn ->
            orderWriteRepository.markCancelRequested(conn, request.getOrderId(), accountId)
        )
        .onFailure().invoke(t ->
            log.errorf(t, "Cancel DB failed: orderId=%d, accountId=%d",
                      request.getOrderId(), accountId)
        )
        .onFailure().recoverWithItem((OrderWriteRepository.CancelResult) null)
        .onItem().transformToUni(result -> {
            if (result == null) {
                return idempotencyRepository.updateFailed(idempotencyKey, "Order not found")
                    .replaceWith(ServiceResult.failure(OrderStatus.REJECTED, "Order not found or database error"));
            }
            return releaseReserveWithIdempotency(accountId, request.getOrderId(),
                                                result.side(), result.reserveId(), idempotencyKey);
        });
    }

    private Uni<ServiceResult> releaseReserveWithIdempotency(long accountId, long orderId,
                                                             String side, String reserveId, String idempotencyKey) {
        Uni<CommonReply> releaseCall = "BUY".equals(side)
            ? accountClient.releaseCash(accountId, reserveId)
            : accountClient.releasePosition(accountId, reserveId);

        return releaseCall
            .onItem().transformToUni(reply -> {
                ServiceResult result = ServiceResult.of(OrderStatus.CANCEL_REQUESTED, orderId, "Cancel requested");
                return idempotencyRepository.updateSuccess(
                    idempotencyKey,
                    orderId,
                    String.format("{\"orderId\":%d,\"status\":\"CANCEL_REQUESTED\"}", orderId)
                ).replaceWith(result);
            })
            .onFailure().call(t -> {
                log.errorf(t, "Failed to release reserve: accountId=%d, orderId=%d, reserveId=%s, side=%s",
                          accountId, orderId, reserveId, side);
                return idempotencyRepository.updateFailed(idempotencyKey, "Release failed: " + t.getMessage());
            })
            .onFailure().recoverWithItem(t ->
                ServiceResult.of(OrderStatus.CANCEL_REQUESTED, orderId, "Cancel requested (release failed)")
            );
    }

    private Uni<ServiceResult> persistOrderWithIdempotency(OrderEntity order, String idempotencyKey) {
        return client.withTransaction(conn ->
            orderWriteRepository.insertOrderAtomic(conn, order, "ORDER_PLACED")
                .chain(() -> idempotencyRepository.updateSuccessInTx(
                    conn,
                    idempotencyKey,
                    order.orderId(),
                    String.format("{\"orderId\":%d,\"status\":\"SUCCESS\"}", order.orderId())
                ))
        )
        .map(v -> ServiceResult.success(order.orderId()))
        .onFailure().invoke(t ->
            log.errorf(t, "Persist order failed: orderId=%d, accountId=%d",
                      order.orderId(), order.accountId())
        );
    }

    private long generateOrderId() {
        return System.currentTimeMillis() * 1000 + (long) (Math.random() * 1000);
    }

    private String generateReserveId() {
        return UUID.randomUUID().toString();
    }
}
