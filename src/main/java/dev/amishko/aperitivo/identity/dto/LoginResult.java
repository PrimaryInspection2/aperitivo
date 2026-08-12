package dev.amishko.aperitivo.identity.dto;

import java.util.UUID;

public record LoginResult(UUID userId, boolean isNewUser) {
}
