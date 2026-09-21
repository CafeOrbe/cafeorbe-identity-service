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
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SesionApiTest {

    @Autowired MockMvc mvc;
    @Autowired UsuarioRepository usuarios;
    @MockitoBean RabbitTemplate rabbit;

    @BeforeEach
    void limpiar() {
        usuarios.deleteAll();
    }

    private ResultActions ingresar(String json) throws Exception {
        return mvc.perform(post("/api/sesion").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    @DisplayName("HU-01 · Acceso exitoso como Comprador: crea la sesión con nombre y rol")
    void accesoComoComprador() throws Exception {
        ingresar("{\"nombre\":\"Ana\",\"rol\":\"COMPRADOR\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.usuario.nombre").value("Ana"))
                .andExpect(jsonPath("$.usuario.rol").value("COMPRADOR"))
                .andExpect(jsonPath("$.nuevo").value(true));
        verify(rabbit).convertAndSend(eq(Eventos.EXCHANGE), eq(Eventos.USUARIO_REGISTRADO), any(Object.class));
    }

    @Test
    @DisplayName("HU-01 · Acceso exitoso como Subastador")
    void accesoComoSubastador() throws Exception {
        ingresar("{\"nombre\":\"Luis\",\"rol\":\"SUBASTADOR\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.usuario.rol").value("SUBASTADOR"));
    }

    @Test
    @DisplayName("HU-01 · Nombre vacío: HTTP 400 con El nombre es obligatorio y no crea la sesión")
    void nombreVacio() throws Exception {
        ingresar("{\"nombre\":\"   \",\"rol\":\"COMPRADOR\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value("El nombre es obligatorio"));
        verify(rabbit, never()).convertAndSend(any(String.class), any(String.class), any(Object.class));
    }

    @Test
    @DisplayName("HU-01 · Rol inválido o ausente: HTTP 400")
    void rolInvalido() throws Exception {
        ingresar("{\"nombre\":\"Ana\",\"rol\":\"ADMIN\"}").andExpect(status().isBadRequest());
        ingresar("{\"nombre\":\"Ana\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value("El rol es obligatorio"));
    }

    @Test
    @DisplayName("HU-07 · Un segundo ingreso reutiliza el usuario y no vuelve a publicar UsuarioRegistrado")
    void segundoIngresoNoRepublica() throws Exception {
        ingresar("{\"nombre\":\"Ana\",\"rol\":\"COMPRADOR\"}").andExpect(jsonPath("$.nuevo").value(true));
        ingresar("{\"nombre\":\"  ana \",\"rol\":\"COMPRADOR\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nuevo").value(false));
        verify(rabbit, times(1)).convertAndSend(eq(Eventos.EXCHANGE), eq(Eventos.USUARIO_REGISTRADO), any(Object.class));
    }

    @Test
    @DisplayName("Si el broker está caído el ingreso funciona y el evento se reintenta en el siguiente ingreso")
    void brokerCaidoSeReintenta() throws Exception {
        doThrow(new AmqpConnectException(new RuntimeException("caído")))
                .when(rabbit).convertAndSend(any(String.class), any(String.class), any(Object.class));
        ingresar("{\"nombre\":\"Ana\",\"rol\":\"COMPRADOR\"}").andExpect(status().isCreated());

        Mockito.reset(rabbit);
        ingresar("{\"nombre\":\"Ana\",\"rol\":\"COMPRADOR\"}").andExpect(status().isCreated());
        verify(rabbit, times(1)).convertAndSend(eq(Eventos.EXCHANGE), eq(Eventos.USUARIO_REGISTRADO), any(Object.class));
    }
}
