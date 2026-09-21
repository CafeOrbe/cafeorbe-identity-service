package com.cafeorbe.identity.service;

import com.cafeorbe.identity.model.Usuario;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/** Emite el token de sesión (JWT HS256) con id, nombre y rol. Lo validan el api-gateway y el realtime-gateway. */
@Service
public class TokenService {

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
                .subject(usuario.getId().toString())
                .claim("nombre", usuario.getNombre())
                .claim("rol", usuario.getRol().name())
                .issuedAt(Date.from(ahora))
                .expiration(Date.from(ahora.plus(vida)))
                .signWith(clave)
                .compact();
    }
}
