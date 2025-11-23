package com.hts.order.domain.service;

import com.hts.order.api.grpc.AccountGrpcClient;
import com.hts.order.domain.CompensationExecutor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * OrderService - 비즈니스 로직 (Mutiny 비동기)
 *
 * 책임:
 * - 주문 검증
 * - Account Reserve (gRPC)
 * - 레포지토리 저장 (트랜잭션)
 * - Transactional Outbox 이벤트 발행
 */
@ApplicationScoped
public class OrderService {

    @Inject AccountGrpcClient accountClient;
    @Inject CompensationExecutor compensationExecutor;

    /**
     * 주문 접수 처리 (비동기)
     */
    public void handlePlace() {
    }

    /**
     * 주문 취소 처리 (비동기)
     */
    public void handleCancel(){
    }
}
