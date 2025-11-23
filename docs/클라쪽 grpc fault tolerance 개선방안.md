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

