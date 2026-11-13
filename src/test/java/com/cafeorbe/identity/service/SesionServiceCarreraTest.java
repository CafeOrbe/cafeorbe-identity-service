package com.cafeorbe.identity.service;

import com.cafeorbe.contracts.Eventos;
import com.cafeorbe.contracts.Rol;
import com.cafeorbe.identity.model.Usuario;
import com.cafeorbe.identity.repository.UsuarioRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.DataIntegrityViolationException;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Dos ingresos simultáneos con la misma cuenta de Google y rol compiten por el mismo INSERT: la base resuelve la
 * carrera con una violación de unicidad. El que pierde debe re-leer al ganador y emitirle un token, no
 * fallar. Y como el ganador ya publicó su UsuarioRegistrado, el perdedor no debe volver a publicarlo:
 * wallet es idempotente, pero el punto es no duplicar trabajo ni el evento.
 */
class SesionServiceCarreraTest {

    static final String SECRETO = "cafeorbe-dev-jwt-secret-cambiar-en-prod-0123456789";

    private final UsuarioRepository usuarios = mock(UsuarioRepository.class);
    private final RabbitTemplate rabbit = mock(RabbitTemplate.class);
    private final TokenService tokens = new TokenService(SECRETO, 8);
    private final SesionService servicio = new SesionService(usuarios, tokens, rabbit,
            new AlmacenDeSesionesEnMemoria(), new com.fasterxml.jackson.databind.ObjectMapper(), 30);

    private static Usuario ganadorConEventoYaPublicado(String nombre, Rol rol) {
        var usuario = Usuario.deGoogle(sub(nombre), sub(nombre) + "@gmail.com", nombre, rol);
        usuario.marcarEventoPublicado();
        return usuario;
    }

    /** La cuenta de Google de cada persona de prueba: su sub es el nombre sin espacios ni mayúsculas. */
    private static String sub(String nombre) {
        return nombre.trim().toLowerCase(java.util.Locale.ROOT).replace(' ', '-');
    }

    private static String clave(String nombre) {
        return Usuario.claveGoogle(sub(nombre));
    }

    private SesionService.Sesion entrar(String nombre, Rol rol) {
        return servicio.iniciarSesionConGoogle(
                new VerificadorGoogle.CuentaGoogle(sub(nombre), sub(nombre) + "@gmail.com", nombre), rol);
    }

    @Test
    @DisplayName("Carrera del INSERT: el segundo ingreso reutiliza al ganador y no se marca como nuevo")
    void elPerdedorReutilizaAlGanador() {
        var ganador = ganadorConEventoYaPublicado("Ana", Rol.COMPRADOR);
        when(usuarios.findByNombreNormalizadoAndRol(clave("Ana"), Rol.COMPRADOR))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(ganador));
        when(usuarios.saveAndFlush(any(Usuario.class)))
                .thenThrow(new DataIntegrityViolationException("violación de unicidad"));

        var sesion = entrar("Ana", Rol.COMPRADOR);

