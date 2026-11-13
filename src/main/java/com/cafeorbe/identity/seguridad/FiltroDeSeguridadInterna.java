package com.cafeorbe.identity.seguridad;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * SEG-02: el servicio solo atiende a quien presenta la credencial interna (cabecera {@code X-Internal-Token}),
 * que solo conocen el api-gateway y los demás servicios. En QA los servicios son alcanzables desde Internet:
 * sin esto, cualquiera podía llamarlos directamente y fabricar las cabeceras X-User-* con otro rol.
 * La salud ({@code /actuator/health}) queda abierta para las sondas del orquestador.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class FiltroDeSeguridadInterna extends OncePerRequestFilter {

    public static final String CABECERA = "X-Internal-Token";

    private final byte[] credencial;
    private final Auditoria auditoria;
    private Timer permitidas;
    private Timer rechazadas;

    public FiltroDeSeguridadInterna(@Value("${cafeorbe.seguridad.credencial-interna}") String credencial,
                                    Auditoria auditoria) {
        this.credencial = credencial.getBytes(StandardCharsets.UTF_8);
        this.auditoria = auditoria;
    }

    @Autowired
    void medirCon(MeterRegistry metricas) {
        permitidas = timerDeVerificacion(metricas, "credencial_interna", "permitida");
        rechazadas = timerDeVerificacion(metricas, "credencial_interna", "rechazada");
    }

    /**
     * SEG-02: cuánto suma la verificación a cada petición (meta: menos de 5 ms en p95). Buckets en 1, 5 y 10 ms
     * para comparar directo con la meta en el tablero técnico.
     */
    static Timer timerDeVerificacion(MeterRegistry metricas, String etapa, String resultado) {
        return Timer.builder("cafeorbe.seguridad.verificacion")
                .description("SEG-02: tiempo de la verificación de seguridad de cada petición")
                .tag("etapa", etapa).tag("resultado", resultado)
                .serviceLevelObjectives(Duration.ofMillis(1), Duration.ofMillis(5), Duration.ofMillis(10))
                .register(metricas);
    }

    private static void medir(Timer timer, long inicio) {
        if (timer != null) {
            timer.record(System.nanoTime() - inicio, TimeUnit.NANOSECONDS);
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest peticion) {
        String ruta = peticion.getRequestURI();
        return ruta.equals("/actuator/health") || ruta.startsWith("/actuator/health/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest peticion, HttpServletResponse respuesta, FilterChain cadena)
            throws ServletException, IOException {
        long inicio = System.nanoTime();
        String recibida = peticion.getHeader(CABECERA);
        // Comparación en tiempo constante: no revela por cuánto se acercó un intento.
        boolean valida = recibida != null && MessageDigest.isEqual(credencial, recibida.getBytes(StandardCharsets.UTF_8));
        medir(valida ? permitidas : rechazadas, inicio);
        if (!valida) {
            auditoria.rechazo("acceso_interno_rechazado", recibida == null ? "sin_credencial" : "credencial_invalida",
                    null, peticion);
            respuesta.setStatus(HttpServletResponse.SC_FORBIDDEN);
            respuesta.setContentType(MediaType.APPLICATION_JSON_VALUE);
            respuesta.setCharacterEncoding(StandardCharsets.UTF_8.name());
            respuesta.getWriter().write("{\"status\":403,\"mensaje\":\"No autorizado\",\"campos\":{}}");
            return;
        }
        cadena.doFilter(peticion, respuesta);
    }
}
