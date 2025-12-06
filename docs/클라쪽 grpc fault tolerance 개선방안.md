Fault Tolerance 개선 방안

  Pod 삭제 시 요청 실패를 방지하려면 클라이언트 쪽 개선이 가장 효과적입니다. 현재 문제점과
  해결 방법:

  현재 문제점

  1. 클라이언트: Retry/재연결 로직 없음
  2. Connection 관리: Pod 삭제 시 GOAWAY를 받아도 처리 안함
  3. Load Balancing: 1000개 클라이언트가 각각 초기 연결 시점의 Pod에 고정됨

  해결 방법 1: 클라이언트 Retry Policy 추가 (권장)

  GrpcAccountClient.java에 gRPC의 built-in retry 추가:

  public GrpcAccountClient(String host, int port) {
      // Service Policy 설정
      Map<String, Object> retryPolicy = new HashMap<>();
      retryPolicy.put("maxAttempts", 3.0);
      retryPolicy.put("initialBackoff", "0.1s");
      retryPolicy.put("maxBackoff", "1s");
      retryPolicy.put("backoffMultiplier", 2.0);
      retryPolicy.put("retryableStatusCodes", Arrays.asList(
          "UNAVAILABLE", "INTERNAL", "UNKNOWN"
      ));

      Map<String, Object> methodConfig = new HashMap<>();
      methodConfig.put("name", Collections.singletonList(new HashMap<>()));
      methodConfig.put("retryPolicy", retryPolicy);

      Map<String, Object> serviceConfig = new HashMap<>();
      serviceConfig.put("methodConfig", Collections.singletonList(methodConfig));

      this.channel = ManagedChannelBuilder.forAddress(host, port)
              .usePlaintext()
              .defaultServiceConfig(serviceConfig)
              .enableRetry()
              .maxRetryAttempts(3)
              .build();
      this.stub = AccountServiceGrpc.newBlockingStub(channel);
  }

  해결 방법 2: Manual Retry Wrapper

  더 세밀한 제어가 필요하면 wrapper 추가:

  @Override
  public CommonReply reserve(long accountId, BigDecimal amount, String reserveId, String 
  orderId) {
      ReserveRequest req = ReserveRequest.newBuilder()
              .setAccountId(accountId)
              .setAmount(amount.toPlainString())
              .setReserveId(reserveId)
              .setOrderId(orderId)
              .build();

      return retryWithBackoff(() -> stub.reserve(req), 3);
  }

  private <T> T retryWithBackoff(Supplier<T> operation, int maxRetries) {
      int attempt = 0;
      while (true) {
          try {
              return operation.get();
          } catch (StatusRuntimeException e) {
              attempt++;
              if (attempt >= maxRetries ||
                  !isRetryable(e.getStatus().getCode())) {
                  throw e;
              }
              try {
                  Thread.sleep(100 * (1L << (attempt - 1))); // exponential backoff
              } catch (InterruptedException ie) {
                  Thread.currentThread().interrupt();
                  throw e;
              }
          }
      }
  }

  private boolean isRetryable(Status.Code code) {
      return code == Status.Code.UNAVAILABLE ||
             code == Status.Code.INTERNAL ||
             code == Status.Code.UNKNOWN;
  }

  해결 방법 3: Connection Health Check (가장 강력)

  주기적으로 connection 상태 확인하고 재연결:

  public final class GrpcAccountClient implements AccountClient {
      private ManagedChannel channel;
      private AccountServiceGrpc.AccountServiceBlockingStub stub;
      private final String host;
      private final int port;

      public GrpcAccountClient(String host, int port) {
          this.host = host;
          this.port = port;
          reconnect();
      }

      private void reconnect() {
          if (channel != null) {
              channel.shutdown();
          }
          this.channel = ManagedChannelBuilder.forAddress(host, port)
                  .usePlaintext()
                  .keepAliveTime(10, TimeUnit.SECONDS)
                  .keepAliveTimeout(5, TimeUnit.SECONDS)
                  .build();
          this.stub = AccountServiceGrpc.newBlockingStub(channel);
      }

      @Override
      public CommonReply reserve(long accountId, BigDecimal amount, String reserveId, 
  String orderId) {
          try {
              return doReserve(accountId, amount, reserveId, orderId);
          } catch (StatusRuntimeException e) {
              if (e.getStatus().getCode() == Status.Code.UNAVAILABLE) {
                  reconnect();
                  return doReserve(accountId, amount, reserveId, orderId);
              }
              throw e;
          }
      }

      private CommonReply doReserve(long accountId, BigDecimal amount, String reserveId, 
  String orderId) {
          ReserveRequest req = ReserveRequest.newBuilder()
                  .setAccountId(accountId)
                  .setAmount(amount.toPlainString())
                  .setReserveId(reserveId)
                  .setOrderId(orderId)
                  .build();
          return stub.reserve(req);
      }
  }

  서버 쪽 개선 (선택사항)

  account-deployment.yaml의 preStop 훅 개선:

  lifecycle:
    preStop:
      exec:
        command: ["/bin/sh", "-c", "sleep 10"]  # 5초 → 10초로 증가

  그리고 readinessProbe 조정:

  readinessProbe:
    httpGet:
      path: /q/health/ready
      port: 9081
    initialDelaySeconds: 5
    periodSeconds: 3
    timeoutSeconds: 2
    failureThreshold: 3  # 2 → 3으로 증가 (9초 유예)

  ---
  권장 사항

  단계별 적용:
  1. 먼저 클라이언트에 Retry Policy 추가 (방법 1) - 가장 간단하고 효과적
  2. 여전히 실패하면 Manual Retry (방법 2) 또는 Reconnect (방법 3) 추가
  3. 서버 쪽 preStop/readiness 조정은 선택사항

  이렇게 하면 Pod 삭제 시 실패율을 0.03% → 0%로 줄일 수 있습니다.

  어떤 방법을 적용해볼까요? 코드 수정을 도와드릴까요?

