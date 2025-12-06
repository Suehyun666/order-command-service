package com.hts.order.api.grpc;

import com.hts.generated.grpc.*;
import com.hts.generated.grpc.account.order.*;
import io.quarkus.grpc.GrpcClient;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.jboss.logging.Logger;

import java.time.temporal.ChronoUnit;

@ApplicationScoped
public class AccountGrpcClient {

    private static final Logger log = Logger.getLogger(AccountGrpcClient.class);

    @Inject
    @GrpcClient("account-command-service")
    AccountOrderService accountService;

    @Retry(maxRetries = 3, delay = 10, delayUnit = ChronoUnit.MILLIS, jitter = 5)
    @Timeout(value = 100, unit = ChronoUnit.MILLIS)
    public Uni<CommonReply> reserveCash(long accountId, long amountMicroUnits, String currency, String reserveId, String orderId) {
        log.debugf("ReserveCash: accountId=%d, reserveId=%s", accountId, reserveId);

        ReserveCashRequest request = ReserveCashRequest.newBuilder()
                .setAccountId(accountId)
                .setAmountMicroUnits(amountMicroUnits)
                .setCurrency(currency)
                .setReserveId(reserveId)
                .setOrderId(orderId)
                .build();

        return accountService.reserveCash(request)
                .onFailure().invoke(t -> log.errorf(t, "ReserveCash failed: %s", reserveId));
    }

    @Retry(maxRetries = 3, delay = 10, delayUnit = ChronoUnit.MILLIS, jitter = 5)
    @Timeout(value = 100, unit = ChronoUnit.MILLIS)
    public Uni<CommonReply> releaseCash(long accountId, String reserveId) {
        ReleaseCashRequest request = ReleaseCashRequest.newBuilder()
                .setAccountId(accountId)
                .setReserveId(reserveId)
                .build();

        return accountService.releaseCash(request)
                .onFailure().invoke(t -> log.errorf(t, "CancelCashReserve failed: %s", reserveId));
    }

    @Retry(maxRetries = 3, delay = 10, delayUnit = ChronoUnit.MILLIS, jitter = 5)
    @Timeout(value = 100, unit = ChronoUnit.MILLIS)
    public Uni<CommonReply> reservePosition(long accountId, String symbol,
                                           long quantity, String reserveId, String orderId) {
        ReservePositionRequest request = ReservePositionRequest.newBuilder()
                .setAccountId(accountId)
                .setSymbol(symbol)
                .setQuantity(quantity)
                .setReserveId(reserveId)
                .setOrderId(orderId)
                .build();

        return accountService.reservePosition(request)
                .onFailure().invoke(t -> log.errorf(t, "ReservePosition failed: %s", reserveId));
    }

    @Retry(maxRetries = 3, delay = 10, delayUnit = ChronoUnit.MILLIS, jitter = 5)
    @Timeout(value = 100, unit = ChronoUnit.MILLIS)
    public Uni<CommonReply> releasePosition(long accountId, String reserveId) {
        ReleasePositionRequest request = ReleasePositionRequest.newBuilder()
                .setAccountId(accountId)
                .setReserveId(reserveId)
                .build();

        return accountService.releasePosition(request)
                .onFailure().invoke(t -> log.errorf(t, "CancelPositionReserve failed: %s", reserveId));
    }
}
