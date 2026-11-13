package com.cafeorbe.identity.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

@Configuration
public class GoogleConfig {

    /**
     * Decodificador del ID token de Google: descarga y cachea las claves públicas de Google (JWKS) y valida la
     * firma RS256, {@code exp} y {@code nbf}. El emisor y la audiencia los comprueba VerificadorGoogle.
     */
    @Bean
    JwtDecoder decodificadorGoogle(@Value("${cafeorbe.google.jwks-uri}") String jwksUri) {
        return NimbusJwtDecoder.withJwkSetUri(jwksUri).build();
    }
}
