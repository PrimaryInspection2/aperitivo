package dev.amishko.aperitivo.identity.config;

import jakarta.servlet.DispatcherType;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;

import static org.springframework.security.config.http.SessionCreationPolicy.STATELESS;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

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
    SecurityFilterChain apiChain(HttpSecurity http, JwtDecoder jwtDecoder, AuthenticationEntryPoint authenticationEntryPoint) {
            http
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/api/**").authenticated()
                        .requestMatchers(PUBLIC_INFRA_PATTERNS).permitAll()
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .anyRequest().denyAll())
                .sessionManagement(session -> session.sessionCreationPolicy(STATELESS))
                //.userDetailsService(userDetailsService) // No authentication manager needed for JWT?
                .oauth2ResourceServer(oauth2 -> oauth2
                        .accessDeniedHandler()
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .jwt(jwt -> jwt
                                .decoder(jwtDecoder)))
                .csrf(AbstractHttpConfigurer::disable); //check against refresh token cookie
        return http.build();
    }


}