package com.hts.order.api.grpc;

import com.hts.generated.grpc.internal.MutinyAuthInternalServiceGrpc;
import com.hts.generated.grpc.internal.ValidateSessionRequest;
import io.grpc.*;
import io.quarkus.grpc.GrpcClient;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jboss.logging.Logger;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Singleton
public class AuthInterceptor implements ServerInterceptor {

    private static final Logger LOG = Logger.getLogger(AuthInterceptor.class);

    @Inject
    @GrpcClient("auth-command-service")
    MutinyAuthInternalServiceGrpc.MutinyAuthInternalServiceStub authServiceStub;

    @Inject Vertx vertx;

    private static final Metadata.Key<String> SESSION_ID_KEY =
            Metadata.Key.of("session-id", Metadata.ASCII_STRING_MARSHALLER);

    public static final io.grpc.Context.Key<Long> ACCOUNT_ID_CONTEXT_KEY = io.grpc.Context.key("accountId");

    private static final int AUTH_TIMEOUT_MS = 500;

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {

        String sessionId = headers.get(SESSION_ID_KEY);
        if (sessionId == null || sessionId.isEmpty()) {
            call.close(Status.UNAUTHENTICATED.withDescription("Session ID missing"), new Metadata());
            return new ServerCall.Listener<>() {};
        }

        CompletableFuture<Long> authFuture = new CompletableFuture<>();
        Context currentContext = Vertx.currentContext();

        ValidateSessionRequest request = ValidateSessionRequest.newBuilder()
                .setSessionId(sessionId)
                .build();

        authServiceStub.validateSession(request)
                .ifNoItem().after(java.time.Duration.ofMillis(AUTH_TIMEOUT_MS)).fail()
                .subscribe().with(
                        response -> {
                            if (response.getIsValid() && response.getAccountId() > 0) {
                                authFuture.complete(response.getAccountId());
                            } else {
                                authFuture.completeExceptionally(
                                        new StatusException(Status.UNAUTHENTICATED.withDescription("Invalid session"))
                                );
                            }
                        },
                        failure -> {
                            LOG.warnf(failure, "Auth validation failed for session: %s", sessionId);
                            authFuture.completeExceptionally(
                                    new StatusException(Status.INTERNAL.withDescription("Auth service unavailable"))
                            );
                        }
                );

        ServerCall.Listener<ReqT> originalListener = next.startCall(call, headers);

        return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(originalListener) {

            @Override
            public void onHalfClose() {
                try {
                    Long accountId = authFuture.get(AUTH_TIMEOUT_MS, TimeUnit.MILLISECONDS);

                    io.grpc.Context ctx = io.grpc.Context.current().withValue(ACCOUNT_ID_CONTEXT_KEY, accountId);

                    io.grpc.Context previous = ctx.attach();
                    try {
                        if (currentContext != null) {
                            currentContext.runOnContext(v -> super.onHalfClose());
                        } else {
                            super.onHalfClose();
                        }
                    } finally {
                        ctx.detach(previous);
                    }

                } catch (java.util.concurrent.TimeoutException e) {
                    LOG.warnf("Auth timeout for session: %s", sessionId);
                    call.close(Status.DEADLINE_EXCEEDED.withDescription("Auth timeout"), new Metadata());
                } catch (java.util.concurrent.ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof StatusException) {
                        StatusException se = (StatusException) cause;
                        call.close(se.getStatus(), new Metadata());
                    } else {
                        LOG.errorf(e, "Auth execution error for session: %s", sessionId);
                        call.close(Status.INTERNAL.withDescription("Auth error"), new Metadata());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    call.close(Status.CANCELLED.withDescription("Auth interrupted"), new Metadata());
                }
            }

            @Override
            public void onCancel() {
                authFuture.cancel(true);
                super.onCancel();
            }
        };
    }
}
