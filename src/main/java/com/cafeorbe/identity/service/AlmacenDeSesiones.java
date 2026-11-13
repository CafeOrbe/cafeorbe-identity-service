package com.cafeorbe.identity.service;

import java.time.Duration;

/**
 * Estado de sesión compartido con el api-gateway y el realtime-gateway a través de Redis.
 * <ul>
 *   <li>SEG-05: sesiones cerradas, clave {@code cafeorbe:sesion:cerrada:{jti}} con TTL hasta el vencimiento del token.</li>
 *   <li>SEG-04: tickets de conexión WebSocket, clave {@code cafeorbe:ws:ticket:{ticket}} con TTL corto; el
 *       realtime-gateway los lee y borra en una sola operación atómica, así que solo sirven una vez.</li>
 * </ul>
 */
public interface AlmacenDeSesiones {

    String PREFIJO_CERRADA = "cafeorbe:sesion:cerrada:";
    String PREFIJO_TICKET = "cafeorbe:ws:ticket:";

    void marcarCerrada(String jti, Duration restante);

    boolean estaCerrada(String jti);

    void guardarTicket(String ticket, String datos, Duration vida);
}
