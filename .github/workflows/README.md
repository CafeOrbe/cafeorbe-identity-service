# Pipeline `ci.yml`

`ci` compila `cafeorbe-contracts`, ejecuta `mvn -B verify` y espera el Quality Gate de Sonar. `build-push` publica la imagen en Azure Container Registry y `deploy-qa` actualiza la aplicación en Azure Container Apps y corre el smoke test de `/actuator/health`. `deploy-prod` solo se ejecuta con tags `v*`.

## Secretos

| Nombre | Uso |
| --- | --- |
| `AZURE_CREDENTIALS` | inicio de sesión en Azure |
| `ACR_USERNAME`, `ACR_PASSWORD` | push de la imagen a `vars.ACR_NAME`.azurecr.io |
| `SONAR_TOKEN` | Quality Gate (secreto de organización) |
| `JWT_SECRET` | firma y validación de la sesión |
| `INTERNAL_TOKEN` | credencial interna SEG-02; **el mismo valor en los 6 servicios** |
| `REDIS_PASSWORD` | autenticación con Azure Cache for Redis |
| `DB_PASSWORD` | PostgreSQL |
| `RABBIT_PASSWORD` | CloudAMQP |
| `OTLP_METRICS_AUTH` | autenticación de métricas OTLP (opcional) |

## Variables

| Nombre | Por defecto | Uso |
| --- | --- | --- |
| `ACR_NAME` | — | registro de imágenes |
| `REDIS_HOST` | — | host de Redis; `deploy-qa` falla si no está definida |
| `REDIS_PORT` | `6380` | puerto TLS |
| `REDIS_SSL` | `true` | TLS |
| `GOOGLE_CLIENT_ID` | — | audiencia del ID token de Google; `deploy-qa` falla si no está definida |
| `OTLP_METRICS_ENABLED` | `false` | exportar métricas |
| `OTLP_METRICS_URL` | `http://localhost:4318/v1/metrics` | endpoint OTLP |

`deploy-qa` aborta antes de tocar Azure si falta `INTERNAL_TOKEN`, `REDIS_HOST` o `GOOGLE_CLIENT_ID`.

`GOOGLE_CLIENT_ID` es público: es el Client ID del OAuth client de tipo **Web application**. CaféOrbe no usa `client_secret`; identity valida la firma del ID token con las claves públicas de Google y comprueba que la audiencia coincida con este valor.
