package dev.amishko.aperitivo.identity.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Slf4j
public class OAuth2ClientController {

    @GetMapping("/")
    public ResponseEntity<String> index(Authentication authentication) {

        log.info("Authentication: {}", authentication);

        return ResponseEntity.ok("Authentication: " + authentication);
    }
}