사용자님의 직관이 매우 정확합니다. \*\*"Check 하고 나중에 Save 한다"\*\*는 방식은 동시성 환경에서 \*\*Race Condition(경합 조건)\*\*을 유발하여 중복 처리가 발생할 수 있는 치명적인 구조입니다.

특히 **3000 TPS / 100ms**라는 고성능 요구사항에서 `SELECT` 후 `INSERT`를 하는 방식은 DB Round Trip을 두 번 발생시키므로 성능적으로도 불리합니다.

이 문제를 해결하고 성능 목표를 달성하기 위한 \*\*"Atomic(원자적) 멱등성 처리 전략"\*\*과 **보완점**을 정리해 드립니다.

-----

### 1\. 문제 분석: 왜 "Check 후 Save"가 위험한가?

* **시나리오:** 아주 짧은 시간(ms 단위) 차이로 동일한 `idempotency_key`를 가진 요청 A와 요청 B가 들어옵니다.
* **현재 로직의 문제:**
    1.  요청 A: `Check(Key)` → 없음 (통과)
    2.  요청 B: `Check(Key)` → 없음 (통과 - **여기서 문제 발생\!**)
    3.  요청 A: `Insert(Processing)` 및 주문 처리 시작
    4.  요청 B: `Insert(Processing)` 및 주문 처리 시작 → **중복 주문/이중 차감 발생**

### 2\. 해결책: 선점 잠금 (Insert First Strategy)

3000 TPS를 견디려면 \*\*DB의 유니크 제약조건(Unique Constraint)\*\*이나 **Redis의 원자적 연산**을 이용해 **"검사와 잠금"을 동시에** 수행해야 합니다.

#### 전략 A: DB Unique Key 활용 (추천 - 데이터 정합성 최우선)

별도의 `checkIdempotency` 조회 없이, **무조건 Insert를 먼저 시도**합니다.

* **테이블 설계:** `idempotency_key` 컬럼에 `UNIQUE INDEX` 필수.
* **로직 흐름:**
    1.  **Transaction 시작**
    2.  `INSERT INTO idempotency_store (key, status) VALUES (?, 'PROCESSING')`
    3.  **성공 시:** 락 획득 성공 → 주문 로직 진행.
    4.  **실패 시 (Duplicate Key Error):** 이미 처리 중이거나 완료된 요청 → `SELECT`로 기존 결과 조회 후 반환.
    5.  주문 로직 완료 후: `UPDATE idempotency_store SET status='DONE', response=... WHERE key=?`
    6.  **Transaction 커밋**

이 방식은 DB I/O를 줄이고(성공 시 Select 불필요), DB 레벨에서 완벽하게 중복을 막습니다.

#### 전략 B: Redis SETNX 활용 (추천 - 속도 최우선)

