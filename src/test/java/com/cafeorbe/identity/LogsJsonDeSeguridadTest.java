package com.cafeorbe.identity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OBS-01 / SEG-02: los eventos de seguridad quedan en el log JSON de Azure con un solo correlationId (ver LogsJson).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(GoogleDePrueba.Config.class)
class LogsJsonDeSeguridadTest {

    @Autowired MockMvc mvc;

    @Test
    @DisplayName("OBS-01 · El acceso sin credencial interna queda en el log JSON con un solo correlationId")
    void accesoInternoRechazadoQuedaEnElLog() throws Exception {
        try (var logs = LogsJson.de("cafeorbe.seguridad")) {
            mvc.perform(post("/api/sesion/ticket-ws").header("X-Correlation-Id", "corr-logs-json-1"))
                    .andExpect(status().isForbidden());

            logs.registradoUnaVez("acceso_interno_rechazado", "corr-logs-json-1");
        }
    }

    @Test
    @DisplayName("SEG-01 · Cada login rechazado queda en el log JSON con un solo correlationId")
    void loginRechazadoQuedaEnElLog() throws Exception {
        try (var logs = LogsJson.de("cafeorbe.seguridad")) {
            mvc.perform(post("/api/sesion/google").header("X-Internal-Token", "credencial-de-prueba")
                            .header("X-Correlation-Id", "corr-logs-json-2")
                            .contentType("application/json").content("{\"idToken\":\"falso\",\"rol\":\"COMPRADOR\"}"))
                    .andExpect(status().isUnauthorized());

            logs.registradoUnaVez("login_rechazado", "corr-logs-json-2");
        }
    }
}
