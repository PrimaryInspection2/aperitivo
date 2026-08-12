package dev.amishko.aperitivo.identity.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

import static org.springframework.security.config.Customizer.withDefaults;
import static org.springframework.security.config.http.SessionCreationPolicy.STATELESS;

@Configuration
public class OAuth2SecurityConfig {

    private static final String CONNECT_AUTHORIZATION_BASE_URI = "/connect";

    @Bean
    @Order(1)
    SecurityFilterChain apiChain(HttpSecurity http) {
        http.securityMatcher("/api/**")
                .authorizeHttpRequests(a -> a.anyRequest().authenticated())
                .sessionManagement(s -> s.sessionCreationPolicy(STATELESS))
                .csrf(c -> c.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()));
               /* .oauth2ResourceServer(rs -> rs
                        .bearerTokenResolver(new DefaultBearerTokenResolver()) //fixme create custom BearerTokenResolver
                        .jwt(withDefaults())
                );*/
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain browserChain(HttpSecurity http,
                                     ClientRegistrationRepository clientRegistrationRepository,
                                     LoginProperties loginProperties,
                                     AuthenticationSuccessHandler successHandler,
                                     AuthenticationFailureHandler failureHandler) {
        http.authorizeHttpRequests(a -> a
                        .requestMatchers("/", "/login", "/error", "/.well-known/jwks.json").permitAll()
                        .anyRequest().authenticated())
                .oauth2Login(o -> o
                        .loginPage(loginProperties.authorizationRequestUri())
                        .successHandler(successHandler)
                        .failureHandler(failureHandler))
                .oauth2Client(c -> c.authorizationCodeGrant(g -> g
                        .authorizationRequestResolver(new DefaultOAuth2AuthorizationRequestResolver(
                                clientRegistrationRepository, CONNECT_AUTHORIZATION_BASE_URI))))
                .csrf(withDefaults());
        return http.build();
    }
}