DB 부하를 줄이고 싶다면 Redis를 씁니다. (3000 TPS 환경에서 가장 적합)

* **명령어:** `SET key "PROCESSING" NX EX 10` (NX: 없으면 세팅, EX: 10초 만료)
* **로직 흐름:**
    1.  Redis `SETNX` 시도.
    2.  **true 리턴:** 락 획득 → DB 트랜잭션 시작 & 주문 처리.
    3.  **false 리턴:** 이미 처리 중 → 잠시 대기 후 결과 조회(Polling) 또는 에러 반환.
    4.  처리 완료 후: Redis 값을 최종 결과로 업데이트 + DB에 멱등성 이력 저장(옵션).

-----

### 3\. 3000 TPS / 100ms 달성을 위한 아키텍처 보완 (Revised)

사용자님의 우려대로 로직을 원자적으로 합치고, 성능을 최적화한 **최종 코드 흐름**입니다. (안정성을 위해 DB 전략 기준)

```java
// OrderCommandService.java

public Uni<ServiceResult> handlePlaceOrder(long accountId, PlaceOrderRequest request) {
    String key = request.getIdempotencyKey();

    // 1. [Atomic] 멱등성 키 선점 시도 (Insert First)
    return idempotencyRepository.tryLock(key, accountId) // INSERT ... VALUES ('PROCESSING')
            .onItem().transformToUni(lockAcquired -> {
                
                if (!lockAcquired) {
                    // 2. 락 실패 = 이미 요청됨 -> 기존 결과 조회하여 반환
                    return idempotencyRepository.findResult(key)
                            .map(saved -> ServiceResult.success(saved.getResponse()));
                }

                // 3. 락 획득 성공 -> 비즈니스 로직 수행
                return processOrderBusinessLogic(accountId, request, key);
            })
            // 예외 발생 시 멱등성 키 상태를 'FAIL'로 변경하거나 삭제해야 재시도 가능
            .onFailure().invoke(ex -> idempotencyRepository.markAsFailed(key, ex));
}

private Uni<ServiceResult> processOrderBusinessLogic(long accountId, PlaceOrderRequest req, String key) {
    long orderId = generateOrderId();
    
    // Account 서비스 호출 (gRPC)
    return accountClient.reserveCash(accountId, req.getAmount(), orderId)
            .onItem().transformToUni(accountResult -> {
                if (accountResult.isSuccess()) {
                    // 주문 저장 + 멱등성 상태 업데이트를 '하나의 트랜잭션'으로 처리
                    return orderRepository.saveOrderAndUpdateIdempotency(
                            createOrderEntity(req, orderId),
                            key, 
                            "SUCCESS"
                    ); 
                } else {
                    // 실패 기록
                    return idempotencyRepository.updateStatus(key, "REJECTED");
                }
            });
}
```

### 4\. 추가로 보완해야 할 점 (Hidden Risks)

3000 TPS 환경에서 발생할 수 있는 미묘한 엣지 케이스들입니다.

#### 1\. Zombie Lock (좀비 락) 문제

* **상황:** `INSERT (PROCESSING)` 성공 후, 서버가 갑자기 죽음(Kill).
* **결과:** 해당 키는 영원히 `PROCESSING` 상태. 재시도해도 "진행 중"이라며 막힘.
* **해결:**
    * **TTL(Time To Live):** Redis 사용 시 `EX` 옵션 필수.
    * **DB 사용 시:** `created_at`을 체크하여 "1분 이상 PROCESSING이면 락 탈취 허용" 로직 추가.

#### 2\. Connection Pool 고갈

* 3000 TPS면 DB 커넥션 풀이 순식간에 마를 수 있습니다.
* **해결:** Quarkus Reactive(Non-blocking) 드라이버를 반드시 사용하고, `idempotency` 테이블은 가능한 가볍게 유지하거나, Redis로 offloading 하는 것을 강력 추천합니다.

#### 3\. 트랜잭션 범위 최소화

* `accountClient.reserveCash()` (외부 통신)는 **DB 트랜잭션 밖에서** 실행해야 합니다.
* 트랜잭션을 잡고 외부 통신을 하면, 응답이 늦어질 때 DB 커넥션을 다 잡아먹어 전체 장애가 납니다.
    * *Bad:* Transaction Start -\> Insert Key -\> Call gRPC -\> Insert Order -\> Commit
    * *Good:* Insert Key (Commit) -\> Call gRPC -\> Transaction Start(Insert Order + Update Key) -\> Commit

