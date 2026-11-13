package com.cafeorbe.identity.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * SEG-01: verifica el ID token que Google entrega al navegador. Comprueba:
 * <ol>
 *   <li>la firma, con las claves públicas de Google (JWKS); el decodificador también exige {@code exp} y {@code nbf};</li>
 *   <li>el emisor ({@code iss}), que debe ser Google;</li>
 *   <li>la audiencia ({@code aud}), que debe ser el Client ID de CaféOrbe: un token emitido para otra
 *       aplicación no sirve aquí aunque su firma sea válida;</li>
 *   <li>que el correo esté verificado por Google.</li>
 * </ol>
 */
@Component
public class VerificadorGoogle {

    /** Cuenta de Google ya verificada. {@code sub} es el identificador estable de la cuenta. */
    public record CuentaGoogle(String sub, String email, String nombre) {
    }

    /** El ID token no es válido. El motivo va al registro de auditoría, nunca al cliente. */
    public static class TokenGoogleInvalidoException extends RuntimeException {
        private final String motivo;

        public TokenGoogleInvalidoException(String motivo) {
            super("ID token de Google rechazado: " + motivo);
            this.motivo = motivo;
        }

        public String motivo() {
            return motivo;
        }
    }

    private static final Set<String> EMISORES = Set.of("accounts.google.com", "https://accounts.google.com");

    private final JwtDecoder decodificador;
    private final List<String> clientIds;

    public VerificadorGoogle(JwtDecoder decodificador, @Value("${cafeorbe.google.client-ids:}") String clientIds) {
        this.decodificador = decodificador;
        this.clientIds = Arrays.stream(clientIds.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    public CuentaGoogle verificar(String idToken) {
        if (clientIds.isEmpty()) {
            throw new TokenGoogleInvalidoException("google_no_configurado");
        }
        if (idToken == null || idToken.isBlank()) {
            throw new TokenGoogleInvalidoException("token_vacio");
        }
        Jwt jwt;
        try {
            jwt = decodificador.decode(idToken.trim());
        } catch (JwtException e) {
            String mensaje = String.valueOf(e.getMessage()).toLowerCase();
            throw new TokenGoogleInvalidoException(mensaje.contains("expired") ? "token_vencido" : "firma_o_formato_invalido");
        }
        String emisor = jwt.getClaimAsString("iss");
        if (emisor == null || !EMISORES.contains(emisor)) {
            throw new TokenGoogleInvalidoException("emisor_invalido");
        }
        List<String> audiencia = jwt.getAudience();
        if (audiencia == null || audiencia.stream().noneMatch(clientIds::contains)) {
            throw new TokenGoogleInvalidoException("audiencia_invalida");
        }
        if (!Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified"))) {
            throw new TokenGoogleInvalidoException("correo_no_verificado");
        }
        String sub = jwt.getSubject();
        if (sub == null || sub.isBlank()) {
            throw new TokenGoogleInvalidoException("sin_sujeto");
        }
        String email = jwt.getClaimAsString("email");
        return new CuentaGoogle(sub, email, nombreVisible(jwt, email));
    }

    private static String nombreVisible(Jwt jwt, String email) {
        String nombre = jwt.getClaimAsString("name");
        if (nombre == null || nombre.isBlank()) {
            nombre = jwt.getClaimAsString("given_name");
        }
        if ((nombre == null || nombre.isBlank()) && email != null) {
            nombre = email.substring(0, Math.max(0, email.indexOf('@')));
        }
        if (nombre == null || nombre.isBlank()) {
            nombre = "Usuario";
        }
        nombre = nombre.trim();
        return nombre.length() > 50 ? nombre.substring(0, 50) : nombre;
    }
}
