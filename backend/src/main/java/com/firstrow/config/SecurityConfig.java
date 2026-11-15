package com.firstrow.config;

import com.firstrow.auth.TokenService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Every HTTP request passes through Spring Security's filter chain before reaching a controller.
 * For a request with "Authorization: Bearer <jwt>", the BearerTokenAuthenticationFilter:
 *   1. extracts the token,  2. verifies signature/issuer/expiry with the JwtDecoder (JwtConfig),
 *   3. turns its claims into an Authentication (user id + ROLE_xxx) and stores it in the SecurityContext.
 * Then the authorization rules below decide: allowed, 401 (not logged in) or 403 (wrong role).
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ProblemDetailSecurityHandlers problemHandlers)
            throws Exception {
        http
                // CSRF attacks rely on the browser auto-sending cookies. We use no cookies or sessions:
                // the token is sent explicitly in a header, so CSRF protection isn't needed.
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                // Stateless: no HTTP session; every request carries its own token.
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/signup", "/api/v1/auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/events", "/api/v1/events/**").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/error").permitAll()
                        // Deny-by-default: anything not listed above needs a valid token.
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(problemHandlers)
                        .accessDeniedHandler(problemHandlers))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(problemHandlers)
                        .accessDeniedHandler(problemHandlers));
        return http.build();
    }

    /** Maps our token's "role" claim (e.g. "ADMIN") to the authority "ROLE_ADMIN" that hasRole() checks. */
    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(TokenService.ROLE_CLAIM);
        authorities.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    /** BCrypt: slow on purpose and salted, so a leaked database can't be cracked quickly. */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * CORS: browsers block a page on one origin (the Vercel frontend) from calling an API on another
     * origin (Render) unless the API says it's allowed. Allowed origins come from ALLOWED_ORIGINS.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource(FirstRowProperties props) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(props.cors().allowedOrigins().stream().map(String::trim).toList());
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "X-Request-Id"));
        cors.setExposedHeaders(List.of("X-Request-Id", "Location", "Retry-After", "Idempotent-Replayed"));
        cors.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
    }
}