### 요약: 제 생각은?

> **"체크와 저장을 나누면 망합니다. 무조건 `INSERT`가 먼저(선점)여야 합니다."**

**3000 TPS / 100ms**를 맞추기 위한 저의 추천 구성:

1.  **1차 방어선 (Redis):** `SETNX`로 초고속 중복 필터링 (Latency \< 2ms).
2.  **2차 처리 (DB):** 비즈니스 로직 성공 후, `Order` 테이블과 `Idempotency` 테이블을 한 트랜잭션으로 커밋.
3.  **예외 처리:** 서버 다운 시 Redis TTL(예: 10초) 덕분에 10초 뒤 자동 락 해제되어 재시도 가능.

이 **Redis + DB 하이브리드 방식**이나 **DB Insert First 방식** 중 인프라 상황에 맞는 것을 선택해서 구현해드릴까요? (Redis가 없다면 DB 최적화 쿼리로 짜드립니다.)
**아니요, DB만으로도 충분히 가능합니다.**

오히려 **관리 포인트(Redis)를 늘리지 않고 인프라 복잡도를 낮출 수 있다는 점**에서, DB 스펙(CPU/IOPS)만 받쳐준다면 **"DB Only" 전략이 아키텍처적으로 더 깔끔**할 수 있습니다.

3000 TPS도 최신 RDBMS(PostgreSQL, MySQL 8.0 등)에서는 튜닝만 잘 되어 있다면 충분히 처리 가능한 수치입니다. 다만, \*\*"어떻게 쿼리를 날리느냐"\*\*가 핵심입니다.

DB만으로 3000 TPS/100ms를 달성하기 위한 **"Atomic Insert(선점 잠금)"** 패턴을 구현해 드리겠습니다.

-----

### 핵심 전략: `INSERT`로 락(Lock) 걸기

`SELECT`로 검사하지 않습니다. **DB의 Unique Index 제약조건을 이용해 `INSERT` 자체가 검사이자 락이 되도록** 합니다. 이 방식은 가장 빠르고 강력합니다.

### 1\. DB 테이블 설계 (최적화)

테이블을 최대한 가볍게 가져가야 합니다. 인덱스도 PK 하나만 둡니다.

```sql
CREATE TABLE idempotency_keys (
    idempotency_key VARCHAR(128) NOT NULL, -- 클라이언트가 보낸 UUID
    account_id      BIGINT NOT NULL,
    status          VARCHAR(20) NOT NULL,  -- PROCESSING, SUCCESS, FAILED
    response_json   JSONB,                 -- 성공 시 응답 결과 캐싱
    created_at      TIMESTAMP DEFAULT NOW(),
    PRIMARY KEY (idempotency_key)          -- 🌟 핵심: 유니크 인덱스 역할
);

-- 성능을 위해 created_at 인덱스는 상황 봐서 추가 (삭제 배치용)
```

### 2\. Quarkus(Mutiny) 구현 로직

가장 중요한 건 **트랜잭션 분리**입니다.

1.  **[짧은 트랜잭션]** 키 선점 (`INSERT`)
2.  **[트랜잭션 없음]** 외부 gRPC 호출 (Account Reserve)
3.  **[메인 트랜잭션]** 주문 저장 + 키 상태 업데이트

이 순서를 지켜야 DB 커넥션을 오래 잡지 않아 3000 TPS를 버팁니다.

#### OrderCommandService.java

