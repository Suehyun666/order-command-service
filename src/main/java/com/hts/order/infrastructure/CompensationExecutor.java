package com.hts.order.infrastructure;

import com.hts.order.api.grpc.AccountGrpcClient;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class CompensationExecutor {

    private static final Logger log = Logger.getLogger(CompensationExecutor.class);

    @Inject AccountGrpcClient accountClient;

    public Uni<Void> compensateCashReserve(long accountId, String reserveId) {
        log.warnf("Compensating cash reserve: accountId=%d, reserveId=%s", accountId, reserveId);

        return accountClient.releaseCash(accountId, reserveId)
                .replaceWithVoid()
                .onFailure().invoke(t ->
                    log.errorf(t, "Compensation failed for cash reserve: accountId=%d, reserveId=%s",
                              accountId, reserveId)
                );
    }

    public Uni<Void> compensatePositionReserve(long accountId, String reserveId) {
        log.warnf("Compensating position reserve: accountId=%d, reserveId=%s", accountId, reserveId);

        return accountClient.releasePosition(accountId, reserveId)
                .replaceWithVoid()
                .onFailure().invoke(t ->
                    log.errorf(t, "Compensation failed for position reserve: accountId=%d, reserveId=%s",
                              accountId, reserveId)
                );
    }
}