package dev.amishko.aperitivo.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;

@ConfigurationProperties(prefix = "aperitivo.identity.login")
public record LoginProperties(String registrationId) {

    public LoginProperties {
        if (registrationId == null || registrationId.isBlank()) {
            throw new IllegalArgumentException(
                    "aperitivo.identity.login.registration-id must be non-blank");
        }
    }

    public String authorizationRequestUri() {
        return OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI
                + "/" + registrationId;
    }
}