```java
import io.smallrye.mutiny.Uni;
import io.vertx.pgclient.PgException;

@ApplicationScoped
public class OrderCommandService {

    // 1. [Atomic] 멱등성 키 선점 (별도 트랜잭션 또는 Auto-commit)
    public Uni<ServiceResult> handlePlaceOrder(long accountId, PlaceOrderRequest req) {
        String key = req.getIdempotencyKey();

        // SQL: INSERT INTO idempotency_keys (key, status) VALUES ($1, 'PROCESSING')
        // 이미 존재하면 Exception 발생
        return idempotencyRepo.tryInsertProcessing(key, accountId)
                .onItem().transformToUni(inserted -> {
                    // A. 선점 성공 (Lock 획득) -> 비즈니스 로직 진행
                    return processOrderLogic(accountId, req, key);
                })
                .onFailure(PgException.class).recoverWithUni(ex -> {
                    // B. 선점 실패 (Duplicate Key) -> 이미 진행 중이거나 완료된 요청
                    // 기존 결과 조회해서 리턴
                    return idempotencyRepo.findByKey(key)
                            .map(this::mapToExistingResult);
                });
    }

    // 비즈니스 로직
    private Uni<ServiceResult> processOrderLogic(long accountId, PlaceOrderRequest req, String key) {
        long orderId = generateOrderId();

        // 2. 외부 서비스 호출 (DB 트랜잭션 밖에서 수행해야 함! 중요!)
        return accountClient.reserveCash(accountId, req.getAmount(), orderId)
                .onItem().transformToUni(accountResult -> {
                    if (accountResult.isSuccess()) {
                        // 3. 주문 저장 + 멱등성 완료 처리를 '하나의 트랜잭션'으로 묶음
                        return PgClient.withTransaction(conn -> 
                            orderRepo.saveOrder(conn, createOrder(req, orderId))
                                .chain(() -> idempotencyRepo.updateSuccess(conn, key, "SUCCESS"))
                        ).map(v -> ServiceResult.success(orderId));
                    } else {
                        // 실패 시 멱등성 상태 실패로 업데이트 (재시도 허용할지 결정 필요)
                        return idempotencyRepo.updateStatus(key, "FAILED")
                                .map(v -> ServiceResult.failure("Insufficient Funds"));
                    }
                })
                // 4. 로직 수행 중 에러(서버 다운 등) 발생 시 처리
                // (Note: 서버가 갑자기 꺼지면 이 부분은 실행 안 됨 -> 좀비 레코드 발생 -> 배치로 정리)
                .onFailure().call(ex -> idempotencyRepo.updateStatus(key, "ERROR"));
    }
}
```

-----

### 3\. 성능 확보를 위한 튜닝 포인트 (3000 TPS 대응)

DB만으로 이 성능을 내려면 아래 3가지는 꼭 챙겨야 합니다.

#### 1\. Connection Pool 설정 (HikariCP / Vert.x Pool)

* 3000 TPS라고 해서 커넥션이 3000개 필요한 게 아닙니다. Reactive 방식에서는 적은 수의 커넥션으로 문맥 교환(Context Switch) 없이 처리하는 게 빠릅니다.
* **권장:** DB 코어 수 \* 2 \~ 4 정도 (예: 16코어 DB면 풀 사이즈 32\~64). 너무 많이 잡으면 오히려 느려집니다.

#### 2\. 좀비 레코드 정리 (Delete Batch)

* `PROCESSING` 상태로 `INSERT` 했는데 서버가 죽으면 영원히 잠깁니다.
* **해결:** 별도의 스케줄러가 `created_at < 1분전 AND status = 'PROCESSING'` 인 데이터를 찾아서 삭제하거나 상태를 `FAILED`로 바꾸는 작업이 필요합니다. (1분마다 실행)

#### 3\. 파티셔닝 (Partitioning) - 데이터량 관리

* 3000 TPS면 1시간에 1,000만 건, 하루면 2.5억 건이 쌓입니다. `idempotency_keys` 테이블이 비대해지면 `INSERT` 속도가 느려집니다.
* **필수:** **TTL(Time-To-Live)** 개념이 DB에는 없으므로, **테이블 파티셔닝**을 써야 합니다.
    * 예: `created_at` 기준으로 시간별/일별 파티션 생성.
    * 오래된 파티션은 `DROP TABLE`로 날리면 부하 없이 정리 가능합니다. (DELETE 명령어로 지우면 DB 죽습니다.)

### 결론

**"DB만으로 하셔도 됩니다."**

* **장점:** 아키텍처가 단순함 (Redis 관리 불필요), 데이터 정합성 보장 확실.
* **조건:**
    1.  `SELECT` 후 `INSERT` 금지 → 무조건 **`INSERT` 먼저** (유니크 제약 활용).
    2.  `idempotency_keys` 테이블은 주기적으로 비워줘야 함 (파티셔닝 권장).
    3.  외부 통신(`accountClient`)은 DB 트랜잭션 바깥으로 뺄 것.

이 구조로 가신다면 Redis 없이도 3000 TPS 충분히 방어할 수 있습니다. **이 DB 기반 코드로 구현 예시를 더 자세히 잡아드릴까요?**


