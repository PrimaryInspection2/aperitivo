package dev.amishko.aperitivo.identity.web;

import dev.amishko.aperitivo.identity.CurrentUser;
import dev.amishko.aperitivo.identity.security.CurrentUserAuthenticationToken;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Slf4j
@RequiredArgsConstructor
public class UserProfileController {

    @GetMapping("/api/me")
    public ResponseEntity<String> getUserProfile(@ParamCurrentUserAuthenticationToken currentUserAuthenticationToken) {
        return ResponseEntity.ok("Hello");
    }
}