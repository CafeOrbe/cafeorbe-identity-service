package com.cafeorbe.identity.seguridad;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * OBS-01: deja el {@code correlationId} de la petición en el MDC para que salga en cada línea del log JSON.
 * Lo genera el api-gateway; si la petición no lo trae (o trae algo que no parece un id) se crea uno nuevo.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class FiltroDeCorrelacion extends OncePerRequestFilter {

    public static final String CABECERA = "X-Correlation-Id";
    public static final String MDC_CLAVE = "correlationId";
    private static final Pattern VALIDO = Pattern.compile("[A-Za-z0-9-]{8,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest peticion, HttpServletResponse respuesta, FilterChain cadena)
            throws ServletException, IOException {
        String recibido = peticion.getHeader(CABECERA);
        String id = recibido != null && VALIDO.matcher(recibido).matches() ? recibido : UUID.randomUUID().toString();
        MDC.put(MDC_CLAVE, id);
        respuesta.setHeader(CABECERA, id);
        try {
            cadena.doFilter(peticion, respuesta);
        } finally {
            MDC.remove(MDC_CLAVE);
        }
    }
}
