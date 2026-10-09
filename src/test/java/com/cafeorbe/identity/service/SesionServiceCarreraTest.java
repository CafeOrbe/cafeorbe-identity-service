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
 * Dos ingresos simultáneos con el mismo nombre y rol compiten por el mismo INSERT: la base resuelve la
 * carrera con una violación de unicidad. El que pierde debe re-leer al ganador y emitirle un token, no
 * fallar. Y como el ganador ya publicó su UsuarioRegistrado, el perdedor no debe volver a publicarlo:
 * wallet es idempotente, pero el punto es no duplicar trabajo ni el evento.
 */
class SesionServiceCarreraTest {

    static final String SECRETO = "cafeorbe-dev-jwt-secret-cambiar-en-prod-0123456789";

    private final UsuarioRepository usuarios = mock(UsuarioRepository.class);
    private final RabbitTemplate rabbit = mock(RabbitTemplate.class);
    private final TokenService tokens = new TokenService(SECRETO, 8);
    private final SesionService servicio = new SesionService(usuarios, tokens, rabbit);

    private static Usuario ganadorConEventoYaPublicado(String nombre, Rol rol) {
        var usuario = new Usuario(nombre, rol);
        usuario.marcarEventoPublicado();
        return usuario;
    }

    @Test
    @DisplayName("Carrera del INSERT: el segundo ingreso reutiliza al ganador y no se marca como nuevo")
    void elPerdedorReutilizaAlGanador() {
        var ganador = ganadorConEventoYaPublicado("Ana", Rol.COMPRADOR);
        when(usuarios.findByNombreNormalizadoAndRol(Usuario.normalizar("Ana"), Rol.COMPRADOR))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(ganador));
        when(usuarios.saveAndFlush(any(Usuario.class)))
                .thenThrow(new DataIntegrityViolationException("violación de unicidad"));

        var sesion = servicio.iniciarSesion("Ana", Rol.COMPRADOR);

