package dev.amishko.aperitivo.identity.web.controller;

import dev.amishko.aperitivo.identity.CurrentUser;
import dev.amishko.aperitivo.identity.service.strava.StravaCodeExchangeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/connect")
public class ConnectedSourceController {

    private final StravaCodeExchangeService codeExchangeService;

    @PostMapping("/strava")
    public ResponseEntity<String> connectStrava(@AuthenticationPrincipal CurrentUser currentUser) {

        codeExchangeService.exchangeToken(currentUser.userUuid(), "123dasd");
        return ResponseEntity.ok("");
    }
}
