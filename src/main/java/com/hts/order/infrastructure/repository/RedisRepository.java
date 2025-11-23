package com.hts.order.infrastructure.repository;

import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.keys.ReactiveKeyCommands;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * Session Repository - Redis 기반 세션 관리
 *
 * Reactive Redis 사용 (Vert.x event-loop에서 안전)
 */
@ApplicationScoped
public class RedisRepository {
    private static final Logger log = LoggerFactory.getLogger(RedisRepository.class);
    private static final String SESSION_PREFIX = "session:";
    private static final Duration SESSION_TTL = Duration.ofDays(1); // 24시간

    private final ReactiveValueCommands<String, String> reactiveValueCommands;
    private final ReactiveKeyCommands<String> reactiveKeyCommands;

    @Inject
    public RedisRepository(RedisDataSource redisDataSource, ReactiveRedisDataSource reactiveRedisDataSource) {
        this.reactiveValueCommands = reactiveRedisDataSource.value(String.class, String.class);
        this.reactiveKeyCommands = reactiveRedisDataSource.key(String.class);
        log.info("SessionRepository initialized with Reactive RedisDataSource");
    }

    /**
     * 세션 ID로 계좌 ID 조회 (비동기 - 원자적 연산 - lua script)
     * @param sessionId 세션 ID
     * @return 계좌 ID Uni (없으면 null)
     */
    public Uni<Long> getAccountId(long sessionId) {
        String key = SESSION_PREFIX + sessionId;

        return reactiveValueCommands.get(key)
                .onItem().transform(value -> {
                    if (value == null) {
                        log.debug("Session not found: sessionId={}", sessionId);
                        return null;
                    }
                    try {
                        return Long.parseLong(value);
                    } catch (NumberFormatException e) {
                        log.error("Invalid accountId format for sessionId={}", sessionId, e);
                        return null;
                    }
                })
                .onFailure().recoverWithItem(e -> {
                    log.error("Failed to get accountId for sessionId={}", sessionId, e);
                    return null;
                });
    }
}
