package com.cafeorbe.identity.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConditionalOnProperty(name = "cafeorbe.sesiones.modo", havingValue = "redis", matchIfMissing = true)
public class AlmacenDeSesionesRedis implements AlmacenDeSesiones {

    private final StringRedisTemplate redis;

    public AlmacenDeSesionesRedis(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void marcarCerrada(String jti, Duration restante) {
        redis.opsForValue().set(PREFIJO_CERRADA + jti, "1", restante);
    }

    @Override
    public boolean estaCerrada(String jti) {
        return Boolean.TRUE.equals(redis.hasKey(PREFIJO_CERRADA + jti));
    }

    @Override
    public void guardarTicket(String ticket, String datos, Duration vida) {
        redis.opsForValue().set(PREFIJO_TICKET + ticket, datos, vida);
    }
}