        assertThat(sesion.nuevo()).isFalse();
        assertThat(sesion.usuario()).isSameAs(ganador);
        assertThat(sesion.token()).isNotBlank();
    }

    @Test
    @DisplayName("Carrera · El perdedor no vuelve a publicar UsuarioRegistrado: ya lo publicó el ganador")
    void elPerdedorNoRepublicaElEvento() {
        var ganador = ganadorConEventoYaPublicado("Ana", Rol.COMPRADOR);
        when(usuarios.findByNombreNormalizadoAndRol(clave("Ana"), Rol.COMPRADOR))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(ganador));
        when(usuarios.saveAndFlush(any(Usuario.class)))
                .thenThrow(new DataIntegrityViolationException("violación de unicidad"));

        entrar("Ana", Rol.COMPRADOR);

        verify(rabbit, never()).convertAndSend(anyString(), anyString(), any(Object.class));
    }

    @Test
    @DisplayName("Carrera · Si tras la violación tampoco aparece el ganador, el error original se propaga")
    void sinGanadorVisibleSePropagaElError() {
        when(usuarios.findByNombreNormalizadoAndRol(clave("Ana"), Rol.COMPRADOR))
                .thenReturn(Optional.empty());
        when(usuarios.saveAndFlush(any(Usuario.class)))
                .thenThrow(new DataIntegrityViolationException("violación de unicidad"));

        assertThatThrownBy(() -> entrar("Ana", Rol.COMPRADOR))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Primer ingreso: registra el usuario, marca nuevo y publica UsuarioRegistrado")
    void primerIngresoRegistraYPublica() {
        when(usuarios.findByNombreNormalizadoAndRol(clave("Ana"), Rol.COMPRADOR))
                .thenReturn(Optional.empty());
        when(usuarios.saveAndFlush(any(Usuario.class))).thenAnswer(i -> i.getArgument(0));

        var sesion = entrar("Ana", Rol.COMPRADOR);

        assertThat(sesion.nuevo()).isTrue();
        assertThat(sesion.usuario().isEventoPublicado()).isTrue();
        verify(rabbit).convertAndSend(eq(Eventos.EXCHANGE), eq(Eventos.USUARIO_REGISTRADO), any(Object.class));
    }

    @Test
    @DisplayName("Si el broker está caído, el primer ingreso no falla: el evento queda pendiente de reintento")
    void brokerCaidoNoImpideCrearLaSesion() {
        when(usuarios.findByNombreNormalizadoAndRol(clave("Ana"), Rol.COMPRADOR))
                .thenReturn(Optional.empty());
        when(usuarios.saveAndFlush(any(Usuario.class))).thenAnswer(i -> i.getArgument(0));
        org.mockito.Mockito.doThrow(new org.springframework.amqp.AmqpConnectException(new RuntimeException("caído")))
                .when(rabbit).convertAndSend(anyString(), anyString(), any(Object.class));

        var sesion = entrar("Ana", Rol.COMPRADOR);

        assertThat(sesion.token()).isNotBlank();
        assertThat(sesion.usuario().isEventoPublicado()).isFalse();
    }

    @Test
    @DisplayName("Un ingreso posterior solo emite token: ni crea usuario ni publica evento")
    void ingresoPosteriorSoloEmiteToken() {
        var existente = ganadorConEventoYaPublicado("Ana", Rol.COMPRADOR);
        when(usuarios.findByNombreNormalizadoAndRol(clave("Ana"), Rol.COMPRADOR))
                .thenReturn(Optional.of(existente));

        var sesion = entrar("Ana", Rol.COMPRADOR);

        assertThat(sesion.nuevo()).isFalse();
        assertThat(sesion.usuario()).isSameAs(existente);
        verify(usuarios, never()).saveAndFlush(any(Usuario.class));
        verify(rabbit, never()).convertAndSend(anyString(), anyString(), any(Object.class));
    }

    @Test
    @DisplayName("La persona es su cuenta de Google: si cambia su nombre en Google sigue siendo el mismo usuario")
    void laCuentaDeGoogleEsLaIdentidad() {
        var existente = ganadorConEventoYaPublicado("Ana", Rol.COMPRADOR);
        when(usuarios.findByNombreNormalizadoAndRol(Usuario.claveGoogle("ana"), Rol.COMPRADOR))
                .thenReturn(Optional.of(existente));

        var sesion = servicio.iniciarSesionConGoogle(
                new VerificadorGoogle.CuentaGoogle("ana", "ana@gmail.com", "Ana María"), Rol.COMPRADOR);

        assertThat(sesion.usuario()).isSameAs(existente);
        assertThat(sesion.usuario().getNombre()).isEqualTo("Ana");
    }

    @Test
    @DisplayName("El token lleva id, nombre y rol, que es lo que leen el api-gateway y el realtime-gateway")
    void elTokenLlevaLaIdentidad() {
        var existente = ganadorConEventoYaPublicado("José Ñandú", Rol.SUBASTADOR);
        when(usuarios.findByNombreNormalizadoAndRol(clave("José Ñandú"), Rol.SUBASTADOR))
                .thenReturn(Optional.of(existente));

        var sesion = entrar("José Ñandú", Rol.SUBASTADOR);

        var claims = Jwts.parser().verifyWith(Keys.hmacShaKeyFor(SECRETO.getBytes(StandardCharsets.UTF_8)))
                .build().parseSignedClaims(sesion.token()).getPayload();
        assertThat(claims.getSubject()).isEqualTo(existente.getId().toString());
        assertThat(claims.get("nombre", String.class)).isEqualTo("José Ñandú");
        assertThat(claims.get("rol", String.class)).isEqualTo("SUBASTADOR");
    }

    @Test
    @DisplayName("La misma cuenta con distinto rol es otro usuario y sí se registra aparte")
    void elRolParteLaIdentidad() {
        when(usuarios.findByNombreNormalizadoAndRol(clave("Ana"), Rol.SUBASTADOR))
                .thenReturn(Optional.empty());
        when(usuarios.saveAndFlush(any(Usuario.class))).thenAnswer(i -> i.getArgument(0));

        var sesion = entrar("Ana", Rol.SUBASTADOR);

        assertThat(sesion.nuevo()).isTrue();
        assertThat(sesion.usuario().getRol()).isEqualTo(Rol.SUBASTADOR);
    }
}