package dev.amishko.aperitivo.identity.web;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController("/api/me")
@Slf4j
@RequiredArgsConstructor
public class UserProfileController {

    @GetMapping
    public ResponseEntity<String> index(@PathVariable UUID userUuid) {
        return ResponseEntity.ok("Hello " + userUuid);
    }
}