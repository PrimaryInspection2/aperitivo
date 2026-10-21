package dev.amishko.aperitivo.identity.web;

import dev.amishko.aperitivo.identity.dto.tokens.AccessTokenDto;
import dev.amishko.aperitivo.identity.security.AccessTokenIssuer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@Slf4j
public class OAuth2ClientController {

    @Autowired
    private AccessTokenIssuer accessTokenIssuer;

    @GetMapping("/api")
    public ResponseEntity<AccessTokenDto> index(Authentication authentication) {

        log.info("Authentication: {}", authentication);
        AccessTokenDto accessTokenDto = accessTokenIssuer.issueAccessToken(UUID.randomUUID());

        return ResponseEntity.ok(accessTokenDto);
    }
}