package dev.amishko.aperitivo.identity.config;

import jakarta.servlet.DispatcherType;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

import static org.springframework.security.config.http.SessionCreationPolicy.STATELESS;

@Configuration
@RequiredArgsConstructor
public class OAuth2SecurityConfig {

    // Unauthenticated infra endpoints. Actuator is open here by design: it is
    // network-restricted at the edge, not by Spring Security (docs/operations/security.md).
    private static final String[] PUBLIC_INFRA_PATTERNS = {
            "/.well-known/**",
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/v3/api-docs/**",
            "/v3/api-docs.yaml",
            "/actuator/health/**",
            "/actuator/prometheus"
    };

    @Bean
    @Order(1)
    SecurityFilterChain apiChain(HttpSecurity http, JwtDecoder jwtDecoder) {
        http.securityMatcher("/api/**")
                .authorizeHttpRequests(a -> a.anyRequest().authenticated())
                .sessionManagement(session -> session.sessionCreationPolicy(STATELESS))
                //.userDetailsService(userDetailsService) // No authentication manager needed for JWT?
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt
                                .decoder(jwtDecoder)))
                .csrf(AbstractHttpConfigurer::disable); //check against refresh token cookie
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain fallbackChain(HttpSecurity http) {
        http.authorizeHttpRequests(requests -> requests
                // The container's ERROR dispatch to /error is re-authorized too; without this
                // a 404/500 of a permitted endpoint is masked as 403.
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers(PUBLIC_INFRA_PATTERNS).permitAll()
                .anyRequest().denyAll());
        return http.build();
    }

}