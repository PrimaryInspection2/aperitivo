package dev.amishko.aperitivo.identity.events.dto;

import dev.amishko.aperitivo.identity.dto.DataProvider;
import dev.amishko.aperitivo.identity.dto.RevocationReason;

import java.time.Instant;
import java.util.UUID;

public record IntegrationRevokedEvent(
        UUID userId,
        UUID connectedSourceId,
        DataProvider provider,
        RevocationReason reason,
        Instant occurredAt
) {
}