package com.cafeorbe.identity.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Solo para pruebas y una única instancia: los gateways no ven este estado. */
@Component
@ConditionalOnProperty(name = "cafeorbe.sesiones.modo", havingValue = "memoria")
public class AlmacenDeSesionesEnMemoria implements AlmacenDeSesiones {

    private record Valor(String datos, Instant vence) {
    }

    private final Map<String, Valor> valores = new ConcurrentHashMap<>();

    @Override
    public void marcarCerrada(String jti, Duration restante) {
        valores.put(PREFIJO_CERRADA + jti, new Valor("1", Instant.now().plus(restante)));
    }

    @Override
    public boolean estaCerrada(String jti) {
        return leer(PREFIJO_CERRADA + jti).isPresent();
    }

    @Override
    public void guardarTicket(String ticket, String datos, Duration vida) {
        valores.put(PREFIJO_TICKET + ticket, new Valor(datos, Instant.now().plus(vida)));
    }

    /** Para las pruebas: el contenido de un ticket todavía vigente. */
    public Optional<String> ticket(String ticket) {
        return leer(PREFIJO_TICKET + ticket);
    }

    private Optional<String> leer(String clave) {
        Valor v = valores.get(clave);
        if (v == null || v.vence().isBefore(Instant.now())) {
            return Optional.empty();
        }
        return Optional.of(v.datos());
    }
}
