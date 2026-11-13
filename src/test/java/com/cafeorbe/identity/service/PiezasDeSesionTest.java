package com.cafeorbe.identity.service;

import com.cafeorbe.contracts.Rol;
import com.cafeorbe.identity.model.Usuario;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PiezasDeSesionTest {

    static final String SECRETO = "cafeorbe-dev-jwt-secret-cambiar-en-prod-0123456789";

    @Test
    @DisplayName("SEG-05 · Cada token lleva un jti distinto y se puede leer de vuelta")
    void tokenConJti() {
        var tokens = new TokenService(SECRETO, 8);
        var usuario = new Usuario("Ana", Rol.COMPRADOR);
        var uno = tokens.leer(tokens.emitir(usuario)).orElseThrow();
        var otro = tokens.leer(tokens.emitir(usuario)).orElseThrow();
        assertThat(uno.jti()).isNotEqualTo(otro.jti());
        assertThat(uno.usuarioId()).isEqualTo(usuario.getId());
        assertThat(uno.rol()).isEqualTo(Rol.COMPRADOR);
    }

    @Test
    @DisplayName("Un token sin jti (Sprint 1), vacío o con otra firma no se acepta")
    void tokenSinJti() {
        var tokens = new TokenService(SECRETO, 8);
        String sinJti = Jwts.builder().subject("00000000-0000-0000-0000-000000000001").claim("nombre", "A")
                .claim("rol", "COMPRADOR").signWith(Keys.hmacShaKeyFor(SECRETO.getBytes(StandardCharsets.UTF_8)))
                .compact();
        assertThat(tokens.leer(sinJti)).isEmpty();
        assertThat(tokens.leer(null)).isEmpty();
        assertThat(tokens.leer(" ")).isEmpty();
        assertThat(new TokenService(SECRETO + "otro", 8).leer(tokens.emitir(new Usuario("A", Rol.COMPRADOR)))).isEmpty();
    }

    @Test
    @DisplayName("SEG-04/05 · En Redis: sesión cerrada y ticket, ambos con TTL")
    @SuppressWarnings("unchecked")
    void almacenRedis() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valores = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valores);
        when(redis.hasKey("cafeorbe:sesion:cerrada:j1")).thenReturn(true);
        var almacen = new AlmacenDeSesionesRedis(redis);

        almacen.marcarCerrada("j1", Duration.ofMinutes(5));
        almacen.guardarTicket("t1", "{}", Duration.ofSeconds(30));

        verify(valores).set("cafeorbe:sesion:cerrada:j1", "1", Duration.ofMinutes(5));
        verify(valores).set("cafeorbe:ws:ticket:t1", "{}", Duration.ofSeconds(30));
        assertThat(almacen.estaCerrada("j1")).isTrue();
        assertThat(almacen.estaCerrada("j2")).isFalse();
    }

    @Test
    @DisplayName("SEG-01 · Sin Client ID configurado nadie entra con Google")
    void googleSinConfigurar() {
        var verificador = new VerificadorGoogle(mock(JwtDecoder.class), " ");
        assertThatThrownBy(() -> verificador.verificar("x"))
                .isInstanceOfSatisfying(VerificadorGoogle.TokenGoogleInvalidoException.class,
                        e -> assertThat(e.motivo()).isEqualTo("google_no_configurado"));
        var configurado = new VerificadorGoogle(mock(JwtDecoder.class), "a");
        assertThatThrownBy(() -> configurado.verificar(" "))
                .isInstanceOfSatisfying(VerificadorGoogle.TokenGoogleInvalidoException.class,
                        e -> assertThat(e.motivo()).isEqualTo("token_vacio"));
    }
}