        assertThat(sesion.nuevo()).isFalse();
        assertThat(sesion.usuario()).isSameAs(ganador);
        assertThat(sesion.token()).isNotBlank();
    }

    @Test
    @DisplayName("Carrera · El perdedor no vuelve a publicar UsuarioRegistrado: ya lo publicó el ganador")
    void elPerdedorNoRepublicaElEvento() {
        var ganador = ganadorConEventoYaPublicado("Ana", Rol.COMPRADOR);
        when(usuarios.findByNombreNormalizadoAndRol(Usuario.normalizar("Ana"), Rol.COMPRADOR))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(ganador));
        when(usuarios.saveAndFlush(any(Usuario.class)))
                .thenThrow(new DataIntegrityViolationException("violación de unicidad"));

        servicio.iniciarSesion("Ana", Rol.COMPRADOR);

        verify(rabbit, never()).convertAndSend(anyString(), anyString(), any(Object.class));
    }

    @Test
    @DisplayName("Carrera · Si tras la violación tampoco aparece el ganador, el error original se propaga")
    void sinGanadorVisibleSePropagaElError() {
        when(usuarios.findByNombreNormalizadoAndRol(Usuario.normalizar("Ana"), Rol.COMPRADOR))
                .thenReturn(Optional.empty());
        when(usuarios.saveAndFlush(any(Usuario.class)))
                .thenThrow(new DataIntegrityViolationException("violación de unicidad"));

        assertThatThrownBy(() -> servicio.iniciarSesion("Ana", Rol.COMPRADOR))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Primer ingreso: registra el usuario, marca nuevo y publica UsuarioRegistrado")
    void primerIngresoRegistraYPublica() {
        when(usuarios.findByNombreNormalizadoAndRol(Usuario.normalizar("Ana"), Rol.COMPRADOR))
                .thenReturn(Optional.empty());
        when(usuarios.saveAndFlush(any(Usuario.class))).thenAnswer(i -> i.getArgument(0));

        var sesion = servicio.iniciarSesion("Ana", Rol.COMPRADOR);

        assertThat(sesion.nuevo()).isTrue();
        assertThat(sesion.usuario().isEventoPublicado()).isTrue();
        verify(rabbit).convertAndSend(eq(Eventos.EXCHANGE), eq(Eventos.USUARIO_REGISTRADO), any(Object.class));
    }

    @Test
    @DisplayName("Si el broker está caído, el primer ingreso no falla: el evento queda pendiente de reintento")
    void brokerCaidoNoImpideCrearLaSesion() {
        when(usuarios.findByNombreNormalizadoAndRol(Usuario.normalizar("Ana"), Rol.COMPRADOR))
                .thenReturn(Optional.empty());
        when(usuarios.saveAndFlush(any(Usuario.class))).thenAnswer(i -> i.getArgument(0));
        org.mockito.Mockito.doThrow(new org.springframework.amqp.AmqpConnectException(new RuntimeException("caído")))
                .when(rabbit).convertAndSend(anyString(), anyString(), any(Object.class));

        var sesion = servicio.iniciarSesion("Ana", Rol.COMPRADOR);

        assertThat(sesion.token()).isNotBlank();
        assertThat(sesion.usuario().isEventoPublicado()).isFalse();
    }

    @Test
    @DisplayName("Un ingreso posterior solo emite token: ni crea usuario ni publica evento")
    void ingresoPosteriorSoloEmiteToken() {
        var existente = ganadorConEventoYaPublicado("Ana", Rol.COMPRADOR);
        when(usuarios.findByNombreNormalizadoAndRol(Usuario.normalizar("Ana"), Rol.COMPRADOR))
                .thenReturn(Optional.of(existente));

        var sesion = servicio.iniciarSesion("Ana", Rol.COMPRADOR);

        assertThat(sesion.nuevo()).isFalse();
        assertThat(sesion.usuario()).isSameAs(existente);
        verify(usuarios, never()).saveAndFlush(any(Usuario.class));
        verify(rabbit, never()).convertAndSend(anyString(), anyString(), any(Object.class));
    }

    @Test
    @DisplayName("El nombre se normaliza: «  aNa  » y «ana» son la misma persona, no dos usuarios")
    void elNombreSeNormaliza() {
        var existente = ganadorConEventoYaPublicado("Ana", Rol.COMPRADOR);
        when(usuarios.findByNombreNormalizadoAndRol(Usuario.normalizar("aNa"), Rol.COMPRADOR))
                .thenReturn(Optional.of(existente));

        var sesion = servicio.iniciarSesion("  aNa  ", Rol.COMPRADOR);

        assertThat(sesion.usuario()).isSameAs(existente);
        assertThat(sesion.usuario().getNombre()).isEqualTo("Ana");
    }

    @Test
    @DisplayName("El token lleva id, nombre y rol, que es lo que leen el api-gateway y el realtime-gateway")
    void elTokenLlevaLaIdentidad() {
        var existente = ganadorConEventoYaPublicado("José Ñandú", Rol.SUBASTADOR);
        when(usuarios.findByNombreNormalizadoAndRol(Usuario.normalizar("José Ñandú"), Rol.SUBASTADOR))
                .thenReturn(Optional.of(existente));

        var sesion = servicio.iniciarSesion("José Ñandú", Rol.SUBASTADOR);

        var claims = Jwts.parser().verifyWith(Keys.hmacShaKeyFor(SECRETO.getBytes(StandardCharsets.UTF_8)))
                .build().parseSignedClaims(sesion.token()).getPayload();
        assertThat(claims.getSubject()).isEqualTo(existente.getId().toString());
        assertThat(claims.get("nombre", String.class)).isEqualTo("José Ñandú");
        assertThat(claims.get("rol", String.class)).isEqualTo("SUBASTADOR");
    }

    @Test
    @DisplayName("El mismo nombre con distinto rol es otra persona y sí se registra aparte")
    void elRolParteLaIdentidad() {
        when(usuarios.findByNombreNormalizadoAndRol(Usuario.normalizar("Ana"), Rol.SUBASTADOR))
                .thenReturn(Optional.empty());
        when(usuarios.saveAndFlush(any(Usuario.class))).thenAnswer(i -> i.getArgument(0));

        var sesion = servicio.iniciarSesion("Ana", Rol.SUBASTADOR);

        assertThat(sesion.nuevo()).isTrue();
        assertThat(sesion.usuario().getRol()).isEqualTo(Rol.SUBASTADOR);
    }
}