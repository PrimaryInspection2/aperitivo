package dev.amishko.aperitivo.identity.web.controller;

import dev.amishko.aperitivo.identity.CurrentUser;
import dev.amishko.aperitivo.identity.service.auth.LogoutService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

import static dev.amishko.aperitivo.identity.web.util.RefreshCookieUtil.buildRefreshCookie;

@RestController
@Slf4j
@RequiredArgsConstructor
@RequestMapping(value = "/api/me")
public class UserProfileController {

    private final LogoutService logoutService;

    @GetMapping
    public ResponseEntity<CurrentUser> getUserProfile(@AuthenticationPrincipal CurrentUser currentUser) {
        return ResponseEntity.ok(currentUser);
    }

    @PostMapping("/sessions/revoke-all")
    public ResponseEntity<Void> logoutFromAllDevices(@AuthenticationPrincipal CurrentUser currentUser) {
        logoutService.logoutFromAllDevices(currentUser.userUuid());

        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, buildRefreshCookie(null, Duration.ZERO).toString())
                .cacheControl(CacheControl.noStore())
                .build();
    }
}