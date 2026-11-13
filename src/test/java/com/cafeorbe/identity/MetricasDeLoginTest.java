package com.cafeorbe.identity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SEG-01 / OBS-01: el login con Google debe tardar menos de 3 s en p95. Para que Grafana pueda calcular ese p95, la
 * métrica del login necesita buckets, y uno justo en 3 s para que la comparación con el umbral sea exacta.
 */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureObservability(tracing = false)
@ActiveProfiles("test")
@Import(GoogleDePrueba.Config.class)
class MetricasDeLoginTest {

    private static final String CREDENCIAL = "credencial-de-prueba";

    @Autowired MockMvc mvc;

    @Test
    @DisplayName("SEG-01 · El login con Google queda en un histograma con bucket en 3 s, sin el histograma completo")
    void loginConBucketEnTresSegundos() throws Exception {
        mvc.perform(post("/api/sesion/google").header("X-Internal-Token", CREDENCIAL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idToken\":\"" + GoogleDePrueba.idToken(UUID.randomUUID().toString()) + "\",\"rol\":\"COMPRADOR\"}"))
                .andExpect(status().isCreated());

        String metricas = mvc.perform(get("/actuator/prometheus").header("X-Internal-Token", CREDENCIAL))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(metricas)
                .containsPattern("http_server_requests_seconds_bucket\\{[^}]*status=\"201\"[^}]*uri=\"/api/sesion/google\"[^}]*le=\"3\\.0\"")
                // Solo los umbrales: el histograma completo (~70 buckets por serie) se come el plan gratuito de Grafana.
                .doesNotContainPattern("http_server_requests_seconds_bucket\\{[^}]*le=\"0\\.001\"");
    }
}
