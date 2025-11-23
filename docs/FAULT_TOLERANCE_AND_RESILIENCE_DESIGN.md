# Order Service - 내결함성 및 복원력 설계 (Fault Tolerance & Resilience Design)

## 목차

1. [전제: SLO 및 실패 도메인](#1-전제-slo-및-실패-도메인)
2. [구조 및 응답 흐름](#2-구조-및-응답-흐름)
3. [Account gRPC 설계](#3-account-grpc-설계)
4. [멱등성(Idempotency) 및 중복 처리](#5-멱등성idempotency-및-중복-처리)
5. [Outbox 패턴 및 이벤트 발행](#6-outbox-패턴-및-이벤트-발행)
6. [데이터 계층 이중화 및 장애조치](#7-데이터-계층-이중화-및-장애조치)
7. [서비스 인스턴스 확장 전략](#8-서비스-인스턴스-확장-전략)
8. [장애 시나리오별 대응 전략](#9-장애-시나리오별-대응-전략)
9. [설정값 및 임계값](#10-설정값-및-임계값)
10. [구현 체크리스트](#11-구현-체크리스트)

---

## 1. 전제: SLO 및 실패 도메인

### 1.1 서비스 수준 목표 (SLO)

| 메트릭                | 목표값        | 이유                 |
| ------------------ | ---------- | ------------------ |
| **주문 경로 p99 지연시간** | ≤ 100ms    | 사용자 체감 품질 보장       |
| **데이터 일관성**        | 100%       | 잔고/체결 데이터 절대 오염 금지 |
| **주문 유실률**         | 0%         | 고객 자산 직결           |
| **가용성**            | ≥ 99.9%    | 월 43분 이내 장애 허용     |
| **처리량**            | 1,000+ TPS | 초기 목표, 확장 가능 구조    |

### 1.2 실패 도메인 정의

| 도메인        | 실패 유형                                       | 영향 범위            |
| ---------- | ------------------------------------------- | ---------------- |
| **네트워크**   | 단절, 지연, 패킷손실, DNS, NAT, 방화벽                 | 요청 타임아웃, 재시도 필요  |
| **애플리케이션** | 프로세스 크래시, OOM, 스레드 데드락, GC 스톨               | 서비스 중단, 장애조치 필요  |
| **상태 저장**  | PostgreSQL 장애/리더 전환, Redis 장애, Kafka 브로커 실패 | 데이터 접근 불가, 자동 전환 |
| **의존 호출**  | Account gRPC 타임아웃/에러, 재시도 폭주, 캐스케이드 실패      | 주문 거부, 서킷 오픈     |
| **운영**     | 배포 중 단절, 설정 오류, 시계 불일치                      | 일시적 서비스 중단       |

---

## 2. 구조 및 응답 흐름

### 2.1 아키텍처 개요

```
┌──────────┐
│  Client  │
└────┬─────┘
     │ TCP (24-byte Header + Protobuf)
     ▼
┌─────────────────┐
│  Gateway        │  ← Control Plane: 라우팅, 모니터링
│                 │
└────┬────────────┘
     │ gRPC (correlationId 포함)
     ▼
┌─────────────────────────────────────────┐
│  Order-Serive-0  Order-Service-1    -N  │  ← Data Plane: 비즈니스 로직 실행
│  (Quarkus)      (Quarkus)          ...  │
└─────┬───────────────────────────────────┘
      │
      ├─ PostgreSQL (주문 저장, Outbox)
      ├─ Account Service (gRPC - 잔고 예약)
      └─ 주문 게이트웨이 전달
```

### 2.2 핵심 원칙

| 원칙                     | 설명                                      | 이유             |
| ---------------------- |-----------------------------------------| -------------- |
| **Correlation Map**    |  `correlationId → Channel` 매핑 관리 | 응답 재조립         |
| **Channel 격리**         |                                         | 계층 분리, 테스트 용이성 |
| **비동기 콜백**             | gRPC 응답을 blocking 대기하지 않음               | 이벤트 루프 블로킹 방지  |



**핵심:**

**correlationId를 응답에 포함**만 시킴
- **Channel 접근 금지** - **예외도 응답으로 변환** → gRPC status code 대신 비즈니스 코드

---

## 3. GateWay 설계

### 3.1 GateWay 책임 및 기술 스택

**기술:** 미정

**책임:**

- HTS 서비스 여러 인스턴스에 대한 **단일 진입점**
- **복원 로직** (Retry, Timeout, Circuit Breaker, Bulkhead)
- **멱등성 보장** 
- **Hedging** (선택)

### 3.2 복원 로직 구성

| 기능              | MicroProfile FT 애노테이션 | 설정값                                                          | 이유                          |
| --------------- | --------------------- | ------------------------------------------------------------ | --------------------------- |
| **전체 시간 제한**    | `@Timeout`            | **50ms**                                                     | 꼬리 지연 차단, p99 100ms 목표      |
| **재시도**         | `@Retry`              | `maxRetries=1`, `delay=10ms`                                 | 일시 장애 복구, 재시도 예산 10% 이하     |
| **격리 (동시성 제한)** | `@Bulkhead`           | `value=128`, `type=SEMAPHORE`                                | Account RPC 전용 슬롯, 다른 기능 보호 |
| **서킷 브레이커**     | `@CircuitBreaker`     | `requestVolumeThreshold=50`, `failureRatio=0.2`, `delay=30s` | 연쇄 장애 차단, 빠른 실패             |


### 3.4 Hedging (선택 기능)

**개념:** p95 근처 지연 시 **두 번째 호출을 다른 인스턴스로 투사**, 먼저 도착한 응답 사용.


**주의:**

- **비용 증가** (요청 2배)
- **멱등성 필수** (같은 requestId)
- **p99 개선 효과**: 15~25% (실측 필요)
- **재시도 예산 초과 시 비활성화**

### 3.5 Quarkus gRPC 설정

```properties
# application.properties

# Account 서비스 엔드포인트 
quarkus.grpc.clients.account.host=account-service
quarkus.grpc.clients.account.port=9000
quarkus.grpc.clients.account.load-balancing-policy=round_robin
quarkus.grpc.clients.account.plain-text=true

# Keep-alive 설정 (연결 유지)
quarkus.grpc.clients.account.keep-alive-time=15s
quarkus.grpc.clients.account.keep-alive-without-calls=true

# Connection pool 설정 (인스턴스당)
quarkus.grpc.clients.account.max-inbound-message-size=1048576

# Fault Tolerance 메트릭 활성화
quarkus.smallrye-fault-tolerance.mp-compatibility=true
```

---


**핵심:**
- **정상 경로**: 메모리 큐 → Kafka (DB 폴링 없음, 초고속)
- **장애 복구**: DB Outbox 테이블에서 미전송 이벤트 재발행
- **성능**: DB 쓰기는 트랜잭션 내 1회, 폴링은 장애 시에만


### 5.5 DLQ (Dead Letter Queue) 전략

**3회 재시도 실패 시 DLQ 토픽으로 이동:**

```java
@Scheduled(every = "5m")
void moveToDLQ() {
    List<OrderOutboxRecord> dead = dsl.selectFrom(ORDER_OUTBOX)
        .where(ORDER_OUTBOX.STATUS.eq("FAILED"))
        .and(ORDER_OUTBOX.RETRY_COUNT.ge(3))
        .fetch();

    dead.forEach(record -> {
        // DLQ 토픽 발행
        kafkaPublisher.publishToDLQ(record.getPayload().data());

        // Outbox에서 삭제 또는 상태 변경
        dsl.update(ORDER_OUTBOX)
            .set(ORDER_OUTBOX.STATUS, "DLQ")
            .where(ORDER_OUTBOX.ID.eq(record.getId()))
            .execute();
    });

    if (!dead.isEmpty()) {
        log.warn("Moved {} events to DLQ", dead.size());
    }
}
```

---

## 6. 데이터 계층 이중화 및 장애조치

### 6.1 PostgreSQL 고가용성 

**구성:** 

```
┌──────────────┐
│   HAProxy    │  ← Writer/Reader 분리 엔드포인트
└──────┬───────┘
       │
   ┌───┴──────┬──────────┐
   │          │          │
┌──▼──┐  ┌───▼──┐  ┌───▼──┐
│ PG1 │  │ PG2  │  │ PG3  │
│Leader│  │Replica│ │Replica│
└─────┘  └──────┘  └──────┘
   │          │          │
   └────┬─────┴──────────┘
        ▼
```

**설정 포인트:**

| 항목                     | 값                                     | 이유                            |
| ---------------------- | ------------------------------------- |-------------------------------|
| **Replication Mode**   | `synchronous_commit = on` (리더 + 1 동기) | RPO ≈ 0, 일관성 보장               |
| **Failover Time**      | ≤ 5–10초                               |  자동 감지 + 리더 전환         |
| **Connection Timeout** | 5초                                    | 빠른 실패, 재연결                    |
| **Max Connections**    | 200                                   | Service 4개 × 40 = 160 + 예약 40 |

**Quarkus 설정:**

```properties
# Writer 
quarkus.datasource.jdbc.url=jdbc:postgresql://pg-writer:5432/order_db
quarkus.datasource.jdbc.max-size=40
quarkus.datasource.jdbc.min-size=10

# Reader (Replica)
quarkus.datasource.reader.jdbc.url=jdbc:postgresql://pg-reader:5432/order_db
quarkus.datasource.reader.jdbc.max-size=20

# Connection pool 설정
quarkus.datasource.jdbc.acquisition-timeout=5s
quarkus.datasource.jdbc.idle-removal-interval=5m
quarkus.datasource.jdbc.max-lifetime=30m
```

### 6.2 Redis 고가용성 (Sentinel)

**구성:** Redis Sentinel (3 Sentinel + 1 Master + 2 Replica)

```
┌────────────────┐
│ Redis Sentinel │
│    (Quorum=2)  │
└────────┬───────┘
         │
   ┌─────┴──────┬──────────┐
   │            │          │
┌──▼────┐  ┌───▼───┐  ┌───▼───┐
│Master │  │Replica│  │Replica│
└───────┘  └───────┘  └───────┘
```

**Quarkus 설정:**

```properties
# Redis Sentinel
quarkus.redis.hosts=redis://sentinel1:26379,redis://sentinel2:26379,redis://sentinel3:26379
quarkus.redis.client-type=sentinel
quarkus.redis.master-name=mymaster
quarkus.redis.timeout=2s
```

**사용 용도:**

- **Session 저장** (sessionId → accountId)
- **Order 인덱스** (orderId → symbol, 빠른 조회)

**주의:**

- **잔고/주문 원본은 PostgreSQL** (Redis는 캐시만)
- **캐시 미스 시 DB 폴백** → 서비스 지속 가능

---


**권장:** 3~5개로 시작, CPU 60% 초과 시 +1

### 7.3 PostgreSQL Connection Pool 사이징

**원칙:** **총합이 `max_connections`를 절대 초과하지 않게**

```
PostgreSQL max_connections: 200
- 운영/백업/모니터링: 30
- 사용 가능: 170

Service 4개 × 40 = 160
Account 4개 × 30 = 120 (별도 DB 사용 시)
```


## 8. 장애 시나리오별 대응 전략

### 8.1 시나리오 1: Account 서비스 부분 장애

**증상:** Account gRPC 타임아웃/에러 증가

**자동 대응:**

1. **Retry 1회** (10ms 백오프) → 일시 장애 복구
2. **Circuit Breaker 오픈** (실패율 20% 초과) → 30초간 빠른 실패
3. **Order 응답: 503 Service Unavailable**

**운영 조치:**

- Account 인스턴스 헬스 체크 (CPU, 메모리, DB 연결)
- 필요 시 인스턴스 재시작 or 추가
- Circuit 수동 리셋 (필요 시)

### 8.2 시나리오 2: PostgreSQL 리더 다운

**증상:** DB 쓰기 실패,  리더 전환 감지

**자동 대응:**

1. ** 자동 Failover** (5~10초)
2. Order Service: DB 연결 재시도 (Quarkus Agroal 자동)
3. 전환 완료 후 정상화

**운영 조치:**


### 8.3 시나리오 3: Kafka 브로커 다운

**증상:** Outbox Dispatcher Kafka 발행 실패

**자동 대응:**

1. **Outbox 테이블에 READY 상태 유지** (DB 트랜잭션은 성공)
2. **재시도 로직** (1분마다, 최대 3회)
3. **DLQ 이동** (3회 실패 후)

**운영 조치:**

- Kafka 클러스터 헬스 체크
- 브로커 재시작 or 교체
- DLQ 이벤트 수동 재처리

### 8.4 시나리오 4: Order Service 1개 크래시


---

## 9. 설정값 및 임계값


### 9.2 Outbox Dispatcher

| 파라미터               | 값              | 근거            |
| ------------------ | -------------- | ------------- |
| **Batch Size**     | 500            | DB INSERT 최적화 |
| **Flush Interval** | 20ms           | 지연과 처리량 균형    |
| **Retry Count**    | 3              | 일시 장애 복구      |
| **DLQ 이동 조건**      | 3회 실패 + 1시간 초과 | 영구 실패 격리      |

### 9.3 PostgreSQL

| 파라미터                         | 값              | 근거                        |
| ---------------------------- | -------------- |---------------------------|
| **max_connections**          | 200            | Serivce + Account + 운영 여유 |
| **synchronous_commit**       | on (1 replica) | RPO ≈ 0, 일관성 보장           |
| **Failover Time**            | ≤ 10s          |  자동 감지 + 전환        |
| **Connection Pool (Serivce)** | 40             | 4 Serivce × 40 = 160      |



## 10. 구현 체크리스트

### Phase 1: 멱등성 및 Outbox (필수)

### Phase 2: GateWay


### Phase 3: Correlation Map 

### Phase 4: PostgreSQL 고가용성

- [ ] **동기 복제** 설정 (`synchronous_commit=on`)
- [ ] **HAProxy** 설정 (Writer/Reader 분리)
- [ ] **Connection Pool** 조정 (Order 40, Account 30)

### Phase 5: Knative Autoscaling

- [ ] **Knative Service** 정의 (동시성 100, min=4, max=20)
- [ ] **Liveness/Readiness Probe** 구현
- [ ] **Graceful Shutdown** 구현 (메모리 큐 flush → Kafka)
- [ ] **Outbox Recovery Job** 구현 (5분마다 DB 폴링)

### Phase 6: 모니터링 및 알람

- [ ] **Prometheus 메트릭** 노출 (`order.place.latency`, `circuit_breaker.state` 등)
- [ ] **Grafana 대시보드** 구성 (p95/p99, 재시도율, 서킷 상태)
- [ ] **알람 규칙** 설정 (p99 > 100ms 3분, Circuit 오픈, DLQ 증가)

### Phase 7: Chaos Testing

- [ ] **Account 서비스 다운** 시뮬레이션 (Circuit Breaker 검증)
- [ ] **PostgreSQL 리더 전환** 시뮬레이션 ( Failover 검증)
- [ ] **Kafka 브로커 다운** 시뮬레이션 (메모리 큐 → DB Outbox 복구 검증)
- [ ] **Order Service 크래시** 시뮬레이션 (자동 복구 검증)
- [ ] **프로세스 크래시 후 재기동** (DB Outbox Recovery Job 검증)

---