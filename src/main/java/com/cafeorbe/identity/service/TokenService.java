package com.cafeorbe.identity.service;

import com.cafeorbe.contracts.Rol;
import com.cafeorbe.identity.model.Usuario;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/**
 * Emite el token de sesión (JWT HS256) con id, nombre, rol y un identificador único de sesión ({@code jti}).
 * Lo validan el api-gateway y el realtime-gateway; el {@code jti} es lo que se anota al cerrar sesión (SEG-05).
 */
@Service
public class TokenService {

    /** Lo que trae un token de sesión válido. */
    public record SesionToken(String jti, UUID usuarioId, String nombre, Rol rol, Instant expiraEn) {
    }

    private final SecretKey clave;
    private final Duration vida;

    public TokenService(@Value("${cafeorbe.jwt.secret}") String secreto,
                        @Value("${cafeorbe.jwt.horas-de-vida}") long horas) {
        this.clave = Keys.hmacShaKeyFor(secreto.getBytes(StandardCharsets.UTF_8));
        this.vida = Duration.ofHours(horas);
    }

    public String emitir(Usuario usuario) {
        Instant ahora = Instant.now();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(usuario.getId().toString())
                .claim("nombre", usuario.getNombre())
                .claim("rol", usuario.getRol().name())
                .issuedAt(Date.from(ahora))
                .expiration(Date.from(ahora.plus(vida)))
                .signWith(clave)
                .compact();
    }

    /** Valida firma y vencimiento. Vacío si el token no es válido o le falta el {@code jti}. */
    public Optional<SesionToken> leer(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Claims c = Jwts.parser().verifyWith(clave).build().parseSignedClaims(token.trim()).getPayload();
            if (c.getId() == null || c.getId().isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new SesionToken(c.getId(), UUID.fromString(c.getSubject()), c.get("nombre", String.class),
                    Rol.valueOf(c.get("rol", String.class)), c.getExpiration().toInstant()));
        } catch (JwtException | IllegalArgumentException | NullPointerException e) {
            return Optional.empty();
        }
    }
}
