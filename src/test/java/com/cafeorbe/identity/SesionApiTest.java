package com.cafeorbe.identity;

import com.cafeorbe.contracts.Eventos;
import com.cafeorbe.identity.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.amqp.AmqpConnectException;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HU-01 y HU-07 con el único acceso que existe: la cuenta de Google (simulada con {@link GoogleDePrueba}). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(GoogleDePrueba.Config.class)
class SesionApiTest {

    @Autowired MockMvc mvc;
    @Autowired UsuarioRepository usuarios;
    @MockitoBean RabbitTemplate rabbit;

    @BeforeEach
    void limpiar() {
        usuarios.deleteAll();
    }

    private ResultActions ingresar(String cuerpo) throws Exception {
        return mvc.perform(post("/api/sesion/google").header("X-Internal-Token", "credencial-de-prueba")
                .contentType(MediaType.APPLICATION_JSON).content(cuerpo));
    }

    private ResultActions conGoogle(String sub, String rol) throws Exception {
        return ingresar("{\"idToken\":\"" + GoogleDePrueba.idToken(sub) + "\",\"rol\":\"" + rol + "\"}");
    }

    @Test
    @DisplayName("HU-01 · Acceso como Comprador con Google: crea la sesión con el nombre de la cuenta y el rol")
    void accesoComoComprador() throws Exception {
        conGoogle("ana", "COMPRADOR")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.usuario.nombre").value("Persona ana"))
                .andExpect(jsonPath("$.usuario.rol").value("COMPRADOR"))
                .andExpect(jsonPath("$.nuevo").value(true));
        verify(rabbit).convertAndSend(eq(Eventos.EXCHANGE), eq(Eventos.USUARIO_REGISTRADO), any(Object.class));
    }

    @Test
    @DisplayName("HU-01 · Acceso como Subastador con Google")
    void accesoComoSubastador() throws Exception {
        conGoogle("luis", "SUBASTADOR")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.usuario.rol").value("SUBASTADOR"));
    }

    @Test
    @DisplayName("SEG-01 · No existe acceso propio por nombre: POST /api/sesion no crea sesión ni usuario")
    void sinAccesoPorNombre() throws Exception {
        long antes = usuarios.count();
        int estado = mvc.perform(post("/api/sesion").header("X-Internal-Token", "credencial-de-prueba")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"nombre\":\"Intruso\",\"rol\":\"COMPRADOR\"}"))
                .andReturn().getResponse().getStatus();

        assertThat(estado).isIn(404, 405);
        assertThat(usuarios.count()).isEqualTo(antes);
        verify(rabbit, never()).convertAndSend(any(String.class), any(String.class), any(Object.class));
    }

    @Test
    @DisplayName("HU-01 · Sin token de Google: HTTP 400 y no crea la sesión")
    void sinTokenDeGoogle() throws Exception {
        ingresar("{\"idToken\":\"   \",\"rol\":\"COMPRADOR\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value("Falta el token de Google"));
        verify(rabbit, never()).convertAndSend(any(String.class), any(String.class), any(Object.class));
    }

    @Test
    @DisplayName("HU-01 · Rol inválido o ausente: HTTP 400")
    void rolInvalido() throws Exception {
        String token = GoogleDePrueba.idToken("ana");
        ingresar("{\"idToken\":\"" + token + "\",\"rol\":\"ADMIN\"}").andExpect(status().isBadRequest());
        ingresar("{\"idToken\":\"" + token + "\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value("El rol es obligatorio"));
    }

    @Test
    @DisplayName("HU-07 · Un segundo ingreso con la misma cuenta reutiliza el usuario y no vuelve a publicar UsuarioRegistrado")
    void segundoIngresoNoRepublica() throws Exception {
        conGoogle("ana", "COMPRADOR").andExpect(jsonPath("$.nuevo").value(true));
        conGoogle("ana", "COMPRADOR")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nuevo").value(false));
        verify(rabbit, times(1)).convertAndSend(eq(Eventos.EXCHANGE), eq(Eventos.USUARIO_REGISTRADO), any(Object.class));
    }

    @Test
    @DisplayName("Si el broker está caído el ingreso funciona y el evento se reintenta en el siguiente ingreso")
    void brokerCaidoSeReintenta() throws Exception {
        doThrow(new AmqpConnectException(new RuntimeException("caído")))
                .when(rabbit).convertAndSend(any(String.class), any(String.class), any(Object.class));
        conGoogle("ana", "COMPRADOR").andExpect(status().isCreated());

        Mockito.reset(rabbit);
        conGoogle("ana", "COMPRADOR").andExpect(status().isCreated());
        verify(rabbit, times(1)).convertAndSend(eq(Eventos.EXCHANGE), eq(Eventos.USUARIO_REGISTRADO), any(Object.class));
    }
}
