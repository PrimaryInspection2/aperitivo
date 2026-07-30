package dev.amishko.aperitivo.identity.dto.tokens;

import lombok.ToString;

import java.time.Instant;

public record RefreshTokenDto(@ToString.Exclude String token, Instant expiresAt) {
}
