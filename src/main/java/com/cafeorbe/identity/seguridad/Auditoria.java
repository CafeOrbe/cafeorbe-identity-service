package com.cafeorbe.identity.seguridad;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Registro de auditoría de seguridad (SEG-01, SEG-02, SEG-05). Cada intento rechazado deja una línea JSON con
 * evento, motivo, usuario, IP y correlationId, y suma la métrica {@code cafeorbe_seguridad_eventos_total}.
 * El correlationId sale del MDC (FiltroDeCorrelacion): no se agrega aquí, porque en los logs JSON un campo
 * repetido hace fallar al escritor y el evento se pierde sin aviso.
 * Nunca se registra el token ni el ID token: solo el motivo.
 */
@Component
public class Auditoria {

    private static final Logger log = LoggerFactory.getLogger("cafeorbe.seguridad");

    private final MeterRegistry metricas;

    public Auditoria(MeterRegistry metricas) {
        this.metricas = metricas;
    }

    public void rechazo(String evento, String motivo, String usuario, HttpServletRequest peticion) {
        metricas.counter("cafeorbe.seguridad.eventos", "evento", evento, "motivo", motivo).increment();
        log.atWarn()
                .addKeyValue("evento", evento)
                .addKeyValue("motivo", motivo)
                .addKeyValue("usuario", usuario == null ? "desconocido" : usuario)
                .addKeyValue("ip", ipDe(peticion))
                .addKeyValue("ruta", peticion.getRequestURI())
                .log("Evento de seguridad: {} ({})", evento, motivo);
    }

    public void exito(String evento, String usuario) {
        metricas.counter("cafeorbe.seguridad.eventos", "evento", evento, "motivo", "ok").increment();
        log.atInfo()
                .addKeyValue("evento", evento)
                .addKeyValue("usuario", usuario)
                .log("Evento de seguridad: {}", evento);
    }

    /** IP del cliente: la primera de X-Forwarded-For (la agrega el ingress y el api-gateway) o la del socket. */
    public static String ipDe(HttpServletRequest peticion) {
        String reenviada = peticion.getHeader("X-Forwarded-For");
        if (reenviada != null && !reenviada.isBlank()) {
            return reenviada.split(",")[0].trim();
        }
        return peticion.getRemoteAddr();
    }
}
