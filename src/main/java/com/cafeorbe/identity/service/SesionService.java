package com.cafeorbe.identity.service;

import com.cafeorbe.contracts.EventoEnvelope;
import com.cafeorbe.contracts.Eventos;
import com.cafeorbe.contracts.Rol;
import com.cafeorbe.contracts.eventos.UsuarioRegistrado;
import com.cafeorbe.identity.model.Usuario;
import com.cafeorbe.identity.repository.UsuarioRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class SesionService {

    private static final Logger log = LoggerFactory.getLogger(SesionService.class);
    private static final SecureRandom ALEATORIO = new SecureRandom();

    public record Sesion(String token, Usuario usuario, boolean nuevo) {
    }

    /** SEG-04: ticket de un solo uso para abrir el WebSocket sin poner el token de sesión en la URL. */
    public record TicketWs(String ticket, Instant expiraEn) {
    }

    /** La sesión no es válida, ya se cerró o venció. El motivo va al registro de auditoría. */
    public static class SesionInvalidaException extends RuntimeException {
        private final String motivo;

        public SesionInvalidaException(String motivo) {
            super("Sesión requerida o expirada");
            this.motivo = motivo;
        }

        public String motivo() {
            return motivo;
        }
    }

    private final UsuarioRepository usuarios;
    private final TokenService tokens;
    private final RabbitTemplate rabbit;
    private final AlmacenDeSesiones almacen;
    private final ObjectMapper json;
    private final Duration vidaTicket;

    @Autowired
    public SesionService(UsuarioRepository usuarios, TokenService tokens, RabbitTemplate rabbit,
                         AlmacenDeSesiones almacen, ObjectMapper json,
                         @Value("${cafeorbe.ws-ticket.segundos-de-vida:30}") long segundosTicket) {
        this.usuarios = usuarios;
        this.tokens = tokens;
        this.rabbit = rabbit;
        this.almacen = almacen;
        this.json = json;
        this.vidaTicket = Duration.ofSeconds(segundosTicket);
    }

    /** SEG-01: la cuenta de Google ya fue verificada; la primera vez con ese rol se registra al usuario. */
    public Sesion iniciarSesionConGoogle(VerificadorGoogle.CuentaGoogle cuenta, Rol rol) {
        // La clave de búsqueda de una cuenta de Google es 'google:{sub}': dos personas pueden llamarse igual.
        return registrarOEncontrar(Usuario.claveGoogle(cuenta.sub()), rol,
                () -> Usuario.deGoogle(cuenta.sub(), cuenta.email(), cuenta.nombre(), rol));
    }

    private Sesion registrarOEncontrar(String clave, Rol rol, Supplier<Usuario> nuevoUsuario) {
        boolean nuevo = false;
        Usuario usuario = usuarios.findByNombreNormalizadoAndRol(clave, rol).orElse(null);
        if (usuario == null) {
            try {
                usuario = usuarios.saveAndFlush(nuevoUsuario.get());
                nuevo = true;
            } catch (DataIntegrityViolationException carrera) {
                // Otro ingreso simultáneo con la misma clave y rol ganó el insert.
                usuario = usuarios.findByNombreNormalizadoAndRol(clave, rol).orElseThrow(() -> carrera);
            }
        }

        publicarSiPendiente(usuario);
        return new Sesion(tokens.emitir(usuario), usuario, nuevo);
    }

    /** Valida el token de sesión y que no se haya cerrado. */
    public TokenService.SesionToken validar(String autorizacion) {
        String token = sinPrefijo(autorizacion);
        var sesion = tokens.leer(token).orElseThrow(() -> new SesionInvalidaException("token_invalido_o_vencido"));
        if (almacen.estaCerrada(sesion.jti())) {
            throw new SesionInvalidaException("sesion_cerrada");
        }
        return sesion;
    }

    /**
     * SEG-05: cierra la sesión en el servidor. El {@code jti} queda anotado hasta que el token vence, así el
     * api-gateway lo rechaza aunque alguien lo haya copiado, y el realtime-gateway corta su WebSocket.
     */
    public TokenService.SesionToken cerrar(String autorizacion) {
        var sesion = validar(autorizacion);
        Duration restante = Duration.between(Instant.now(), sesion.expiraEn());
        if (!restante.isNegative() && !restante.isZero()) {
            almacen.marcarCerrada(sesion.jti(), restante);
        }
        return sesion;
    }

    /** SEG-04: emite el ticket WebSocket de la sesión. Dura pocos segundos y el realtime-gateway lo consume una vez. */
    public TicketWs emitirTicket(String autorizacion) {
        var sesion = validar(autorizacion);
        byte[] bytes = new byte[32];
        ALEATORIO.nextBytes(bytes);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        Map<String, Object> datos = new LinkedHashMap<>();
        datos.put("usuarioId", sesion.usuarioId().toString());
        datos.put("nombre", sesion.nombre());
        datos.put("rol", sesion.rol().name());
        datos.put("jti", sesion.jti());
        datos.put("expiraEn", sesion.expiraEn().getEpochSecond());
        try {
            almacen.guardarTicket(ticket, json.writeValueAsString(datos), vidaTicket);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo serializar el ticket", e);
        }
        return new TicketWs(ticket, Instant.now().plus(vidaTicket));
    }

    private static String sinPrefijo(String autorizacion) {
        if (autorizacion == null || !autorizacion.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new SesionInvalidaException("sin_token");
        }
        return autorizacion.substring(7).trim();
    }

    /**
     * Si el broker estaba caído en el primer ingreso, el evento queda pendiente y se reintenta
     * en el siguiente ingreso; wallet es idempotente, así que un reintento no duplica la carga.
     */
    private void publicarSiPendiente(Usuario usuario) {
        if (usuario.isEventoPublicado()) {
            return;
        }
        try {
            var evento = new EventoEnvelope<>(UUID.randomUUID(), Eventos.USUARIO_REGISTRADO, 1, Instant.now(),
                    new UsuarioRegistrado(usuario.getId(), usuario.getNombre(), usuario.getRol().name()));
            rabbit.convertAndSend(Eventos.EXCHANGE, Eventos.USUARIO_REGISTRADO, evento);
            usuario.marcarEventoPublicado();
            usuarios.save(usuario);
        } catch (RuntimeException e) {
            log.warn("No se pudo publicar UsuarioRegistrado de {}; se reintentará en el próximo ingreso: {}",
                    usuario.getId(), e.getMessage());
        }
    }
}
