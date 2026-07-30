package dev.amishko.aperitivo.identity.events;

import dev.amishko.aperitivo.config.BaseIT;
import dev.amishko.aperitivo.identity.dto.DataProvider;
import dev.amishko.aperitivo.identity.dto.RevocationReason;
import dev.amishko.aperitivo.identity.events.dto.IntegrationRevokedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.test.AssertablePublishedEvents;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the transactional-delivery contract for identity domain events.
 *
 * <p>Production consumers use {@code @ApplicationModuleListener}, which is
 * {@code @Async + @TransactionalEventListener(AFTER_COMMIT) + @Transactional}.
 * These tests exercise the {@code AFTER_COMMIT} half of that contract:
 *
 * <ul>
 *   <li>publish inside a committed transaction → after-commit listener fires;</li>
 *   <li>publish inside a rolled-back transaction → after-commit listener does <b>not</b> fire;</li>
 *   <li>publish outside any transaction → after-commit listener does <b>not</b> fire
 *       (no phase to bind to), but publication is still observable.</li>
 * </ul>
 *
 * <p>{@link AssertablePublishedEvents} (Spring Modulith native) is the control
 * channel: it captures every published event regardless of transaction phase,
 * so it proves publication actually happened. The narrower
 * {@link dev.amishko.aperitivo.config.RecordedEvents#integrationRevokedAfterCommit()}
 * channel proves the tx-phase gate.
 */
class TransactionalEventDeliveryIT extends BaseIT {

    @Autowired
    private ApplicationEventPublisher publisher;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private Clock clock;

    @Test
    @DisplayName("commit → after-commit listener fires exactly once")
    void afterCommitFiresOnCommit(AssertablePublishedEvents events) {
        IntegrationRevokedEvent event = newEvent();

        tx.executeWithoutResult(status -> publisher.publishEvent(event));

        assertThat(events.ofType(IntegrationRevokedEvent.class))
                .as("publication is always observable, regardless of tx phase")
                .containsExactly(event);
        assertThat(recordedEvents.integrationRevokedAfterCommit())
                .as("AFTER_COMMIT listener must fire once the tx commits")
                .containsExactly(event);
    }

    @Test
    @DisplayName("rollback → after-commit listener stays silent; the event was still published")
    void afterCommitDoesNotFireOnRollback(AssertablePublishedEvents events) {
        IntegrationRevokedEvent event = newEvent();

        tx.executeWithoutResult(status -> {
            publisher.publishEvent(event);
            status.setRollbackOnly();
        });

        assertThat(events.ofType(IntegrationRevokedEvent.class))
                .as("publishEvent was called, so the event surfaces on the publication channel")
                .containsExactly(event);
        assertThat(recordedEvents.integrationRevokedAfterCommit())
                .as("AFTER_COMMIT listener must be suppressed when the tx rolls back")
                .isEmpty();
    }

    @Test
    @DisplayName("publish outside a transaction → after-commit listener stays silent")
    void afterCommitDoesNotFireOutsideTransaction(AssertablePublishedEvents events) {
        IntegrationRevokedEvent event = newEvent();

        publisher.publishEvent(event);

        assertThat(events.ofType(IntegrationRevokedEvent.class)).containsExactly(event);
        assertThat(recordedEvents.integrationRevokedAfterCommit())
                .as("no tx → no AFTER_COMMIT phase → listener must not fire")
                .isEmpty();
    }

    private IntegrationRevokedEvent newEvent() {
        return new IntegrationRevokedEvent(
                UUID.randomUUID(),
                UUID.randomUUID(),
                DataProvider.STRAVA,
                RevocationReason.USER_REQUESTED,
                clock.instant());
    }
}
