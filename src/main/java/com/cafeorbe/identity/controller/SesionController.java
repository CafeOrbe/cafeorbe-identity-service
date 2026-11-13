package com.cafeorbe.identity.controller;

import com.cafeorbe.contracts.Rol;
import com.cafeorbe.identity.seguridad.Auditoria;
import com.cafeorbe.identity.service.SesionService;
import com.cafeorbe.identity.service.VerificadorGoogle;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/sesion")
public class SesionController {

    /** SEG-01: el ID token que Google entregó al navegador y el rol con el que la persona quiere entrar. */
    public record GoogleRequest(
            @NotBlank(message = "Falta el token de Google")
            @Size(max = 4096, message = "El token de Google no es válido")
            String idToken,
            @NotNull(message = "El rol es obligatorio")
            Rol rol) {
    }

    public record UsuarioResponse(UUID id, String nombre, Rol rol) {
    }

    public record SesionResponse(String token, UsuarioResponse usuario, boolean nuevo) {
    }

    public record TicketResponse(String ticket, Instant expiraEn) {
    }

    private final SesionService sesiones;
    private final VerificadorGoogle google;
    private final Auditoria auditoria;

    public SesionController(SesionService sesiones, VerificadorGoogle google, Auditoria auditoria) {
        this.sesiones = sesiones;
        this.google = google;
        this.auditoria = auditoria;
    }

    /**
     * HU-01 y SEG-01: el único acceso a CaféOrbe es con una cuenta de Google. No hay usuario y contraseña propios
     * ni acceso por nombre: la identidad la comprueba Google y aquí solo se verifica su ID token.
     * 401 si el ID token es falso, alterado, vencido o de otra aplicación.
     */
    @PostMapping("/google")
    @ResponseStatus(HttpStatus.CREATED)
    public SesionResponse iniciarConGoogle(@Valid @RequestBody GoogleRequest peticion, HttpServletRequest http) {
        VerificadorGoogle.CuentaGoogle cuenta;
        try {
            cuenta = google.verificar(peticion.idToken());
        } catch (VerificadorGoogle.TokenGoogleInvalidoException e) {
            auditoria.rechazo("login_rechazado", e.motivo(), null, http);
            throw new SesionService.SesionInvalidaException(e.motivo());
        }
        var sesion = sesiones.iniciarSesionConGoogle(cuenta, peticion.rol());
        auditoria.exito("login_google", sesion.usuario().getId().toString());
        return respuesta(sesion);
    }

    /** SEG-05 / HU-02: cierra la sesión en el servidor; el token deja de servir aunque no haya vencido. */
    @PostMapping("/cerrar")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cerrar(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String autorizacion,
                       HttpServletRequest http) {
        try {
            var sesion = sesiones.cerrar(autorizacion);
            auditoria.exito("sesion_cerrada", sesion.usuarioId().toString());
        } catch (SesionService.SesionInvalidaException e) {
            auditoria.rechazo("cierre_rechazado", e.motivo(), null, http);
            throw e;
        }
    }

    /** SEG-04: ticket de un solo uso para abrir el WebSocket de la sala. */
    @PostMapping("/ticket-ws")
    @ResponseStatus(HttpStatus.CREATED)
    public TicketResponse ticketWebSocket(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String autorizacion,
            HttpServletRequest http) {
        try {
            var ticket = sesiones.emitirTicket(autorizacion);
            return new TicketResponse(ticket.ticket(), ticket.expiraEn());
        } catch (SesionService.SesionInvalidaException e) {
            auditoria.rechazo("ticket_rechazado", e.motivo(), null, http);
            throw e;
        }
    }

    private static SesionResponse respuesta(SesionService.Sesion sesion) {
        var u = sesion.usuario();
        return new SesionResponse(sesion.token(), new UsuarioResponse(u.getId(), u.getNombre(), u.getRol()),
                sesion.nuevo());
    }
}
