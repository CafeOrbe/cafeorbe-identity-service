package com.cafeorbe.identity;

import com.cafeorbe.identity.repository.UsuarioRepository;
import com.cafeorbe.identity.service.AlmacenDeSesiones;
import com.cafeorbe.identity.service.AlmacenDeSesionesEnMemoria;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jwt.JWTClaimsSet;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** SEG-01, SEG-02, SEG-04 y SEG-05 sobre la API real de identity (Google simulado con una clave propia). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(GoogleDePrueba.Config.class)
class SeguridadSesionApiTest {

    private static final String CREDENCIAL = "credencial-de-prueba";

    @Autowired MockMvc mvc;
    @Autowired UsuarioRepository usuarios;
    @Autowired AlmacenDeSesiones almacen;
    @Autowired MeterRegistry metricas;
    @Autowired ObjectMapper json;
    @MockitoBean RabbitTemplate rabbit;

    @BeforeEach
    void limpiar() {
        usuarios.deleteAll();
    }

    private ResultActions conGoogle(String idToken, String rol) throws Exception {
        return mvc.perform(post("/api/sesion/google").header("X-Internal-Token", CREDENCIAL)
                .header("X-Forwarded-For", "203.0.113.7")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"idToken\":\"" + idToken + "\",\"rol\":\"" + rol + "\"}"));
    }

    private String tokenDeSesion(String sub) throws Exception {
        String cuerpo = conGoogle(GoogleDePrueba.idToken(sub), "COMPRADOR").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(cuerpo).path("token").asText();
    }

    private double rechazosDeLogin() {
        return metricas.find("cafeorbe.seguridad.eventos").tag("evento", "login_rechazado").counters().stream()
                .mapToDouble(c -> c.count()).sum();
    }

    @Test
    @DisplayName("SEG-01 · Un ID token legítimo de Google crea la sesión en menos de 3 s")
    void loginLegitimo() throws Exception {
        long inicio = System.nanoTime();
        conGoogle(GoogleDePrueba.idToken("10001"), "COMPRADOR")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.usuario.nombre").value("Persona 10001"))
                .andExpect(jsonPath("$.usuario.rol").value("COMPRADOR"))
                .andExpect(jsonPath("$.nuevo").value(true));
        assertThat(Duration.ofNanos(System.nanoTime() - inicio)).isLessThan(Duration.ofSeconds(3));

        // El segundo ingreso con la misma cuenta y rol reutiliza al usuario.
        conGoogle(GoogleDePrueba.idToken("10001"), "COMPRADOR").andExpect(jsonPath("$.nuevo").value(false));
        assertThat(usuarios.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("SEG-01 · Dos personas con el mismo nombre en Google son usuarios distintos")
    void mismoNombreCuentasDistintas() throws Exception {
        conGoogle(GoogleDePrueba.idToken("1", r -> r.claim("name", "Ana")), "COMPRADOR").andExpect(status().isCreated());
        conGoogle(GoogleDePrueba.idToken("2", r -> r.claim("name", "Ana")), "COMPRADOR")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.nuevo").value(true));
        assertThat(usuarios.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("SEG-01 · 12 ID tokens inválidos: 100 % responde 401, ninguno crea sesión y todos quedan registrados")
    void tokensInvalidos() throws Exception {
        String valido = GoogleDePrueba.idToken("20001");
        String[] partes = valido.split("\\.");
        String payloadAlterado = new String(Base64.getUrlDecoder().decode(partes[1])).replace("20001", "99999");

        List<String> invalidos = new ArrayList<>(List.of(
                // falso: firmado con una clave que no es de Google
                GoogleDePrueba.firmar(GoogleDePrueba.reclamosValidos("20001").build(), GoogleDePrueba.CLAVES_DEL_ATACANTE),
                // alterado: se cambió el sub sin volver a firmar
                partes[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(payloadAlterado.getBytes())
                        + "." + partes[2],
                // firma recortada
                partes[0] + "." + partes[1] + "." + partes[2].substring(0, partes[2].length() - 4),
                // vencido
                GoogleDePrueba.idToken("20001", r -> r.issueTime(Date.from(Instant.now().minusSeconds(7200)))
                        .expirationTime(Date.from(Instant.now().minusSeconds(3600)))),
                // emitido para otra aplicación
                GoogleDePrueba.idToken("20001", r -> r.audience("otra-app.apps.googleusercontent.com")),
                // emisor que no es Google
                GoogleDePrueba.idToken("20001", r -> r.issuer("https://accounts.g00gle.com")),
                // correo no verificado
                GoogleDePrueba.idToken("20001", r -> r.claim("email_verified", false)),
                // sin firma (alg none)
                partes[0].replace(partes[0], Base64.getUrlEncoder().withoutPadding()
                        .encodeToString("{\"alg\":\"none\"}".getBytes())) + "." + partes[1] + ".",
                // todavía no válido (nbf en el futuro)
                GoogleDePrueba.idToken("20001", r -> r.notBeforeTime(Date.from(Instant.now().plusSeconds(3600)))),
                // sin sujeto
                GoogleDePrueba.firmar(new JWTClaimsSet.Builder(GoogleDePrueba.reclamosValidos("x").build())
                        .subject(null).build(), GoogleDePrueba.CLAVES),
                "esto-no-es-un-jwt",
                "a.b.c"));

        double antes = rechazosDeLogin();
        for (String token : invalidos) {
            conGoogle(token, "COMPRADOR").andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.token").doesNotExist());
        }
        assertThat(invalidos).hasSizeGreaterThanOrEqualTo(10);
        assertThat(usuarios.count()).isZero();
        assertThat(rechazosDeLogin() - antes).isEqualTo(invalidos.size());
    }

    @Test
    @DisplayName("SEG-02 · Sin credencial interna, o con una equivocada, el servicio responde 403 sin crear nada")
    void sinCredencialInterna() throws Exception {
        mvc.perform(post("/api/sesion/google").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idToken\":\"" + GoogleDePrueba.idToken("3") + "\",\"rol\":\"COMPRADOR\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/sesion/google").header("X-Internal-Token", "adivinada")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idToken\":\"" + GoogleDePrueba.idToken("3") + "\",\"rol\":\"COMPRADOR\"}"))
                .andExpect(status().isForbidden());
        assertThat(usuarios.count()).isZero();
        // Las sondas de salud no llevan credencial.
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("OBS-01 · La respuesta devuelve el correlationId recibido, o uno nuevo si no venía")
    void correlacion() throws Exception {
        mvc.perform(get("/actuator/health").header("X-Correlation-Id", "puja-1234-abcd"))
                .andExpect(header().string("X-Correlation-Id", "puja-1234-abcd"));
        mvc.perform(get("/actuator/health").header("X-Correlation-Id", "<script>"))
                .andExpect(header().string("X-Correlation-Id", org.hamcrest.Matchers.matchesPattern("[0-9a-f-]{36}")));
    }

    @Test
    @DisplayName("SEG-04 · El ticket WebSocket es aleatorio, de vida corta y lleva la identidad y el jti de la sesión")
    void ticketWebSocket() throws Exception {
        String token = tokenDeSesion("40001");
        Set<String> tickets = new HashSet<>();
        for (int i = 0; i < 3; i++) {
            String cuerpo = mvc.perform(post("/api/sesion/ticket-ws").header("X-Internal-Token", CREDENCIAL)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.expiraEn").isNotEmpty())
                    .andReturn().getResponse().getContentAsString();
            tickets.add(json.readTree(cuerpo).path("ticket").asText());
        }
        assertThat(tickets).hasSize(3).allSatisfy(t -> assertThat(t).hasSizeGreaterThanOrEqualTo(40));

        String guardado = ((AlmacenDeSesionesEnMemoria) almacen).ticket(tickets.iterator().next()).orElseThrow();
        JsonNode datos = json.readTree(guardado);
        assertThat(datos.path("rol").asText()).isEqualTo("COMPRADOR");
        assertThat(datos.path("jti").asText()).isNotBlank();
        assertThat(datos.path("expiraEn").asLong()).isGreaterThan(Instant.now().getEpochSecond());
        // El ticket no es el token de sesión ni lo contiene.
        assertThat(guardado).doesNotContain(token);
    }

    @Test
    @DisplayName("SEG-04 · Sin sesión válida no se emite ticket")
    void ticketSinSesion() throws Exception {
        mvc.perform(post("/api/sesion/ticket-ws").header("X-Internal-Token", CREDENCIAL))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/sesion/ticket-ws").header("X-Internal-Token", CREDENCIAL)
                        .header("Authorization", "Bearer no.es.valido"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("SEG-05 · Tras cerrar sesión el mismo token ya no sirve: ni ticket ni segundo cierre")
    void cerrarSesion() throws Exception {
        String token = tokenDeSesion("50001");
        mvc.perform(post("/api/sesion/cerrar").header("X-Internal-Token", CREDENCIAL)
                .header("Authorization", "Bearer " + token)).andExpect(status().isNoContent());

        mvc.perform(post("/api/sesion/ticket-ws").header("X-Internal-Token", CREDENCIAL)
                .header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/sesion/cerrar").header("X-Internal-Token", CREDENCIAL)
                .header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
    }
}
