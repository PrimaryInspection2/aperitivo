package dev.amishko.aperitivo.config;

import dev.amishko.aperitivo.identity.events.dto.IntegrationRevokedEvent;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Captures identity domain events on the {@link TransactionPhase#AFTER_COMMIT}
 * phase — the same phase that real production consumers (e.g.
 * {@code @ApplicationModuleListener} in Notifications) will bind to.
 *
 * <p>This is deliberately narrow. For "was the event ever published?"
 * questions, integration tests inject Modulith's native
 * {@link org.springframework.modulith.test.AssertablePublishedEvents} as a
 * method parameter — that captures every publish regardless of transaction
 * phase and offers a fluent AssertJ API.
 *
 * <p>This helper answers the strictly narrower question: "did the publishing
 * transaction actually commit?". A rolled-back tx silently drops the
 * after-commit callback; a committed one fires it. That distinction is the
 * only reason this bean exists — the tx-phase-blind
 * {@code AssertablePublishedEvents} cannot verify it.
 */
@TestComponent
public class RecordedEvents {

    private final List<IntegrationRevokedEvent> integrationRevokedAfterCommit = new CopyOnWriteArrayList<>();

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onIntegrationRevokedAfterCommit(IntegrationRevokedEvent event) {
        integrationRevokedAfterCommit.add(event);
    }

    public List<IntegrationRevokedEvent> integrationRevokedAfterCommit() {
        return List.copyOf(integrationRevokedAfterCommit);
    }

    public void clear() {
        integrationRevokedAfterCommit.clear();
    }
}
