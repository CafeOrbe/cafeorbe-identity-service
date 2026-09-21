# cafeorbe-identity-service

Sesión con nombre y rol. **Arquitectura:** CRUD en capas (Controller → Service → Repository). Puerto `8081`.

| Historia | Qué hace |
|---|---|
| HU-01 | `POST /api/sesion` crea la sesión y devuelve un JWT (HS256) con id, nombre y rol. Nombre vacío → HTTP 400 `El nombre es obligatorio`. |
| HU-02 | El cierre de sesión es del cliente (borra el token). No hay endpoint. |
| HU-07 (origen) | La primera vez que alguien ingresa con un nombre y rol, publica `UsuarioRegistrado`. Si el broker está caído, el evento queda pendiente (`evento_publicado = false`) y se reintenta en el siguiente ingreso. |

Una persona se identifica por **nombre (sin distinguir mayúsculas ni espacios extremos) + rol**.

## Ejecutar

```bash
mvn spring-boot:run      # requiere Postgres (identity_db) y RabbitMQ: ver cafeorbe-infra
mvn test                 # usa H2, no necesita infraestructura
```

Variables: `DB_URL`, `DB_USER`, `DB_PASSWORD`, `RABBIT_*`, `JWT_SECRET` (≥ 32 caracteres, el mismo que usan gateway y realtime).
