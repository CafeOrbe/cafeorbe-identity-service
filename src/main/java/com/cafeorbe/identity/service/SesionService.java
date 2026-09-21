package com.cafeorbe.identity.service;

import com.cafeorbe.contracts.EventoEnvelope;
import com.cafeorbe.contracts.Eventos;
import com.cafeorbe.contracts.Rol;
import com.cafeorbe.contracts.eventos.UsuarioRegistrado;
import com.cafeorbe.identity.model.Usuario;
import com.cafeorbe.identity.repository.UsuarioRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class SesionService {

    private static final Logger log = LoggerFactory.getLogger(SesionService.class);

    public record Sesion(String token, Usuario usuario, boolean nuevo) {
    }

    private final UsuarioRepository usuarios;
    private final TokenService tokens;
    private final RabbitTemplate rabbit;

    public SesionService(UsuarioRepository usuarios, TokenService tokens, RabbitTemplate rabbit) {
        this.usuarios = usuarios;
        this.tokens = tokens;
        this.rabbit = rabbit;
    }

    /**
     * Crea la sesión de una persona. La primera vez que ingresa con ese nombre y rol se registra
     * y se publica {@code UsuarioRegistrado}; después solo se emite un token nuevo.
     */
    public Sesion iniciarSesion(String nombre, Rol rol) {
        String limpio = nombre.trim();
        String clave = Usuario.normalizar(limpio);

        boolean nuevo = false;
        Usuario usuario = usuarios.findByNombreNormalizadoAndRol(clave, rol).orElse(null);
        if (usuario == null) {
            try {
                usuario = usuarios.saveAndFlush(new Usuario(limpio, rol));
                nuevo = true;
            } catch (DataIntegrityViolationException carrera) {
                // Otro ingreso simultáneo con el mismo nombre y rol ganó el insert.
                usuario = usuarios.findByNombreNormalizadoAndRol(clave, rol).orElseThrow(() -> carrera);
            }
        }

        publicarSiPendiente(usuario);
        return new Sesion(tokens.emitir(usuario), usuario, nuevo);
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
