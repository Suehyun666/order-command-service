package com.hts.order.infrastructure.repository;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class IdempotencyRepository {

    private static final Logger log = Logger.getLogger(IdempotencyRepository.class);

    @Inject PgPool client;

    public Uni<Boolean> tryAcquireLock(String idempotencyKey, long accountId) {
        return client.preparedQuery("""
            INSERT INTO idempotency_keys (idempotency_key, account_id, status)
            VALUES ($1, $2, 'PROCESSING')
            """)
            .execute(Tuple.of(idempotencyKey, accountId))
            .map(rowSet -> true)
            .onFailure().recoverWithItem(ex -> {
                if (ex.getMessage() != null && ex.getMessage().contains("duplicate key")) {
                    return false;
                }
                throw new RuntimeException("Idempotency lock failed", ex);
            });
    }

    public Uni<IdempotencyResult> findResult(String idempotencyKey) {
        return client.preparedQuery("""
            SELECT order_id, status, response_payload
            FROM idempotency_keys
            WHERE idempotency_key = $1
            """)
            .execute(Tuple.of(idempotencyKey))
            .map(rows -> {
                if (!rows.iterator().hasNext()) {
                    return null;
                }
                Row row = rows.iterator().next();
                return new IdempotencyResult(
                    row.getLong("order_id"),
                    row.getString("status"),
                    row.getJsonObject("response_payload")
                );
            });
    }

    public Uni<Void> updateSuccess(String idempotencyKey, long orderId, String responsePayload) {
        return client.preparedQuery("""
            UPDATE idempotency_keys
            SET status = 'SUCCESS', order_id = $1, response_payload = $2::jsonb
            WHERE idempotency_key = $3
            """)
            .execute(Tuple.of(orderId, responsePayload, idempotencyKey))
            .replaceWithVoid();
    }

    public Uni<Void> updateSuccessInTx(io.vertx.mutiny.sqlclient.SqlConnection conn, String idempotencyKey, long orderId, String responsePayload) {
        return conn.preparedQuery("""
            UPDATE idempotency_keys
            SET status = 'SUCCESS', order_id = $1, response_payload = $2::jsonb
            WHERE idempotency_key = $3
            """)
            .execute(Tuple.of(orderId, responsePayload, idempotencyKey))
            .replaceWithVoid();
    }

    public Uni<Void> updateFailed(String idempotencyKey, String reason) {
        return client.preparedQuery("""
            UPDATE idempotency_keys
            SET status = 'FAILED', response_payload = $1::jsonb
            WHERE idempotency_key = $2
            """)
            .execute(Tuple.of(String.format("{\"error\":\"%s\"}", reason), idempotencyKey))
            .replaceWithVoid();
    }

    public record IdempotencyResult(Long orderId, String status, io.vertx.core.json.JsonObject responsePayload) {}
}
