package com.cafeorbe.identity;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.function.Consumer;

/**
 * Hace las veces de Google en las pruebas: firma ID tokens con una clave RSA propia y el decodificador de la
 * aplicación confía en ella. Así se prueba la verificación real (firma, vencimiento, emisor y audiencia)
 * sin salir a Internet.
 */
public final class GoogleDePrueba {

    public static final String CLIENT_ID = "cliente-cafeorbe.apps.googleusercontent.com";
    public static final KeyPair CLAVES = generar();
    /** Una clave que Google nunca usó: firma un token "falso". */
    public static final KeyPair CLAVES_DEL_ATACANTE = generar();

    private GoogleDePrueba() {
    }

    private static KeyPair generar() {
        try {
            var generador = KeyPairGenerator.getInstance("RSA");
            generador.initialize(2048);
            return generador.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static JWTClaimsSet.Builder reclamosValidos(String sub) {
        Instant ahora = Instant.now();
        return new JWTClaimsSet.Builder()
                .issuer("https://accounts.google.com")
                .audience(CLIENT_ID)
                .subject(sub)
                .claim("email", sub + "@gmail.com")
                .claim("email_verified", true)
                .claim("name", "Persona " + sub)
                .issueTime(Date.from(ahora))
                .expirationTime(Date.from(ahora.plusSeconds(3600)));
    }

    public static String idToken(String sub) {
        return firmar(reclamosValidos(sub).build(), CLAVES);
    }

    public static String idToken(String sub, Consumer<JWTClaimsSet.Builder> cambio) {
        var reclamos = reclamosValidos(sub);
        cambio.accept(reclamos);
        return firmar(reclamos.build(), CLAVES);
    }

    public static String firmar(JWTClaimsSet reclamos, KeyPair claves) {
        try {
            var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), reclamos);
            jwt.sign(new RSASSASigner(claves.getPrivate()));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    @TestConfiguration
    public static class Config {
        @Bean
        @Primary
        JwtDecoder decodificadorDePrueba() {
            return NimbusJwtDecoder.withPublicKey((RSAPublicKey) CLAVES.getPublic()).build();
        }
    }
}
