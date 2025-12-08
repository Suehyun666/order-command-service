package com.hts.order.infrastructure.event;

import com.hts.generated.events.order.OrderFillEvent;
import com.hts.order.infrastructure.repository.OrderUpdateRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.jboss.logging.Logger;

import java.util.concurrent.CompletionStage;

@ApplicationScoped
public class OrderFillEventConsumer {

    private static final Logger LOG = Logger.getLogger(OrderFillEventConsumer.class);

    @Inject OrderUpdateRepository updateRepo;

    @Incoming("order-filled-events")
    public CompletionStage<Void> onOrderFilled(Message<byte[]> message) {
        return Uni.createFrom().item(() -> {
            try {
                OrderFillEvent event = OrderFillEvent.parseFrom(message.getPayload());

                LOG.infof("Received OrderFillEvent: eventId=%s, clientOrderId=%s, accountId=%d",
                        event.getEventId(), event.getClientOrderId(), event.getAccountId());

                return event;
            } catch (Exception e) {
                LOG.errorf(e, "Failed to parse OrderFillEvent");
                throw new RuntimeException(e);
            }
        })
        .chain(event -> updateRepo.updateOrderToFilled(event))
        .onItem().transform(result -> {
            if (!result) {
                LOG.warnf("Order not found or already filled: clientOrderId=%s",
                    message.getMetadata().toString());
            }
            return null;
        })
        .chain(() -> Uni.createFrom().completionStage(message.ack()))
        .onFailure().recoverWithUni(err -> {
            LOG.errorf(err, "Failed to process OrderFillEvent");
            return Uni.createFrom().completionStage(message.nack(err));
        })
        .subscribeAsCompletionStage();
    }
}
