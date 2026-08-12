package dev.amishko.aperitivo.identity.dto;

public record ProviderIdentityProfile(IdentityProvider provider, String providerUserId,
                                      String email, String displayName, String avatarUrl) {
}
